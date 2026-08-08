package com.github.lodestone.common.io

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The digest algorithms Lodestone verifies against.
 *
 * Mojang publishes SHA-1 for everything it serves and cannot be asked to publish anything else;
 * our own runtime archives are published with SHA-256, which is what a new artefact should use.
 */
enum class HashAlgorithm(val jcaName: String) {
    SHA1("SHA-1"),
    SHA256("SHA-256"),
}

/** A digest as some manifest published it, carried together with the algorithm that produced it. */
data class Checksum(val algorithm: HashAlgorithm, val value: String) {
    companion object {
        fun sha1(value: String?): Checksum? = value?.let { Checksum(HashAlgorithm.SHA1, it) }

        fun sha256(value: String?): Checksum? = value?.let { Checksum(HashAlgorithm.SHA256, it) }
    }
}

/** Digest helpers over files and streams. */
object Hashing {

    private const val BUFFER_BYTES = 64 * 1024
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun digest(algorithm: HashAlgorithm): MessageDigest =
        MessageDigest.getInstance(algorithm.jcaName)

    fun sha1Digest(): MessageDigest = digest(HashAlgorithm.SHA1)

    fun toHex(bytes: ByteArray): String {
        val hex = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(hex)
    }

    fun hash(file: File, algorithm: HashAlgorithm): String =
        file.inputStream().use { hash(it, algorithm) }

    fun hash(stream: InputStream, algorithm: HashAlgorithm): String {
        val digest = digest(algorithm)
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    fun sha1(file: File): String = hash(file, HashAlgorithm.SHA1)

    fun sha1(stream: InputStream): String = hash(stream, HashAlgorithm.SHA1)

    /** Case-insensitive, because mod-loader manifests are inconsistent about hex casing. */
    fun matches(expected: String?, actual: String): Boolean =
        expected != null && expected.equals(actual, ignoreCase = true)

    fun matches(expected: Checksum?, actual: String): Boolean = matches(expected?.value, actual)
}
