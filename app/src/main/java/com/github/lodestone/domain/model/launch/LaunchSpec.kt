package com.github.lodestone.domain.model.launch

import java.io.File

/**
 * A fully resolved command line, ready to hand to the JVM bridge.
 *
 * Everything here is concrete: no placeholders remain, every path exists, and the classpath is in
 * the order the version manifest asked for.
 */
data class LaunchSpec(
    val versionId: String,
    val mainClass: String,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    /** The runtime's `libjvm.so`, which the bridge opens to create the VM. */
    val libjvm: File,
    val javaHome: File,
    val gameDirectory: File,
    val nativesDirectory: File,
    /** The desktop-GL translation layer to open, or null when the game talks to the driver. */
    val translationLayer: File?,
    /** The EGL the layer is driven through, or null for Android's. */
    val eglLibrary: File?,
    /** Directories appended to `LD_LIBRARY_PATH` before the VM starts. */
    val libraryPath: List<File>,
    val environment: Map<String, String>,
) {
    /** The command an equivalent desktop launch would run, for the log and for bug reports. */
    fun describe(): String = buildString {
        append(File(javaHome, "bin/java").absolutePath)
        jvmArgs.forEach { append(' ').append(it) }
        append(' ').append(mainClass)
        gameArgs.forEach { append(' ').append(it) }
    }
}

/** Tuning the user controls, applied on top of what the version manifest asks for. */
data class LaunchOptions(
    /** Maximum heap in mebibytes. */
    val maxMemoryMb: Int = DEFAULT_MAX_MEMORY_MB,
    val minMemoryMb: Int = DEFAULT_MIN_MEMORY_MB,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val extraJvmArgs: List<String> = emptyList(),
    val demo: Boolean = false,
    val renderer: Renderer = Renderer.AUTO,
    /** Turns on HotSpot's own startup logging, for diagnosing a VM that will not come up. */
    val verboseVmStartup: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_MEMORY_MB = 1024
        const val DEFAULT_MIN_MEMORY_MB = 128
    }
}

/**
 * How OpenGL calls reach the device.
 *
 * Minecraft 1.17 and later ask for desktop OpenGL 3.2 core, which Android does not have; something
 * has to stand in for it. Modern versions also carry a Vulkan renderer, which Android supports
 * natively and which is the fastest path when the version offers it.
 */
enum class Renderer(val id: String, val label: String) {
    /** Pick per version: Vulkan where the manifest ships it, translation otherwise. */
    AUTO("auto", "Automatic"),

    /** Translate desktop GL onto OpenGL ES. */
    GL4ES("gl4es", "OpenGL ES translation"),

    /** Translate desktop GL onto Vulkan via Mesa's Zink. */
    ZINK("zink", "Zink (Vulkan)"),

    /** Hand the game's own Vulkan renderer straight to the device driver. */
    VULKAN("vulkan", "Native Vulkan");

    /**
     * The translation layer libraries this renderer will accept, best first.
     *
     * [AUTO] lists both so that whichever is actually packaged wins, with Zink preferred: gl4es
     * reports OpenGL 2.1 and cannot serve the 3.2 core profile 1.17 and later ask for.
     */
    val libraryNames: List<String>
        get() = when (this) {
            AUTO -> listOf(ZINK_LIBRARY, GL4ES_LIBRARY)
            ZINK -> listOf(ZINK_LIBRARY)
            GL4ES -> listOf(GL4ES_LIBRARY)
            VULKAN -> emptyList()
        }

    /**
     * The EGL implementation the layer named by [libraryNames] must be driven through.
     *
     * Zink's GL entry points only work on a context Mesa's own EGL created, so it cannot use
     * Android's. gl4es forwards to the device's GLES driver and needs Android's EGL, which is what
     * the shim uses when this is null.
     */
    fun eglLibraryFor(layerName: String): String? =
        if (layerName == ZINK_LIBRARY) ZINK_EGL_LIBRARY else null

    private companion object {
        /** Mesa builds Zink into a plain `libGL.so`, beside the `libgallium_dri.so` it loads. */
        const val ZINK_LIBRARY = "libGL.so"

        /**
         * Mesa's EGL, staged under a name of its own.
         *
         * Its SONAME is still `libEGL.so`, and packaged under that name it would sit ahead of
         * Android's on the app's library search path — so anything resolving the system EGL by
         * `DT_NEEDED`, this shim included, would silently get Mesa's instead.
         */
        const val ZINK_EGL_LIBRARY = "libEGL_zink.so"
        const val GL4ES_LIBRARY = "libgl4es.so"
    }
}
