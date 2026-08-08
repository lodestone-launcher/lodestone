package com.github.lodestone.data.local.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.data.remote.microsoft.MicrosoftAuthApi
import com.github.lodestone.data.repository.AccountRepository
import com.github.lodestone.domain.model.account.MinecraftAccount
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The half of the account store that only exists on a device.
 *
 * The JVM tests cover the ciphertext format against an ordinary AES key; what they cannot reach is
 * the Android key store itself — that the key is created, that it is not exportable, that GCM
 * authentication is enforced by the platform provider, and that all of it survives the app's
 * process going away.
 */
@RunWith(AndroidJUnit4::class)
class AccountStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "lodestone.accounts.test"
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(context.filesDir, "accounts-test.bin")
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun roundTripsThroughTheKeyStore() {
        val cipher = TokenCipher.androidKeystore(alias)
        val token = "M.C5xx.-not-a-real-refresh-token".toByteArray()

        assertArrayEquals(token, cipher.decrypt(cipher.encrypt(token)))
    }

    @Test
    fun keepsTheKeyInsideTheKeyStore() {
        TokenCipher.androidKeystore(alias).encrypt("a token".toByteArray())

        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val key = (store.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey

        assertNotNull("the key was never created", key)
        // The point of the key store: the app can use the key but cannot read it, so neither can
        // anything that gets hold of the app's files.
        assertNull("the key material is readable, so it is not key store backed", key.encoded)
    }

    @Test
    fun refusesTamperedCiphertext() {
        val cipher = TokenCipher.androidKeystore(alias)
        val encrypted = cipher.encrypt("a token".toByteArray())
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1] + 1).toByte()

        // The GCM tag is checked by the platform provider here, not by our own code.
        val failure = runCatching { cipher.decrypt(encrypted) }.exceptionOrNull()
        assertNotNull("a tampered blob decrypted", failure)
    }

    @Test
    fun keepsAnAccountAcrossInstances() = runBlocking {
        val account = MinecraftAccount(
            uuid = "0".repeat(31) + "1",
            username = "Steve",
            accessToken = "an-access-token",
            expiresAt = System.currentTimeMillis() + 3_600_000,
            xuid = "2535000000000001",
            refreshToken = "a-refresh-token",
        )

        repository().add(account)
        val reopened = repository()
        reopened.load()

        assertEquals(listOf("Steve"), reopened.state.value.accounts.map { it.username })
        assertEquals("Steve", reopened.state.value.active?.username)
        assertEquals("a-refresh-token", reopened.state.value.active?.refreshToken)
        assertFalse(
            "the refresh token is readable on disk",
            String(file.readBytes(), Charsets.ISO_8859_1).contains("a-refresh-token"),
        )
    }

    @Test
    fun forgetsAnAccountThatSignsOut() = runBlocking {
        val repository = repository()
        repository.addOffline("Player")
        assertTrue(file.isFile)

        repository.remove(repository.state.value.accounts.single().uuid)

        assertNull(repository.state.value.active)
        assertFalse("signing out left the store on disk", file.exists())
    }

    private fun repository() = AccountRepository(
        store = AccountStore(file, TokenCipher.androidKeystore(alias), LodestoneJson),
        // Nothing here reaches the network: these accounts never expire, so no leg is ever called.
        auth = MicrosoftAuthApi(
            client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
            json = LodestoneJson,
        ),
    )

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
