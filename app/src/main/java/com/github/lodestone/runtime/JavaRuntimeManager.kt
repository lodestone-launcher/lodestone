package com.github.lodestone.runtime

import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.domain.model.launch.Renderer
import com.github.lodestone.domain.model.launch.RendererCandidate
import com.github.lodestone.domain.model.version.JavaVersionRequirement
import com.github.lodestone.domain.model.version.LwjglNativeSet
import timber.log.Timber
import java.io.File

/**
 * Finds and unpacks the Java runtime a version asks for.
 *
 * Minecraft names its runtime through `javaVersion.component`, and Lodestone ships one cross-built
 * runtime per feature release rather than per component: several components map onto the same JDK,
 * and a few name releases that are out of support.
 */
class JavaRuntimeManager(private val files: GameFiles) {

    /**
     * The feature release that satisfies [requirement].
     *
     * `java-runtime-alpha` asks for Java 16, which is long out of support and which nothing in the
     * game actually depends on; 17 runs those versions unchanged, so it is substituted rather than
     * cross-compiled separately.
     */
    fun featureFor(requirement: JavaVersionRequirement): Int = when {
        requirement.majorVersion <= 8 -> 8
        requirement.majorVersion == 16 -> 17
        else -> requirement.majorVersion
    }

    fun runtimeRoot(feature: Int): File = files.runtimeDirectory("java-$feature")

    /**
     * The `libjvm.so` the bridge opens, or null when the runtime is absent or incomplete.
     *
     * HotSpot lives under `lib/server/` on modern releases and `lib/<arch>/server/` on 8, so both
     * layouts are probed rather than assuming one.
     */
    fun libjvm(feature: Int): File? {
        val root = runtimeRoot(feature)
        if (!root.isDirectory) {
            return null
        }
        val candidates = listOf(
            File(root, "lib/server/libjvm.so"),
            File(root, "lib/${abiDirectory()}/server/libjvm.so"),
            File(root, "jre/lib/${abiDirectory()}/server/libjvm.so"),
        )
        return candidates.firstOrNull(File::isFile)
    }

    fun isInstalled(requirement: JavaVersionRequirement): Boolean =
        libjvm(featureFor(requirement)) != null

    /**
     * Restores the executable and readable bits on a freshly unpacked runtime.
     *
     * The runtime tarballs are stored without permissions, because Android's extraction path does
     * not preserve them reliably. Everything under `bin/` has to be executable for the launcher's
     * fallback path, and every shared object has to be readable for `dlopen`.
     */
    fun applyPermissions(feature: Int) {
        val root = runtimeRoot(feature)
        if (!root.isDirectory) {
            return
        }
        root.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
                return@forEach
            }
            file.setReadable(true, false)
            val isExecutable = file.parentFile?.name == "bin" ||
                file.name.endsWith(".so") ||
                file.name.contains(".so.")
            if (isExecutable) {
                file.setExecutable(true, false)
            }
        }
        Timber.i("Applied runtime permissions for Java %d", feature)
    }

    /**
     * The environment the hosted VM needs before it starts.
     *
     * `JAVA_HOME` is read by the JDK's own bootstrap, and `LD_LIBRARY_PATH` lets the runtime's
     * libraries resolve each other — the natives directory comes first so that our GLFW shim and
     * translation layer win over anything of the same name inside the runtime.
     *
     * The library directories come from [libraryDirectories] rather than being spelled out, because
     * 8 puts none of them where the modern releases do.
     */
    fun environmentFor(feature: Int, nativesDirectory: File): Map<String, String> {
        val root = runtimeRoot(feature)
        val libraryPath = (listOf(nativesDirectory) + libraryDirectories(root))
            .filter(File::isDirectory)
            .joinToString(":") { it.absolutePath }

        return mapOf(
            "JAVA_HOME" to root.absolutePath,
            "LD_LIBRARY_PATH" to libraryPath,
            // bionic has no locale database, so anything reading these finds a sane default rather
            // than falling back to ASCII and mangling non-Latin text.
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
        )
    }

    /**
     * The system properties that point LWJGL at our shims instead of the desktop libraries it would
     * normally unpack from its own jars.
     *
     * This is the whole reason no LWJGL patching is needed: its `opengl` and `glfw` modules are pure
     * Java that `dlopen`s whatever these name.
     */
    fun lwjglProperties(
        nativesDirectory: File,
        shimDirectory: File,
    ): List<String> = buildList {
        // LWJGL unpacks its own bundled natives unless told where ours already are. Emitted here and
        // nowhere else: the argument builder used to add it too, and two of them in one command line
        // meant the effective value was whichever the VM happened to parse last.
        add("-Dorg.lwjgl.librarypath=${nativesDirectory.absolutePath}")
        // Named where the APK unpacked them, which is where the Activity loaded the GLFW shim from.
        // The linker treats two paths to the same library as two libraries, and the shim's window
        // state has to be the one the Activity is feeding the surface into.
        add("-Dorg.lwjgl.glfw.libname=${File(shimDirectory, "liblodestone_glfw.so").absolutePath}")
        // `org.lwjgl.opengl.libname` is deliberately not set here. Which translation layer serves
        // the game is only decided in the game process, once one of them has actually come up, so
        // the Activity appends it for whichever won.
        // jemalloc is not cross-compiled: LWJGL falls back to the platform allocator, and bionic's
        // is a scudo/jemalloc hybrid already.
        add("-Dorg.lwjgl.system.allocator=system")
        // Vulkan reaches the driver through our own loader rather than Android's directly. It
        // forwards everything and answers one question differently: a surface's rotation, which
        // Minecraft passes straight into its swapchain the way a desktop renderer can, and which on
        // a landscape activity would otherwise present every frame turned on its side.
        //
        // Named by path, and only when it is packaged. Falling back to `libvulkan.so` rather than
        // to LWJGL's own default matters: LWJGL looks for the versioned `libvulkan.so.1` a Linux
        // distribution ships, finds nothing on Android, and reports the backend as unavailable —
        // which the game accepts quietly by falling back to OpenGL.
        val vulkanLoader = File(shimDirectory, VULKAN_LOADER).takeIf(File::isFile)
        add("-Dorg.lwjgl.vulkan.libname=${vulkanLoader?.absolutePath ?: "libvulkan.so"}")
        // The two shader libraries the Vulkan backend compiles through, named where they were
        // installed. Both keep the file names their own builds produce rather than the ones LWJGL
        // looks for, so they have to be pointed at rather than found. Emitted for every launch:
        // a version with no Vulkan renderer never loads them, and the properties are inert.
        add("-Dorg.lwjgl.shaderc.libname=${File(nativesDirectory, LwjglNativeSet.SHADERC).absolutePath}")
        add("-Dorg.lwjgl.spvc.libname=${File(nativesDirectory, LwjglNativeSet.SPVC).absolutePath}")
        // Checks are deliberately left on. Disabling them removes the guard LWJGL puts in front of
        // every function pointer, and against a shim standing in for GLFW that guard is the
        // difference between a named "function not supported" exception and a jump to address
        // zero. That is not hypothetical: the IME entry points, which LWJGL resolves optionally
        // and so leaves null when absent, took the render thread down with a two-frame tombstone
        // that named no function at all. The per-call cost is a null test next to a driver call.
    }

    /**
     * The renderers [renderer] asks for that are actually packaged, best first.
     *
     * Resolved against the APK's native library directory rather than the version's natives, so
     * that packaging a layer is enough to make it selectable. Being packaged is only the first
     * hurdle: whether a renderer *works* is settled later, when the shim tries to bring its EGL up
     * on this device's driver. A Vulkan launch chains to nothing and lands here as an empty list.
     */
    /**
     * The OpenGL library LWJGL loads while bootstrapping, whichever backend the game will drive.
     *
     * Minecraft loads its native libraries as one unconditional list, OpenGL included, and a
     * library that will not load throws a `ReportedException` that ends the launch. On the Vulkan
     * path nothing renders through this — the game never makes a GL call — but `GL.create` still
     * has to find something, and left to itself it looks for the `libGL.so.1` a Linux distribution
     * ships and Android does not have.
     *
     * gl4es answers rather than the system `libGLESv2.so`, because it is this project's OpenGL: if
     * anything ever does reach a GL entry point here, it should reach the implementation the
     * OpenGL path would have used, not one with different semantics behind the same names.
     */
    fun openglLibrary(shimDirectory: File): File? =
        File(shimDirectory, Renderer.GL4ES.libraryName!!).takeIf(File::isFile)

    fun rendererCandidates(shimDirectory: File, renderer: Renderer): List<RendererCandidate> =
        renderer.chain.mapNotNull { candidate ->
            val layer = candidate.libraryName?.let { File(shimDirectory, it) } ?: return@mapNotNull null
            if (!layer.isFile) {
                return@mapNotNull null
            }
            RendererCandidate(
                renderer = candidate,
                layer = layer,
                eglLibrary = candidate.eglLibraryName
                    ?.let { File(shimDirectory, it) }
                    ?.takeIf(File::isFile),
            )
        }

    /**
     * Every directory of the runtime's own shared libraries, across both layouts.
     *
     * The same split [libjvm] probes: modern releases keep them under `lib/`, 8 under
     * `jre/lib/<arch>/`. Listing both rather than picking one keeps this from having to know which
     * feature release it was handed, and the caller drops the paths that do not exist.
     */
    private fun libraryDirectories(root: File): List<File> {
        val arch = abiDirectory()
        return listOf(
            "lib",
            "lib/server",
            "lib/jli",
            "jre/lib/$arch",
            "jre/lib/$arch/server",
            "jre/lib/$arch/jli",
        ).map { File(root, it) }
    }

    private companion object {
        /** Android's Vulkan loader, wrapped so that surface rotation is normalised away. */
        const val VULKAN_LOADER = "liblodestone_vulkan.so"
    }

    private fun abiDirectory(): String =
        when (val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "amd64"
            else -> abi ?: "aarch64"
        }
}
