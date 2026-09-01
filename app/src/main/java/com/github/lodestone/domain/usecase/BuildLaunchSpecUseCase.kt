package com.github.lodestone.domain.usecase

import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.data.local.files.Lwjgl2CompatInstaller
import com.github.lodestone.data.local.files.Lwjgl2CompatSource
import com.github.lodestone.data.local.files.LwjglNativesInstaller
import com.github.lodestone.data.local.files.LwjglNativesSource
import com.github.lodestone.domain.model.account.MinecraftAccount
import com.github.lodestone.domain.model.launch.LaunchOptions
import com.github.lodestone.domain.model.launch.LaunchSpec
import com.github.lodestone.domain.model.launch.Renderer
import com.github.lodestone.domain.model.version.GraphicsBackend
import com.github.lodestone.domain.model.version.LWJGL2_COMPAT_COORDINATE
import com.github.lodestone.domain.model.version.LaunchEnvironment
import com.github.lodestone.domain.model.version.LwjglSelection
import com.github.lodestone.domain.model.version.ResolvedVersion
import com.github.lodestone.domain.model.version.isSupersededByLwjgl2Compat
import com.github.lodestone.domain.model.version.lwjglSelection
import com.github.lodestone.domain.model.version.supportsVulkanBackend
import com.github.lodestone.runtime.JavaRuntimeManager
import timber.log.Timber
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
    private val lwjglNatives: LwjglNativesSource,
    private val lwjgl2Compat: Lwjgl2CompatSource,
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

        val selection = version.lwjglSelection(environment)

        // Per version, and the same directory `org.lwjgl.librarypath` names: LWJGL's Java side and
        // its JNI libraries only ever bind to their own release, so the set that lands here is the
        // one this version's manifest pins rather than a global one.
        val nativesDirectory = files.nativesDirectory(version.id)
        nativesDirectory.mkdirs()
        when (selection) {
            is LwjglSelection.Packaged -> {
                if (!selection.isExact) {
                    Timber.w(
                        "%s pins LWJGL %s; installing the packaged %s set instead",
                        version.id,
                        selection.requested,
                        selection.set.version,
                    )
                }
                LwjglNativesInstaller.install(
                    set = selection.set,
                    source = lwjglNatives,
                    nativeLibraryDir = nativeLibraryDir,
                    target = nativesDirectory,
                )
            }

            is LwjglSelection.Compat2 -> {
                Timber.i(
                    "%s pins LWJGL %s; serving it through the compatibility layer on %s",
                    version.id,
                    selection.requested,
                    selection.set.version,
                )
                LwjglNativesInstaller.install(
                    set = selection.set,
                    source = lwjglNatives,
                    nativeLibraryDir = nativeLibraryDir,
                    target = nativesDirectory,
                    compat2 = true,
                )
            }

            LwjglSelection.Absent -> Unit
        }

        val compatJar = (selection as? LwjglSelection.Compat2)?.let {
            Lwjgl2CompatInstaller.install(
                source = lwjgl2Compat,
                target = files.library(LWJGL2_COMPAT_COORDINATE.path),
            )
        }

        val classpath = buildList {
            // Ahead of everything, because it declares the org.lwjgl classes the game asks for and
            // the first entry that defines a name is the one the VM resolves.
            compatJar?.let(::add)
            version.classpathLibraries(environment)
                .filterNot { compatJar != null && it.isSupersededByLwjgl2Compat() }
                .forEach { add(files.library(it.path)) }
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
        // Which backend the game itself will drive. Vulkan is the game's own renderer talking to
        // the device driver with nothing in between, so it is preferred wherever the version has
        // one; everything older has only the OpenGL path, and reaches it through a translation
        // layer. An explicit choice of a Vulkan launch on a version that cannot do it would leave
        // the game with no renderer at all, so it falls back rather than failing.
        val backend = when {
            !version.supportsVulkanBackend -> GraphicsBackend.OPENGL
            options.renderer == Renderer.GL4ES -> GraphicsBackend.OPENGL
            else -> GraphicsBackend.VULKAN
        }
        if (options.renderer.isVulkan && backend != GraphicsBackend.VULKAN) {
            Timber.w("%s has no Vulkan renderer; falling back to OpenGL", version.id)
        }

        // Empty on the Vulkan path, and that emptiness is the instruction: the shim brings up no
        // EGL and opens no layer, because the game is about to create its own device and present
        // to the window itself.
        // A Vulkan choice that lands on the OpenGL path has nothing to say about which layer to
        // use, and its own chain is empty — asking it would leave the launch with no renderer at
        // all. What someone asking for Vulkan wants from a version that has none is the best
        // OpenGL path, which is what Automatic means.
        val glRenderer = if (options.renderer.isVulkan) Renderer.AUTO else options.renderer
        val renderers = when (backend) {
            GraphicsBackend.VULKAN -> emptyList()
            GraphicsBackend.OPENGL -> runtimes.rendererCandidates(nativeLibraryDir, glRenderer)
        }
        val jvmArgs = buildList {
            // The `java` launcher derives java.home from its own location and hands it to the VM.
            // An embedder calling JNI_CreateJavaVM gets no such help, and without it HotSpot cannot
            // find lib/modules and dies with a bare "Error occurred during initialization of VM".
            add("-Djava.home=${javaHome.absolutePath}")
            // HotSpot's perf-counter file goes to a hardcoded /tmp on Linux, which java.io.tmpdir
            // does not redirect and which does not exist on Android. Nothing reads these counters
            // here — they exist for jstat and jcmd, neither of which can attach on a phone.
            add("-XX:-UsePerfData")
            // Unified logging arrived in Java 9. On 8 the option is not merely ignored: HotSpot
            // rejects it outright, JNI_CreateJavaVM returns JNI_ERR, and every pre-1.13 version
            // fails to launch in a debug build for a reason that has nothing to do with them.
            if (options.verboseVmStartup && feature >= FIRST_UNIFIED_LOGGING_FEATURE) {
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
            addAll(runtimes.lwjglProperties(nativesDirectory, nativeLibraryDir))
        }
        val gameArgs = buildList {
            addAll(argumentBuilder.buildGameArgs(version, environment, paths, account, options))
            // Only ever emitted for a version whose Main declares the option. joptsimple rejects an
            // argument it does not know outright, so passing this to anything older would turn a
            // renderer preference into a launch that never starts.
            if (version.supportsVulkanBackend) {
                add("--graphicsBackend")
                add(backend.argument)
            }
        }

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
                renderers = renderers,
                graphicsBackend = backend,
                openglLibrary = runtimes.openglLibrary(nativeLibraryDir),
                libraryPath = listOf(nativesDirectory, nativeLibraryDir),
                environment = runtimes.environmentFor(feature, nativesDirectory),
            ),
        )
    }

    private companion object {
        /** The first feature release whose HotSpot understands `-Xlog`. */
        const val FIRST_UNIFIED_LOGGING_FEATURE = 9
    }
}
