package com.github.lodestone.data.local.files

import timber.log.Timber
import java.io.File
import java.io.InputStream

/** Opens the packaged LWJGL 2 compatibility jar, or null when this build does not carry one. */
fun interface Lwjgl2CompatSource {
    fun open(): InputStream?
}

/**
 * Puts the LWJGL 2 compatibility layer where the game's classpath can name it.
 *
 * Packaged as an asset and installed into the libraries directory rather than launched from inside
 * the APK, so that it appears in a launch log as an ordinary Maven-shaped path and can be inspected
 * on device like any other library. The jar carries LWJGL 3's core, GLFW and relocated OpenGL
 * bindings alongside the LWJGL 2 API, because a pre-1.13 manifest names no LWJGL 3 coordinate at
 * all and there would otherwise be nothing to download them from.
 */
object Lwjgl2CompatInstaller {

    const val ASSET = "lwjgl2-compat.jar"

    /**
     * Returns the installed jar, or null when it could not be written.
     *
     * Reinstalled whenever the sizes differ, which is what carries a rebuilt layer onto a device
     * that already has an older one: the jar is app-versioned, not content-addressed.
     */
    fun install(source: Lwjgl2CompatSource, target: File): File? {
        val opened = source.open()
        if (opened == null) {
            Timber.e("This build packages no %s", ASSET)
            return null
        }
        return opened.use { input ->
            val bytes = runCatching { input.readBytes() }
                .onFailure { Timber.e(it, "Could not read %s", ASSET) }
                .getOrNull() ?: return null
            if (target.isFile && target.length() == bytes.size.toLong()) {
                return target
            }
            target.parentFile?.mkdirs()
            runCatching { target.writeBytes(bytes) }
                .onFailure { Timber.e(it, "Could not install %s", ASSET) }
                .map { target }
                .getOrNull()
        }
    }
}
