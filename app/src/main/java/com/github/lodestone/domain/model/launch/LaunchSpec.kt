package com.github.lodestone.domain.model.launch

import com.github.lodestone.domain.model.version.GraphicsBackend
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
    /**
     * Which backend the game itself will drive.
     *
     * Carried rather than inferred from [renderers] being empty, because the two mean different
     * things: an empty list on the OpenGL path means no layer was packaged, which is a failure,
     * and on the Vulkan path means there was never meant to be one.
     */
    val graphicsBackend: GraphicsBackend,
    /**
     * The library LWJGL's own OpenGL bootstrap loads, or null when this build packages none.
     *
     * Separate from [renderers] because it is not a rendering choice: the game loads its native
     * libraries as one list and fails the launch over any of them, so something has to answer for
     * OpenGL even on a launch that will never make a GL call.
     */
    val openglLibrary: File?,
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
)

/**
 * How OpenGL calls reach the device.
 *
 * Minecraft 1.17 and later ask for desktop OpenGL 3.2 core, which Android does not have; something
 * has to stand in for it. Modern versions also carry a Vulkan renderer, which Android supports
 * natively and which is the fastest path when the version offers it.
 */
enum class Renderer(val id: String, val label: String, val description: String) {
    /** Native Vulkan where the version can, the OpenGL translation layer where it cannot. */
    AUTO(
        id = "auto",
        label = "Automatic",
        description = "The game's own Vulkan renderer where the version has one, OpenGL translation otherwise.",
    ),

    /**
     * Hand the game's own Vulkan renderer straight to the device driver.
     *
     * Nothing is translated on this path. Minecraft 26.2 ships a Vulkan backend, Android exposes
     * Vulkan on every device this app installs on, and the shim's only job is to hand it a surface
     * for the activity's window. It is both the fastest path and the only one whose output is the
     * game's own rendering rather than our reinterpretation of it.
     */
    VULKAN(
        id = "vulkan",
        label = "Native Vulkan",
        description = "The game's own Vulkan renderer, with no translation. Needs a version that ships one.",
    ),

    /** Translate desktop GL onto OpenGL ES. */
    GL4ES(
        id = "gl4es",
        label = "OpenGL ES translation",
        description = "Desktop OpenGL 2.1 on the device's own GL ES driver. Works nearly everywhere, but only suits 1.16 and earlier.",
    );

    /** Whether this choice renders through the game's own Vulkan backend rather than a layer. */
    val isVulkan: Boolean get() = this == VULKAN

    /**
     * The translation layers to try, best first, for a launch that is not using Vulkan.
     *
     * Only [AUTO] chains, and on the OpenGL side there is one layer to chain to. An explicit choice
     * is honoured exactly: someone who has picked a renderer to see how it behaves is not helped by
     * quietly being given another one.
     */
    val chain: List<Renderer>
        get() = when (this) {
            AUTO -> listOf(GL4ES)
            VULKAN -> emptyList()
            else -> listOf(this)
        }

    /** The translation layer library, or null when the game talks to the driver itself. */
    val libraryName: String?
        get() = when (this) {
            GL4ES -> GL4ES_LIBRARY
            AUTO, VULKAN -> null
        }

    private companion object {
        const val GL4ES_LIBRARY = "libgl4es.so"
    }
}
