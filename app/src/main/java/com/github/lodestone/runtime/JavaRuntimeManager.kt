package com.github.lodestone.runtime

import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.domain.model.launch.Renderer
import com.github.lodestone.domain.model.version.JavaVersionRequirement
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
     */
    fun environmentFor(feature: Int, nativesDirectory: File): Map<String, String> {
        val root = runtimeRoot(feature)
        val libraryPath = listOf(
            nativesDirectory,
            File(root, "lib"),
            File(root, "lib/server"),
            File(root, "lib/jli"),
        ).filter(File::isDirectory).joinToString(":") { it.absolutePath }

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
        translationLayer: File?,
    ): List<String> = buildList {
        add("-Dorg.lwjgl.librarypath=${nativesDirectory.absolutePath}")
        // Named where the APK unpacked them, which is where the Activity loaded the GLFW shim from.
        // The linker treats two paths to the same library as two libraries, and the shim's window
        // state has to be the one the Activity is feeding the surface into.
        add("-Dorg.lwjgl.glfw.libname=${File(shimDirectory, "liblodestone_glfw.so").absolutePath}")
        // The same path the Activity already opened, so LWJGL's dlopen finds the library loaded
        // rather than running its constructors a second time on the render thread.
        translationLayer?.let { add("-Dorg.lwjgl.opengl.libname=${it.absolutePath}") }
        // jemalloc is not cross-compiled: LWJGL falls back to the platform allocator, and bionic's
        // is a scudo/jemalloc hybrid already.
        add("-Dorg.lwjgl.system.allocator=system")
        // LWJGL probes for a debug console and stack traces it cannot get here.
        add("-Dorg.lwjgl.util.NoChecks=true")
    }

    /**
     * The translation layer [renderer] asks for, taking the first one actually packaged.
     *
     * Resolved against the APK's native library directory rather than the version's natives, so
     * that adding Zink's libraries to the build is enough to switch to it.
     */
    fun translationLayer(shimDirectory: File, renderer: Renderer): File? =
        renderer.libraryNames.map { File(shimDirectory, it) }.firstOrNull(File::isFile)

    /** The EGL [translationLayer] has to be driven through, or null for Android's. */
    fun eglLibrary(shimDirectory: File, renderer: Renderer, layer: File?): File? =
        layer?.let { renderer.eglLibraryFor(it.name) }
            ?.let { File(shimDirectory, it) }
            ?.takeIf(File::isFile)

    private fun abiDirectory(): String =
        when (val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "amd64"
            else -> abi ?: "aarch64"
        }
}
