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
    /**
     * The renderers to try, best first.
     *
     * A list rather than one choice because whether a renderer works is only knowable once its EGL
     * has actually come up on this device's driver, which is far too late to go back and pick again.
     */
    val renderers: List<RendererCandidate>,
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

/** One renderer the shim may bring up, with the libraries it needs to do so. */
data class RendererCandidate(
    val renderer: Renderer,
    /** The desktop-GL translation layer to open. */
    val layer: File,
    /** The EGL the layer is driven through, or null for Android's. */
    val eglLibrary: File?,
)

/**
 * How OpenGL calls reach the device.
 *
 * Minecraft 1.17 and later ask for desktop OpenGL 3.2 core, which Android does not have; something
 * has to stand in for it. Modern versions also carry a Vulkan renderer, which Android supports
 * natively and which is the fastest path when the version offers it.
 */
enum class Renderer(val id: String, val label: String, val description: String) {
    /** Try each translation layer in turn and keep the first that comes up. */
    AUTO(
        id = "auto",
        label = "Automatic",
        description = "Zink where the driver supports it, OpenGL ES translation otherwise.",
    ),

    /** Translate desktop GL onto Vulkan via Mesa's Zink. */
    ZINK(
        id = "zink",
        label = "Zink",
        description = "Desktop OpenGL 4.6 on Vulkan. Needed for 1.17 and later; not every GPU can run it.",
    ),

    /** Translate desktop GL onto OpenGL ES. */
    GL4ES(
        id = "gl4es",
        label = "OpenGL ES translation",
        description = "Desktop OpenGL 2.1 on the device's own GL ES driver. Works nearly everywhere, but only suits 1.16 and earlier.",
    ),

    /** Hand the game's own Vulkan renderer straight to the device driver. */
    VULKAN(
        id = "vulkan",
        label = "Native Vulkan",
        description = "The game's own Vulkan renderer, with no translation. Not wired up yet.",
    );

    /**
     * The renderers to try, best first.
     *
     * Only [AUTO] chains, and it prefers Zink because gl4es reports OpenGL 2.1 and cannot serve the
     * 3.2 core profile 1.17 and later ask for. An explicit choice is honoured exactly: someone who
     * has picked a renderer to see how it behaves is not helped by quietly being given another one.
     */
    val chain: List<Renderer>
        get() = when (this) {
            AUTO -> listOf(ZINK, GL4ES)
            else -> listOf(this)
        }

    /** The translation layer library, or null when the game talks to the driver itself. */
    val libraryName: String?
        get() = when (this) {
            ZINK -> ZINK_LIBRARY
            GL4ES -> GL4ES_LIBRARY
            AUTO, VULKAN -> null
        }

    /**
     * The EGL implementation the layer must be driven through, or null for Android's.
     *
     * Zink's GL entry points only work on a context Mesa's own EGL created. gl4es forwards to the
     * device's GL ES driver and wants Android's, which the shim is linked against already.
     */
    val eglLibraryName: String?
        get() = if (this == ZINK) ZINK_EGL_LIBRARY else null

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
