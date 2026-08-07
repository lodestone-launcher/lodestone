package com.github.lodestone.common.io

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-1 helpers. Mojang publishes SHA-1 for every file it serves, so that is what we verify. */
object Hashing {

    private const val BUFFER_BYTES = 64 * 1024
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun sha1Digest(): MessageDigest = MessageDigest.getInstance("SHA-1")

    fun toHex(bytes: ByteArray): String {
        val hex = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(hex)
    }

    fun sha1(file: File): String = file.inputStream().use(::sha1)

    fun sha1(stream: InputStream): String {
        val digest = sha1Digest()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    /** Case-insensitive, because mod-loader manifests are inconsistent about hex casing. */
    fun matches(expected: String?, actual: String): Boolean =
        expected != null && expected.equals(actual, ignoreCase = true)
}
