package com.github.lodestone.di

import android.content.Context
import android.os.Build
import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.data.local.files.LwjglNativesSource
import com.github.lodestone.data.remote.download.DownloadEngine
import com.github.lodestone.data.repository.RuntimeInstaller
import com.github.lodestone.data.repository.VersionInstaller
import com.github.lodestone.domain.usecase.BuildLaunchSpecUseCase
import com.github.lodestone.runtime.JavaRuntimeManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import java.io.File

@ContributesTo(AppScope::class)
interface DataModule {

    /**
     * The Minecraft directory lives in app-private storage rather than shared storage. The runtime
     * has to `dlopen` shared objects from it, and since Android 10 that is only permitted for files
     * the app owns — a game directory on the SD card could hold the assets but never the JVM.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideGameFiles(context: Context): GameFiles =
        GameFiles(File(context.filesDir, "minecraft"))

    @Provides
    @SingleIn(AppScope::class)
    fun provideDownloadEngine(client: HttpClient): DownloadEngine = DownloadEngine(client)

    @Provides
    @SingleIn(AppScope::class)
    fun provideVersionInstaller(
        client: HttpClient,
        downloads: DownloadEngine,
        files: GameFiles,
        runtimeInstaller: RuntimeInstaller,
    ): VersionInstaller = VersionInstaller(client, downloads, files, runtimeInstaller)

    @Provides
    @SingleIn(AppScope::class)
    fun provideRuntimeInstaller(
        context: Context,
        client: HttpClient,
        downloads: DownloadEngine,
        files: GameFiles,
        runtimes: JavaRuntimeManager,
    ): RuntimeInstaller = RuntimeInstaller(
        client = client,
        downloads = downloads,
        files = files,
        runtimes = runtimes,
        // Opened lazily rather than read here: the packaged manifest is only ever needed when the
        // network and the on-disk cache have both failed.
        bundledManifest = { context.assets.open(RUNTIME_MANIFEST_ASSET) },
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideBuildLaunchSpec(
        files: GameFiles,
        runtimes: JavaRuntimeManager,
        lwjglNatives: LwjglNativesSource,
    ): BuildLaunchSpecUseCase = BuildLaunchSpecUseCase(files, runtimes, lwjglNatives)

    /**
     * The packaged LWJGL sets, which are assets rather than jniLibs: the installer only ever
     * extracts the top level of `lib/<abi>/`, so two sets could not be told apart there without
     * renaming the libraries, and LWJGL loads them by their real names.
     *
     * The ABIs are tried in the order the device prefers them, which is also the order the APK's
     * own libraries are chosen in, so an x86_64 emulator running an APK built for both picks its
     * own rather than failing over to one it cannot load.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideLwjglNatives(context: Context): LwjglNativesSource =
        LwjglNativesSource { set, name ->
            Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                runCatching { context.assets.open("${set.assetPath}/$abi/$name") }.getOrNull()
            }
        }

    @Provides
    @SingleIn(AppScope::class)
    fun provideJavaRuntimeManager(files: GameFiles): JavaRuntimeManager = JavaRuntimeManager(files)
}

/** Packaged from the same path the published manifest is served from. */
private const val RUNTIME_MANIFEST_ASSET = "runtimes.json"
