package com.github.lodestone.data.repository

import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.common.io.Checksum
import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.data.local.files.NativesExtractor
import com.github.lodestone.data.remote.download.DownloadEngine
import com.github.lodestone.data.remote.download.DownloadProgress
import com.github.lodestone.data.remote.download.DownloadRequest
import com.github.lodestone.data.remote.download.VerificationMode
import com.github.lodestone.domain.model.version.AssetIndex
import com.github.lodestone.domain.model.version.LaunchEnvironment
import com.github.lodestone.domain.model.version.ResolvedVersion
import com.github.lodestone.domain.model.version.VersionEntry
import com.github.lodestone.domain.model.version.VersionJson
import com.github.lodestone.domain.model.version.VersionJsonSource
import com.github.lodestone.domain.model.version.VersionManifest
import com.github.lodestone.domain.model.version.VersionResolver
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** What the installer is doing, so the UI can say something more useful than a bare percentage. */
sealed interface InstallStage {
    data object ResolvingVersion : InstallStage
    data object FetchingAssetIndex : InstallStage
    data class Downloading(val progress: DownloadProgress) : InstallStage
    data object UnpackingNatives : InstallStage
    data object PreparingAssets : InstallStage
    data class InstallingRuntime(val stage: RuntimeStage) : InstallStage

    /**
     * The game is installed but its Java runtime is not published for this device yet. Reported
     * rather than thrown: everything else finished, and the install is worth keeping.
     */
    data class RuntimeUnavailable(val reason: String) : InstallStage
    data class Done(val version: ResolvedVersion) : InstallStage
}

/**
 * Downloads everything a version needs to run and lays it out the way the game expects.
 *
 * The install is idempotent and resumable: every file is content-addressed by SHA-1, so re-running
 * after a failure only fetches what is genuinely missing.
 */
class VersionInstaller(
    private val client: HttpClient,
    private val downloads: DownloadEngine,
    private val files: GameFiles,
    private val runtimeInstaller: RuntimeInstaller,
) {

    suspend fun install(
        entry: VersionEntry,
        environment: LaunchEnvironment,
        verification: VerificationMode = VerificationMode.SIZE_ONLY,
        onStage: (InstallStage) -> Unit = {},
    ): ResolvedVersion = withContext(Dispatchers.IO) {
        files.prepare()

        onStage(InstallStage.ResolvingVersion)
        val version = VersionResolver.resolve(entry.id, cachingSource(entry))

        onStage(InstallStage.FetchingAssetIndex)
        val assetIndex = fetchAssetIndex(version)

        val requests = buildList {
            addAll(clientJarRequest(version))
            addAll(libraryRequests(version, environment))
            addAll(assetRequests(assetIndex))
            addAll(loggingRequest(version))
        }

        Timber.i("Installing %s: %d files", version.id, requests.size)
        downloads.download(requests, verification) { progress ->
            onStage(InstallStage.Downloading(progress))
        }

        onStage(InstallStage.UnpackingNatives)
        extractNatives(version, environment)

        onStage(InstallStage.PreparingAssets)
        materialiseLegacyAssets(version, assetIndex)

        installRuntime(version, onStage)

        onStage(InstallStage.Done(version))
        version
    }

    /**
     * Fetches the Java runtime the version asks for.
     *
     * Last, because it is by far the largest download and everything before it is useless without
     * the game files anyway. A runtime that is not published — Java 8 is still being cross-built —
     * leaves an install that is complete apart from being unable to launch, which is worth saying
     * out loud rather than throwing away.
     */
    private suspend fun installRuntime(version: ResolvedVersion, onStage: (InstallStage) -> Unit) {
        val feature = runtimeInstaller.featureFor(version.javaVersion)
        try {
            runtimeInstaller.ensureInstalled(feature) { stage ->
                onStage(InstallStage.InstallingRuntime(stage))
            }
        } catch (failure: RuntimeUnavailableException) {
            Timber.w(failure, "No Java %d runtime for this device", feature)
            onStage(InstallStage.RuntimeUnavailable(failure.message.orEmpty()))
        }
    }

    /** Resolves an already-installed version from the on-disk manifests, without downloading. */
    suspend fun resolve(entry: VersionEntry, environment: LaunchEnvironment): ResolvedVersion =
        withContext(Dispatchers.IO) { VersionResolver.resolve(entry.id, cachingSource(entry)) }

    /**
     * Resolves version manifests, caching each one on disk under the layout the official launcher
     * uses so an existing installation can be adopted and so mod loaders can find their parents.
     */
    private fun cachingSource(root: VersionEntry): VersionJsonSource = VersionJsonSource { id ->
        val cached = files.versionJson(id)
        if (cached.isFile) {
            runCatching { parseVersion(cached.readText()) }
                .onFailure { Timber.w(it, "Ignoring unreadable cached manifest for %s", id) }
                .getOrNull()
                ?.let { return@VersionJsonSource it }
        }

        val url = if (id == root.id) {
            root.url
        } else {
            // A parent named by `inheritsFrom` is looked up in the published manifest; a mod loader
            // manifest that names a version Mojang never shipped simply cannot be resolved.
            fetchManifest().find(id)?.url
                ?: error("Version '$id' is not in Mojang's manifest and is not installed locally")
        }

        val body: String = client.get(url).body()
        cached.parentFile?.mkdirs()
        cached.writeText(body)
        parseVersion(body)
    }

    private fun parseVersion(text: String): VersionJson =
        LodestoneJson.decodeFromString(VersionJson.serializer(), text)

    suspend fun fetchManifest(): VersionManifest = client.get(VersionManifest.URL).body()

    // ---------------------------------------------------------------------------------------------
    // File sets
    // ---------------------------------------------------------------------------------------------

    private fun clientJarRequest(version: ResolvedVersion): List<DownloadRequest> {
        val jar = version.clientJar ?: return emptyList()
        val url = jar.url ?: return emptyList()
        return listOf(
            DownloadRequest(
                url = url,
                // The jar is stored under the version that actually published it, so a mod-loader
                // install shares the vanilla jar instead of downloading its own copy.
                destination = files.versionJar(version.clientJarVersionId),
                checksum = Checksum.sha1(jar.sha1),
                size = jar.size,
                label = "${version.clientJarVersionId}.jar",
            ),
        )
    }

    private fun libraryRequests(
        version: ResolvedVersion,
        environment: LaunchEnvironment,
    ): List<DownloadRequest> =
        (version.classpathLibraries(environment) + version.nativeLibraries(environment))
            .mapNotNull { entry ->
                val url = entry.artifact.url ?: return@mapNotNull null
                DownloadRequest(
                    url = url,
                    destination = files.library(entry.path),
                    checksum = Checksum.sha1(entry.artifact.sha1),
                    size = entry.artifact.size,
                    label = entry.library.name,
                )
            }

    private suspend fun fetchAssetIndex(version: ResolvedVersion): AssetIndex? {
        val reference = version.assetIndex ?: return null
        val destination = files.assetIndex(reference.id)

        if (!destination.isFile) {
            val url = reference.url ?: return null
            downloads.download(
                listOf(
                    DownloadRequest(
                        url = url,
                        destination = destination,
                        checksum = Checksum.sha1(reference.sha1),
                        size = reference.size,
                        label = "asset index ${reference.id}",
                    ),
                ),
            )
        }
        return runCatching {
            LodestoneJson.decodeFromString(AssetIndex.serializer(), destination.readText())
        }.onFailure { Timber.e(it, "Could not read asset index %s", reference.id) }.getOrNull()
    }

    private fun assetRequests(index: AssetIndex?): List<DownloadRequest> {
        val objects = index?.objects ?: return emptyList()
        // Many logical names share one hash — the same sound or texture referenced from several
        // paths — and downloading a hash twice would be pure waste.
        return objects.values
            .distinctBy(com.github.lodestone.domain.model.version.AssetObject::hash)
            .map { asset ->
                DownloadRequest(
                    url = asset.url,
                    destination = files.assetObject(asset.objectPath),
                    checksum = Checksum.sha1(asset.hash),
                    size = asset.size,
                    label = "assets",
                )
            }
    }

    private fun loggingRequest(version: ResolvedVersion): List<DownloadRequest> {
        val logging = version.logging ?: return emptyList()
        val url = logging.file.url ?: return emptyList()
        return listOf(
            DownloadRequest(
                url = url,
                destination = File(files.logConfigs, logging.file.id),
                checksum = Checksum.sha1(logging.file.sha1),
                size = logging.file.size,
                label = logging.file.id,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Post-download layout
    // ---------------------------------------------------------------------------------------------

    private fun extractNatives(version: ResolvedVersion, environment: LaunchEnvironment) {
        val target = files.nativesDirectory(version.id)
        // A stale shared object from a previous install of the same version would be loaded in
        // preference to the correct one, so the directory is rebuilt from scratch.
        target.deleteRecursively()

        val jars = version.nativeLibraries(environment).map { entry ->
            NativesExtractor.NativeJar(
                file = files.library(entry.path),
                extract = entry.library.extract,
            )
        }
        NativesExtractor.extract(jars, target)
    }

    /**
     * Builds the flat asset trees the pre-1.8 versions read from.
     *
     * `legacy` versions want a shared `assets/virtual/<index>` tree; `pre-1.6` versions want the
     * files inside the game directory itself. Both address assets by their logical names, which the
     * hashed object store cannot serve directly.
     */
    private fun materialiseLegacyAssets(version: ResolvedVersion, index: AssetIndex?) {
        if (!version.usesLegacyAssetLayout || index == null) {
            return
        }
        val target = if (version.mapsAssetsToResources) {
            files.resources
        } else {
            files.virtualAssets(version.assetsId)
        }

        for ((logicalName, asset) in index.objects) {
            val source = files.assetObject(asset.objectPath)
            if (!source.isFile) {
                continue
            }
            val destination = File(target, logicalName)
            // Skipping identical files keeps a re-launch from rewriting hundreds of megabytes.
            if (destination.isFile && destination.length() == asset.size) {
                continue
            }
            destination.parentFile?.mkdirs()
            runCatching { source.copyTo(destination, overwrite = true) }
                .onFailure { Timber.w(it, "Could not place legacy asset %s", logicalName) }
        }
    }
}
