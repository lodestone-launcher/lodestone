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

        // Mojang's manifests carry Linux natives for x86-64 only, so the ones the version installer
        // unpacked cannot be loaded here at all. The cross-built replacements ride in the APK and
        // have to sit beside them, because `org.lwjgl.librarypath` names a single directory.
        overrideExtractedNatives(nativeLibraryDir, nativesDirectory)

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
        val translationLayer = runtimes.translationLayer(nativeLibraryDir, options.renderer)
        val eglLibrary = runtimes.eglLibrary(nativeLibraryDir, options.renderer, translationLayer)
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
                //
                // Deliberately not `all=debug`: that writes a quarter of a gigabyte before the game
                // reaches its title screen, and the cost of formatting it dominates startup. The
                // `library` tag is the one that earns its place here, because it traces every
                // dlopen the VM performs — which is exactly how the shims are diagnosed.
                add("-Xlog:all=warning,library=info:stderr")
            }
            addAll(argumentBuilder.buildJvmArgs(version, environment, paths, options))
            addAll(runtimes.lwjglProperties(nativesDirectory, nativeLibraryDir, translationLayer))
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
                translationLayer = translationLayer,
                eglLibrary = eglLibrary,
                libraryPath = listOf(nativesDirectory, nativeLibraryDir),
                environment = runtimes.environmentFor(feature, nativesDirectory),
            ),
        )
    }

    /**
     * Replaces the version's unpacked LWJGL natives with the cross-built ones from the APK.
     *
     * Copied rather than linked: `nativeLibraryDir` and app storage are frequently different
     * filesystems, so a hard link cannot be relied on. A failure here is deliberately not fatal —
     * the launch proceeds and reports a missing library, which is easier to diagnose.
     *
     * The shims themselves are pointedly *not* copied here. `liblodestone_glfw.so` is already loaded
     * by the Activity to receive the surface, and the linker keys a mapping on its path: a second
     * copy under a second path would be a second library, with its own window state, so the surface
     * would arrive at one and EGL would run in the other.
     */
    private fun overrideExtractedNatives(from: File, to: File) {
        // `libfreetype.so` and `libopenal.so` carry no `lwjgl_` prefix because LWJGL's freetype and
        // openal bindings dispatch through libffi rather than through a JNI stub of their own, so
        // the natives jar ships the third-party library itself.
        val natives = listOf(
            "liblwjgl.so",
            "liblwjgl_opengl.so",
            "liblwjgl_stb.so",
            "liblwjgl_tinyfd.so",
            "libfreetype.so",
            "libopenal.so",
        )
        for (name in natives) {
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
