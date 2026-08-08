package com.github.lodestone.data.local.files

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Unpacks a gzipped tar archive.
 *
 * Android ships no tar reader and the app carries no compression library, so this reads the format
 * directly. It covers what GNU tar actually emits for a JDK image: ustar headers, the GNU long-name
 * extension, pax extended records, and the symlinks and hard links a runtime image contains.
 */
object TarGzExtractor {

    private const val BLOCK_BYTES = 512
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Extracts [archive] into [target], dropping [stripComponents] leading path segments.
     *
     * The runtime tarballs wrap the whole image in a single `jdk/` directory, which would otherwise
     * become a level of nesting that nothing else in the launcher expects; stripping it is the same
     * thing `tar --strip-components=1` does.
     *
     * @return the number of entries written.
     */
    fun extract(archive: File, target: File, stripComponents: Int = 0): Int =
        archive.inputStream().buffered(BUFFER_BYTES).use { raw ->
            GZIPInputStream(raw, BUFFER_BYTES).use { stream ->
                extractStream(stream, target, stripComponents)
            }
        }

    private fun extractStream(stream: InputStream, target: File, stripComponents: Int): Int {
        target.mkdirs()
        // Resolved once, because every entry's destination is checked against it and a relative
        // `target` would otherwise be re-resolved against a working directory that can change.
        val root = target.canonicalFile
        val header = ByteArray(BLOCK_BYTES)
        val buffer = ByteArray(BUFFER_BYTES)

        var written = 0
        var pendingName: String? = null
        var pendingLink: String? = null
        var emptyBlocks = 0

        while (true) {
            if (!stream.readFully(header)) {
                break
            }
            if (header.all { it.toInt() == 0 }) {
                // Two zero blocks mark the end of the archive; a single one is padding some writers
                // emit mid-stream, so it is not enough to stop on.
                if (++emptyBlocks >= 2) break else continue
            }
            emptyBlocks = 0

            val size = header.readOctal(124, 12)
            // Pre-POSIX writers leave the typeflag NUL and GNU marks a contiguous file '7'; both
            // mean an ordinary file, and normalising here keeps the rest of the loop to one case.
            val type = when (val flag = header[156].toInt().toChar()) {
                '\u0000', ' ', '7' -> '0'
                else -> flag
            }
            val padding = (BLOCK_BYTES - (size % BLOCK_BYTES).toInt()) % BLOCK_BYTES

            when (type) {
                // GNU stores an over-long name as the body of a preceding entry.
                'L' -> {
                    pendingName = stream.readBody(size).toNullTerminatedString()
                    stream.skipFully(padding.toLong())
                    continue
                }

                'K' -> {
                    pendingLink = stream.readBody(size).toNullTerminatedString()
                    stream.skipFully(padding.toLong())
                    continue
                }

                // pax records. Global ones ('g') are archive-wide defaults that nothing here needs,
                // so only the per-entry form is read.
                'x', 'g' -> {
                    val records = parsePax(stream.readBody(size))
                    if (type == 'x') {
                        records["path"]?.let { pendingName = it }
                        records["linkpath"]?.let { pendingLink = it }
                    }
                    stream.skipFully(padding.toLong())
                    continue
                }
            }

            val name = pendingName ?: header.readName()
            val linkName = pendingLink ?: header.readString(157, 100)
            pendingName = null
            pendingLink = null

            val relative = strip(name, stripComponents)
            val destination = relative?.let { resolve(root, it) }
            if (destination == null) {
                stream.skipFully(size + padding)
                continue
            }

            when (type) {
                '5' -> destination.mkdirs()

                '0' -> {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output ->
                        var remaining = size
                        while (remaining > 0) {
                            val read = stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (read < 0) throw IOException("Truncated archive inside $name")
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    written++
                }

                '2' -> {
                    destination.parentFile?.mkdirs()
                    destination.delete()
                    runCatching { Files.createSymbolicLink(destination.toPath(), File(linkName).toPath()) }
                        .onFailure { Timber.w(it, "Could not link %s -> %s", name, linkName) }
                    written++
                }

                '1' -> {
                    val source = strip(linkName, stripComponents)?.let { resolve(root, it) }
                    if (source != null && source.isFile) {
                        destination.parentFile?.mkdirs()
                        destination.delete()
                        // A hard link across the same directory always works, but the filesystem is
                        // free to refuse one; a copy is indistinguishable to everything downstream.
                        runCatching { Files.createLink(destination.toPath(), source.toPath()) }
                            .onFailure { source.copyTo(destination, overwrite = true) }
                        written++
                    }
                }

                // Character and block devices, FIFOs and sockets. A JDK image contains none, and
                // an app has no way to create them anyway.
                else -> Timber.d("Skipping %s: unsupported tar entry type '%s'", name, type)
            }
            stream.skipFully(if (type == '0') padding.toLong() else size + padding)
        }
        return written
    }

    /**
     * The entry path with [count] leading segments removed, or null if it should not be written.
     *
     * An absolute path or one that climbs out with `..` is dropped rather than sanitised: nothing
     * we publish contains either, so their presence means the archive is not what we think it is.
     */
    private fun strip(name: String, count: Int): String? {
        val segments = name.trim('/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.size <= count || segments.any { it == ".." } || name.startsWith('/')) {
            return null
        }
        return segments.drop(count).joinToString("/")
    }

    private fun resolve(root: File, relative: String): File? {
        val destination = File(root, relative)
        val path = destination.canonicalPath
        return if (path == root.path || path.startsWith(root.path + File.separator)) {
            destination
        } else {
            Timber.w("Refusing to write %s outside the extraction directory", relative)
            null
        }
    }

    private fun parsePax(body: ByteArray): Map<String, String> =
        String(body, StandardCharsets.UTF_8).lineSequence()
            .mapNotNull { line ->
                // Each record is "<length> <key>=<value>", where the length covers the whole record
                // including its own digits — irrelevant here, because the newline already delimits.
                val keyValue = line.substringAfter(' ', "").takeIf { it.contains('=') } ?: return@mapNotNull null
                keyValue.substringBefore('=') to keyValue.substringAfter('=')
            }
            .toMap()

    /** ustar splits a long name across `prefix` and `name`, joined with a slash. */
    private fun ByteArray.readName(): String {
        val name = readString(0, 100)
        val prefix = if (readString(257, 6).startsWith("ustar")) readString(345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun ByteArray.readString(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end].toInt() != 0) {
            end++
        }
        return String(this, offset, end - offset, StandardCharsets.UTF_8)
    }

    /**
     * Reads a numeric header field.
     *
     * GNU switches to base-256 with the high bit of the first byte set for values that do not fit
     * the octal field. No JDK file is anywhere near 8 GiB, but a header that uses it would otherwise
     * be read as a nonsense size and desynchronise the whole stream.
     */
    private fun ByteArray.readOctal(offset: Int, length: Int): Long {
        if (this[offset].toInt() and 0x80 != 0) {
            var value = (this[offset].toInt() and 0x7F).toLong()
            for (index in offset + 1 until offset + length) {
                value = (value shl 8) or (this[index].toLong() and 0xFF)
            }
            return value
        }
        val text = readString(offset, length).trim()
        return if (text.isEmpty()) 0L else text.toLongOrNull(radix = 8) ?: 0L
    }

    private fun ByteArray.toNullTerminatedString(): String =
        String(this, 0, indexOf(0).takeIf { it >= 0 } ?: size, StandardCharsets.UTF_8)

    private fun InputStream.readBody(size: Long): ByteArray {
        val body = ByteArray(size.toInt())
        if (!readFully(body)) {
            throw IOException("Truncated archive: expected $size bytes of metadata")
        }
        return body
    }

    /** Fills [into] completely. Returns false only at a clean end of stream. */
    private fun InputStream.readFully(into: ByteArray): Boolean {
        var offset = 0
        while (offset < into.size) {
            val read = read(into, offset, into.size - offset)
            if (read < 0) {
                return offset == 0
            }
            offset += read
        }
        return true
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                // GZIPInputStream returns 0 from skip at a member boundary rather than at the end
                // of the stream, so a read is the only way to tell the two apart.
                if (read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
