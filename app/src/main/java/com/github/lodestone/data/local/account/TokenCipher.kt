package com.github.lodestone.data.local.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the account store with AES-GCM.
 *
 * A Microsoft refresh token is a long-lived credential: anyone holding one can mint fresh game
 * sessions for that account until the user revokes it, which almost nobody ever does. App-private
 * storage already keeps it away from other apps, but not from a rooted device, an ADB backup or an
 * offline image of the data partition, so the file on disk is ciphertext.
 *
 * The key is produced by [androidKeystore], which keeps it inside the platform key store where the
 * app can use it but never read it. Deliberately not bound to user authentication: the launcher
 * refreshes tokens while starting the game, and a key that needs a fresh unlock would turn that
 * into a lock-screen prompt in the middle of a launch.
 *
 * The key provider is a parameter rather than a hardcoded lookup so the format can be exercised
 * off-device, where there is no key store to talk to.
 */
class TokenCipher(private val key: () -> SecretKey) {

    /** Returns `version ‖ ivLength ‖ iv ‖ ciphertext‖tag`. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        // The provider picks the IV. Asking it to is not laziness: reusing an IV under one GCM key
        // is a total break, and a provider-generated one cannot be got wrong here.
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext)
        return ByteArray(HEADER_SIZE + iv.size + body.size).also { out ->
            out[0] = FORMAT_VERSION
            out[1] = iv.size.toByte()
            iv.copyInto(out, HEADER_SIZE)
            body.copyInto(out, HEADER_SIZE + iv.size)
        }
    }

    /**
     * Reverses [encrypt].
     *
     * Throws for anything that is not intact ciphertext under the current key — a truncated file, a
     * tampered one, or one written under a key that has since been replaced.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > HEADER_SIZE) { "Ciphertext is too short to hold a header" }
        require(ciphertext[0] == FORMAT_VERSION) { "Unsupported ciphertext version" }
        val ivSize = ciphertext[1].toInt()
        require(ivSize in 1..MAX_IV_SIZE && ciphertext.size > HEADER_SIZE + ivSize) {
            "Ciphertext header is malformed"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_LENGTH_BITS, ciphertext, HEADER_SIZE, ivSize),
        )
        return cipher.doFinal(ciphertext, HEADER_SIZE + ivSize, ciphertext.size - HEADER_SIZE - ivSize)
    }

    companion object {
        /**
         * A cipher backed by a non-exportable key in the Android key store, created on first use.
         *
         * The key is generated once and then only referenced. If it disappears — the app's data is
         * cleared, or the store is reset by a factory reset or a restore onto another device — the
         * stored accounts become undecryptable and the user signs in again, which is the correct
         * outcome for a credential that was never meant to leave the device it was issued on.
         */
        fun androidKeystore(alias: String = KEY_ALIAS): TokenCipher = TokenCipher {
            val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKey(alias)
        }

        private fun generateKey(alias: String): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            return generator.generateKey()
        }

        const val TRANSFORMATION = "AES/GCM/NoPadding"

        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "lodestone.accounts"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_LENGTH_BITS = 128

        /** The format byte and the IV length byte that precede the IV. */
        private const val HEADER_SIZE = 2
        private const val MAX_IV_SIZE = 16
        private const val FORMAT_VERSION: Byte = 1
    }
}
