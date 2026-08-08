package com.github.lodestone.data.repository

import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.common.io.Checksum
import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.data.local.files.TarGzExtractor
import com.github.lodestone.data.remote.download.DownloadEngine
import com.github.lodestone.data.remote.download.DownloadProgress
import com.github.lodestone.data.remote.download.DownloadRequest
import com.github.lodestone.domain.model.runtime.RuntimeDescriptor
import com.github.lodestone.domain.model.runtime.RuntimeManifest
import com.github.lodestone.domain.model.version.JavaVersionRequirement
import com.github.lodestone.runtime.JavaRuntimeManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

/** What the runtime installer is doing, for the same progress surface the version install uses. */
sealed interface RuntimeStage {
    data object ResolvingManifest : RuntimeStage
    data class Downloading(val feature: Int, val progress: DownloadProgress) : RuntimeStage
    data class Unpacking(val feature: Int) : RuntimeStage
    data class Done(val feature: Int) : RuntimeStage
}

/** A runtime that cannot be installed, with a message worth showing to the person waiting on it. */
class RuntimeUnavailableException(message: String) : IOException(message)

/**
 * Fetches and unpacks the Java runtimes the game asks for.
 *
 * A runtime is a two-hundred-megabyte archive, which makes every property of the version install
 * matter more here rather than less: the download resumes into a `.part` file, the unpack lands in
 * a sibling directory that is renamed only once complete, and an already-installed runtime costs
 * nothing to confirm.
 */
class RuntimeInstaller(
    private val client: HttpClient,
    private val downloads: DownloadEngine,
    private val files: GameFiles,
    private val runtimes: JavaRuntimeManager,
    /** The copy packaged in the APK, read when the published manifest cannot be reached. */
    private val bundledManifest: () -> InputStream,
    private val abi: String = deviceAbi(),
) {

    /**
     * Installs the runtime for [feature] unless it is already present.
     *
     * @return true when a download happened, so the caller can tell "installed" from "already had".
     */
    suspend fun ensureInstalled(
        feature: Int,
        onStage: (RuntimeStage) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        if (runtimes.libjvm(feature) != null) {
            onStage(RuntimeStage.Done(feature))
            return@withContext false
        }

        onStage(RuntimeStage.ResolvingManifest)
        val descriptor = describe(feature)

        val root = runtimes.runtimeRoot(feature)
        val staging = File(root.parentFile, "${root.name}.incomplete")
        val archive = File(files.runtimes, descriptor.url.substringAfterLast('/').substringBefore('?'))
        // A previous attempt that died mid-unpack leaves a staging directory holding most of a
        // runtime. It is never adopted, because there is no way to tell how far it got.
        staging.deleteRecursively()
        checkFreeSpace(descriptor, archive)

        Timber.i("Fetching Java %d runtime (%s)", feature, describeSize(descriptor))
        downloads.download(
            listOf(
                DownloadRequest(
                    url = descriptor.url,
                    destination = archive,
                    checksum = Checksum.sha256(descriptor.sha256),
                    size = descriptor.archiveSize,
                    label = "Java $feature runtime",
                ),
            ),
        ) { progress -> onStage(RuntimeStage.Downloading(feature, progress)) }

        onStage(RuntimeStage.Unpacking(feature))
        val entries = try {
            // The archive wraps the image in a single `jdk/` directory, which the launcher's layout
            // does not want: `runtimes/java-21` is itself the JAVA_HOME.
            TarGzExtractor.extract(archive, staging, stripComponents = 1)
        } catch (failure: Throwable) {
            // A filesystem that filled up mid-unpack is the likeliest cause, so leaving several
            // hundred megabytes of half a runtime behind would make the next attempt worse.
            staging.deleteRecursively()
            throw failure
        }
        Timber.i("Unpacked %d files for Java %d", entries, feature)

        root.deleteRecursively()
        if (!staging.renameTo(root)) {
            staging.deleteRecursively()
            throw RuntimeUnavailableException("Could not move the Java $feature runtime into place")
        }
        // Deliberately after the rename: `applyPermissions` walks the runtime root, and a runtime
        // is only at its root once it is whole.
        runtimes.applyPermissions(feature)

        val libjvm = runtimes.libjvm(feature)
        if (libjvm == null) {
            root.deleteRecursively()
            throw RuntimeUnavailableException(
                "The Java $feature runtime unpacked without a libjvm.so; the archive is not a JDK image",
            )
        }
        Timber.i("Java %d runtime ready at %s", feature, libjvm.absolutePath)

        // Only once the runtime is known good, because until then the archive is the only copy of
        // two hundred megabytes that were paid for over someone's mobile data.
        archive.delete()
        onStage(RuntimeStage.Done(feature))
        true
    }

    /** The published archive for [feature] on this device, or a refusal that says why not. */
    suspend fun describe(feature: Int): RuntimeDescriptor {
        val manifest = manifest()
        return manifest.find(feature, abi) ?: throw RuntimeUnavailableException(
            buildString {
                append("No Java $feature runtime is published for $abi yet")
                val available = manifest.featuresFor(abi)
                if (available.isNotEmpty()) {
                    append(" (available: ${available.joinToString(", ")})")
                }
            },
        )
    }

    fun isInstalled(feature: Int): Boolean = runtimes.libjvm(feature) != null

    fun featureFor(requirement: JavaVersionRequirement): Int = runtimes.featureFor(requirement)

    // ---------------------------------------------------------------------------------------------
    // Manifest
    // ---------------------------------------------------------------------------------------------

    /**
     * The published manifest, falling back to the on-disk cache and then to the copy in the APK.
     *
     * The bundled copy is the same file the URL serves, so an offline first run installs whatever
     * was current when the app was built rather than failing outright.
     */
    private suspend fun manifest(): RuntimeManifest {
        val cache = File(files.runtimes, MANIFEST_CACHE)
        runCatching {
            val body = client.get(RuntimeManifest.URL).bodyAsText()
            val parsed = parse(body)
            cache.parentFile?.mkdirs()
            cache.writeText(body)
            return parsed
        }.onFailure { Timber.w(it, "Could not fetch the runtime manifest; falling back") }

        if (cache.isFile) {
            runCatching { return parse(cache.readText()) }
                .onFailure { Timber.w(it, "Ignoring an unreadable cached runtime manifest") }
        }
        return parse(bundledManifest().use { it.readBytes().decodeToString() })
    }

    private fun parse(text: String): RuntimeManifest =
        LodestoneJson.decodeFromString(RuntimeManifest.serializer(), text)

    // ---------------------------------------------------------------------------------------------
    // Space
    // ---------------------------------------------------------------------------------------------

    /**
     * Refuses before downloading when the archive and its unpacked form will not both fit.
     *
     * Both are counted because they coexist: the archive is only deleted once the runtime is known
     * good. Running out of space midway through an unpack is the failure this exists to prevent,
     * and it is a far more confusing one than being told up front.
     *
     * `usableSpace` rather than `StorageManager.getAllocatableBytes`, which would also count cached
     * data the system could evict: that number is larger, and the failure worth avoiding here is
     * starting a download that cannot finish rather than refusing one that could.
     */
    private fun checkFreeSpace(descriptor: RuntimeDescriptor, archive: File) {
        files.runtimes.mkdirs()
        val archiveSize = descriptor.archiveSize ?: return
        val alreadyFetched = if (archive.isFile) archive.length() else 0L
        val unpacked = descriptor.unpackedSize ?: 0L
        val required = archiveSize - alreadyFetched + unpacked + SPACE_MARGIN
        val available = files.runtimes.usableSpace
        // A filesystem that will not report its free space is not a reason to refuse to install.
        if (available <= 0 || available >= required) {
            return
        }
        throw RuntimeUnavailableException(
            "The Java ${descriptor.feature} runtime needs ${formatBytes(required)} free, " +
                "but only ${formatBytes(available)} is available",
        )
    }

    companion object {
        private const val MANIFEST_CACHE = "runtimes.json"

        /** How large the download is, for a prompt that does not understate what it is asking for. */
        fun describeSize(descriptor: RuntimeDescriptor): String =
            descriptor.archiveSize?.let(::formatBytes) ?: "unknown size"

        /** Headroom for the log, the temporary files an unpack creates, and general prudence. */
        private const val SPACE_MARGIN = 64L * 1024 * 1024

        private fun deviceAbi(): String =
            android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            else -> "%.0f MB".format(bytes / (1024.0 * 1024))
        }
    }
}
