package com.github.lodestone.data.local.files

import com.github.lodestone.domain.model.version.LwjglNativeSet
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * Opens one library out of a packaged LWJGL set, or null when this build does not carry it.
 *
 * An interface rather than a path, because the sets are packaged as assets: they are directories in
 * the APK, not files on disk, and only the ABI the device actually runs is ever opened.
 */
fun interface LwjglNativesSource {
    fun open(set: LwjglNativeSet, name: String): InputStream?
}

/**
 * Fills a version's natives directory with the libraries Lodestone cross-built.
 *
 * Mojang's manifests carry Linux natives for x86-64 only, so what the version installer unpacked
 * cannot be loaded here at all and has to be written over. The replacements are copied rather than
 * linked: the APK's library directory and app storage are frequently different filesystems, so a
 * hard link cannot be relied on.
 *
 * The shims are pointedly *not* copied here. `liblodestone_glfw.so` is already loaded by the
 * Activity to receive the surface, and the linker keys a mapping on its path: a second copy under a
 * second path would be a second library, with its own window state, so the surface would arrive at
 * one and EGL would run in the other.
 */
object LwjglNativesInstaller {

    /**
     * Records which set the directory holds.
     *
     * A version's natives survive across launches and across app updates, so the set that was
     * installed has to be written down. Comparing file sizes instead would work only as long as no
     * two builds of the same library ever agreed on a length.
     */
    private const val MARKER = ".lwjgl"

    /**
     * Libraries that are not generated from the bindings, so the same build serves every set. They
     * stay in the APK's library directory and are installed straight from there.
     */
    private val SHARED = listOf("libfreetype.so", "libopenal.so")

    /** The one library of a set the LWJGL 2 layer needs a different build of. */
    private const val OPENGL = "liblwjgl_opengl.so"

    /**
     * @param compat2 installs the OpenGL bindings the LWJGL 2 compatibility layer needs in place of
     *   the stock ones. The two export different JNI symbol names, so a directory holding one is no
     *   use to the other and the marker records which it is.
     */
    fun install(
        set: LwjglNativeSet,
        source: LwjglNativesSource,
        nativeLibraryDir: File,
        target: File,
        compat2: Boolean = false,
    ) {
        target.mkdirs()
        installSet(set, source, target, compat2)
        installShared(nativeLibraryDir, target)
    }

    private fun installSet(
        set: LwjglNativeSet,
        source: LwjglNativesSource,
        target: File,
        compat2: Boolean,
    ) {
        val stamp = if (compat2) "${set.version}+lwjgl2" else set.version
        val marker = File(target, MARKER)
        if (marker.isFile && runCatching(marker::readText).getOrNull() == stamp) {
            return
        }
        // Stamped only once every library has landed, so a copy interrupted halfway is redone on
        // the next launch rather than left half-applied behind a marker that claims otherwise.
        marker.delete()
        for (name in set.libraries) {
            val asset = if (compat2 && name == OPENGL) LwjglNativeSet.COMPAT2_OPENGL else name
            val opened = source.open(set, asset)
            if (opened == null) {
                Timber.e("This build packages no %s for LWJGL %s", asset, set.version)
                return
            }
            val destination = File(target, name)
            val copied = runCatching {
                opened.use { input -> destination.outputStream().use(input::copyTo) }
            }.onFailure { Timber.e(it, "Could not install %s", name) }.isSuccess
            if (!copied) {
                return
            }
            destination.setReadable(true, false)
            destination.setExecutable(true, false)
        }
        marker.writeText(stamp)
        Timber.i("Installed the LWJGL %s natives into %s", stamp, target)
    }

    /**
     * A failure here is deliberately not fatal — the launch proceeds and reports a missing library,
     * which is easier to diagnose than a launcher that refuses to start.
     */
    private fun installShared(from: File, to: File) {
        for (name in SHARED) {
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
