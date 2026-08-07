package com.github.lodestone.data.local.files

import com.github.lodestone.domain.model.version.ExtractRule
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * Unpacks the shared objects out of a version's native jars.
 *
 * The JVM loads these through `java.library.path`, so they have to exist as ordinary files in one
 * flat directory. On Android that directory lives inside app-private storage, which is the only
 * place the linker will open a `.so` from.
 */
object NativesExtractor {

    /**
     * Extracts [jars] into [target], flattening the archive structure.
     *
     * Native jars nest their payload under a platform directory (`linux/arm64/libfoo.so` in LWJGL 3,
     * or the bare name in LWJGL 2), but `java.library.path` is not recursive, so everything is
     * written to the top level.
     */
    fun extract(jars: List<NativeJar>, target: File) {
        target.mkdirs()

        for (native in jars) {
            if (!native.file.isFile) {
                Timber.w("Skipping missing native jar %s", native.file.name)
                continue
            }
            runCatching { extractOne(native, target) }
                .onFailure { Timber.e(it, "Could not unpack %s", native.file.name) }
        }
    }

    private fun extractOne(native: NativeJar, target: File) {
        ZipFile(native.file).use { archive ->
            for (entry in archive.entries()) {
                if (entry.isDirectory) {
                    continue
                }
                val name = entry.name
                if (native.extract?.excludes(name) == true || isMetadata(name)) {
                    continue
                }
                if (!isLoadable(name)) {
                    continue
                }

                // Flattened on purpose, and named only by its basename, which also neutralises any
                // `../` an archive might carry.
                val destination = File(target, File(name).name)
                // Later jars must not silently replace an earlier one's library; first writer wins,
                // matching the classpath order the version manifest asked for.
                if (destination.exists()) {
                    continue
                }
                archive.getInputStream(entry).use { input ->
                    destination.outputStream().use(input::copyTo)
                }
                destination.setReadable(true, false)
                destination.setExecutable(true, false)
            }
        }
    }

    private fun isMetadata(name: String): Boolean =
        name.startsWith("META-INF/") || name.startsWith("module-info")

    /**
     * Only shared objects are worth unpacking. Native jars also carry the odd `.dll`, `.dylib` or
     * licence file, and copying those wastes space in app storage for no benefit.
     */
    private fun isLoadable(name: String): Boolean =
        name.endsWith(".so") || name.contains(".so.")

    data class NativeJar(
        val file: File,
        val extract: ExtractRule?,
    )
}
