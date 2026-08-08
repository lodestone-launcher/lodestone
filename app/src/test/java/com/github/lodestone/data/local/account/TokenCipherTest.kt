package com.github.lodestone.data.local.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Exercises the ciphertext format against a plain AES key.
 *
 * The Android key store cannot be reached from a JVM test, so what is verified here is everything
 * except where the key comes from: the framing, the round trip, and that a modified byte is
 * refused rather than decrypted into something plausible.
 */
class TokenCipherTest {

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = TokenCipher { key }

    @Test
    fun `round trips a token`() {
        val token = "M.C5xx.-Ceci-nest-pas-un-vrai-jeton".toByteArray()

        assertArrayEquals(token, cipher.decrypt(cipher.encrypt(token)))
    }

    @Test
    fun `does not leave the plaintext in the ciphertext`() {
        val token = "M.C5xx.-Ceci-nest-pas-un-vrai-jeton"

        val encrypted = cipher.encrypt(token.toByteArray())

        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(token))
    }

    @Test
    fun `uses a fresh nonce for every message`() {
        val token = "the same token twice".toByteArray()

        val first = cipher.encrypt(token)
        val second = cipher.encrypt(token)

        // Identical output would mean a reused IV, which under GCM leaks the XOR of the plaintexts
        // and, worse, the authentication key.
        assertNotEquals(String(first, Charsets.ISO_8859_1), String(second, Charsets.ISO_8859_1))
    }

    @Test
    fun `refuses a tampered message`() {
        val encrypted = cipher.encrypt("a token".toByteArray())
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1] + 1).toByte()

        assertThrows(Exception::class.java) { cipher.decrypt(encrypted) }
    }

    @Test
    fun `refuses a message written under another key`() {
        val encrypted = cipher.encrypt("a token".toByteArray())
        val other = TokenCipher { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }

        assertThrows(Exception::class.java) { other.decrypt(encrypted) }
    }

    @Test
    fun `refuses a truncated message`() {
        val encrypted = cipher.encrypt("a token".toByteArray())

        assertThrows(Exception::class.java) { cipher.decrypt(encrypted.copyOf(3)) }
        assertThrows(Exception::class.java) { cipher.decrypt(ByteArray(0)) }
    }
}
