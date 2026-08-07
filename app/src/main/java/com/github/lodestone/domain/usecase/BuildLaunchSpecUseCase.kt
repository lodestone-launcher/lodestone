package com.github.lodestone.domain.usecase

import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.domain.model.account.MinecraftAccount
import com.github.lodestone.domain.model.launch.LaunchOptions
import com.github.lodestone.domain.model.launch.LaunchSpec
import com.github.lodestone.domain.model.version.LaunchEnvironment
import com.github.lodestone.domain.model.version.ResolvedVersion
import com.github.lodestone.runtime.JavaRuntimeManager
import java.io.File

/**
 * Turns an installed version into a concrete [LaunchSpec].
 *
 * This is the last step before the VM starts: it picks the runtime the version asks for, assembles
 * the classpath in manifest order, resolves every `${...}` placeholder, and points LWJGL at the
 * shims that stand in for the desktop libraries it would otherwise try to unpack.
 */
class BuildLaunchSpecUseCase(
    private val files: GameFiles,
    private val runtimes: JavaRuntimeManager,
    private val argumentBuilder: LaunchArgumentBuilder = LaunchArgumentBuilder(),
) {

    sealed interface Result {
        data class Ready(val spec: LaunchSpec) : Result

        /** The version needs a Java runtime that is not installed yet. */
        data class MissingRuntime(val feature: Int, val component: String) : Result
    }

    operator fun invoke(
        version: ResolvedVersion,
        account: MinecraftAccount,
        environment: LaunchEnvironment,
        options: LaunchOptions,
        nativeLibraryDir: File,
    ): Result {
        val feature = runtimes.featureFor(version.javaVersion)
        val libjvm = runtimes.libjvm(feature)
            ?: return Result.MissingRuntime(feature, version.javaVersion.component)

        val nativesDirectory = files.nativesDirectory(version.id)
        nativesDirectory.mkdirs()

        // The shims ship inside the APK, but LWJGL and the JVM load libraries from one directory.
        // Linking them alongside the extracted natives is what lets `-Dorg.lwjgl.glfw.libname`
        // resolve without copying tens of megabytes on every launch.
        linkShims(nativeLibraryDir, nativesDirectory)

        val classpath = buildList {
            version.classpathLibraries(environment).forEach { add(files.library(it.path)) }
            // The client jar goes last so that a mod loader's overrides on the classpath win.
            add(files.versionJar(version.clientJarVersionId))
        }.filter(File::isFile)

        val paths = LaunchArgumentBuilder.Paths(
            gameDirectory = files.root,
            assetsRoot = files.assets,
            virtualAssets = files.virtualAssets(version.assetsId).takeIf { version.usesLegacyAssetLayout },
            nativesDirectory = nativesDirectory,
            librariesDirectory = files.libraries,
            classpath = classpath,
            loggingConfig = version.logging?.let { File(files.logConfigs, it.file.id) },
        )

        val javaHome = runtimes.runtimeRoot(feature)
        val jvmArgs = buildList {
            // The `java` launcher derives java.home from its own location and hands it to the VM.
            // An embedder calling JNI_CreateJavaVM gets no such help, and without it HotSpot cannot
            // find lib/modules and dies with a bare "Error occurred during initialization of VM".
            add("-Djava.home=${javaHome.absolutePath}")
            // HotSpot's perf-counter file goes to a hardcoded /tmp on Linux, which java.io.tmpdir
            // does not redirect and which does not exist on Android. Nothing reads these counters
            // here — they exist for jstat and jcmd, neither of which can attach on a phone.
            add("-XX:-UsePerfData")
            if (options.verboseVmStartup) {
                // HotSpot's unified logging, aimed at stderr so the stdio mirror captures it. A VM
                // that dies during initialisation often does so without printing anything on its
                // own, and this is the only way to see how far it got.
                add("-Xlog:all=debug:stderr")
            }
            addAll(argumentBuilder.buildJvmArgs(version, environment, paths, options))
            addAll(runtimes.lwjglProperties(nativesDirectory))
        }
        val gameArgs = argumentBuilder.buildGameArgs(version, environment, paths, account, options)

        File(files.root, "tmp").mkdirs()

        return Result.Ready(
            LaunchSpec(
                versionId = version.id,
                mainClass = version.mainClass,
                jvmArgs = jvmArgs,
                gameArgs = gameArgs,
                libjvm = libjvm,
                javaHome = javaHome,
                gameDirectory = files.root,
                nativesDirectory = nativesDirectory,
                libraryPath = listOf(nativesDirectory, nativeLibraryDir),
                environment = runtimes.environmentFor(feature, nativesDirectory),
            ),
        )
    }

    /**
     * Makes the APK's bundled shims visible from the natives directory.
     *
     * Copied rather than linked: `nativeLibraryDir` and app storage are frequently different
     * filesystems, so a hard link cannot be relied on. A failure here is deliberately not fatal —
     * the launch proceeds and reports a missing library, which is easier to diagnose.
     */
    private fun linkShims(from: File, to: File) {
        val shims = listOf("liblodestone_glfw.so", "libgl4es.so", "liblwjgl.so", "liblwjgl_stb.so")
        for (name in shims) {
            val source = File(from, name)
            if (!source.isFile) {
                continue
            }
            val destination = File(to, name)
            if (destination.isFile && destination.length() == source.length()) {
                continue
            }
            destination.delete()
            runCatching { source.copyTo(destination, overwrite = true) }
                .onSuccess { destination.setExecutable(true, false) }
        }
    }
}
