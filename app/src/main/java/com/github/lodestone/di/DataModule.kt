package com.github.lodestone.di

import android.content.Context
import com.github.lodestone.data.local.files.GameFiles
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
    ): BuildLaunchSpecUseCase = BuildLaunchSpecUseCase(files, runtimes)

    @Provides
    @SingleIn(AppScope::class)
    fun provideJavaRuntimeManager(files: GameFiles): JavaRuntimeManager = JavaRuntimeManager(files)
}

/** Packaged from the same path the published manifest is served from. */
private const val RUNTIME_MANIFEST_ASSET = "runtimes.json"
