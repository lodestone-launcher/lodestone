package com.github.lodestone.data.local.files

import com.github.lodestone.domain.model.version.LwjglNativeSet
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * The packaged LWJGL sets, as the installer reads them.
 *
 * An interface rather than a path, because the sets are packaged as assets: they are directories in
 * the APK, not files on disk, and only the ABI the device actually runs is ever opened.
 */
interface LwjglNativesSource {

    /** Opens one library out of a packaged set, or null when this build does not carry it. */
    fun open(set: LwjglNativeSet, name: String): InputStream?

    /**
     * Changes whenever the packaged libraries might have.
     *
     * A version's natives are copied out of the APK once and then survive every later launch, so
     * something has to say that the copies are stale. The set's own version cannot: these
     * libraries are ours rather than LWJGL's, and a rebuild that fixes one of them — a shaderc
     * built against a release that actually exports what the bindings resolve, say — is still
     * LWJGL 3.4.1. Without this, such a fix would ship in an update and never reach the directory
     * it was meant to fix.
     */
    val revision: String
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
     * Records what the directory holds.
     *
     * A version's natives survive across launches and across app updates, so what was installed has
     * to be written down. Comparing file sizes instead would work only as long as no two builds of
     * the same library ever agreed on a length.
     *
     * The marker names every library and the build they came out of, not just the release. Both
     * have already been needed. A set gains libraries between app versions — 3.4.1 grew the three
     * the Vulkan backend needs — and a set's libraries get rebuilt without its version moving,
     * because these are our builds rather than LWJGL's. A marker recording only "3.4.1" calls a
     * directory installed before either change current, and what that looks like on the way out is
     * a launch that dies on a library the launcher believes it wrote.
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
        val stamp = stampFor(set, source, compat2)
        val marker = File(target, MARKER)
        // The marker is believed only as far as the files it names. Mojang's own natives jars
        // unpack x86-64 libraries into this same directory, so a name being present says nothing
        // about whose build it is — but a name being absent is proof the install did not finish.
        if (marker.isFile &&
            runCatching(marker::readText).getOrNull() == stamp &&
            set.libraries.all { File(target, it).isFile }
        ) {
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
        Timber.i("Installed the LWJGL %s natives into %s", set.version, target)
    }

    /**
     * What a finished install of [set] looks like, as the marker records it.
     *
     * The library names are part of it rather than only the release, so that adding one to a set
     * invalidates every directory installed before it. Written one per line because this file is
     * read by a person far more often than by the code — it is the first thing worth looking at
     * when a launch cannot find a library.
     */
    private fun stampFor(
        set: LwjglNativeSet,
        source: LwjglNativesSource,
        compat2: Boolean,
    ): String {
        val version = if (compat2) "${set.version}+lwjgl2" else set.version
        return (listOf(version, source.revision) + set.libraries.sorted()).joinToString("\n")
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
