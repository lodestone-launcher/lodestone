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
    VULKAN("vulkan", "Native Vulkan"),
}
