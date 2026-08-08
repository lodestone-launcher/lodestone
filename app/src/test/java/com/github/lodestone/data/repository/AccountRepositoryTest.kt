package com.github.lodestone.data.repository

import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.data.local.account.AccountStore
import com.github.lodestone.data.local.account.TokenCipher
import com.github.lodestone.data.remote.microsoft.AuthFixtures
import com.github.lodestone.data.remote.microsoft.MicrosoftAuthApi
import com.github.lodestone.data.remote.microsoft.respondJson
import com.github.lodestone.domain.model.account.AccountType
import com.github.lodestone.domain.model.account.AuthenticationError
import com.github.lodestone.domain.model.account.MinecraftAccount
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/**
 * The account list as the launcher uses it: several accounts with one of them current, and a token
 * renewed on the way to a launch rather than after the game has already been refused a session.
 */
class AccountRepositoryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var clock = 1_700_000_000_000L
    private var tokenResponse: Pair<HttpStatusCode, String> = HttpStatusCode.OK to RENEWED_TOKEN
    private var renewals = 0
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(temporary.newFolder("files"), "accounts.bin")
    }

    @Test
    fun `keeps accounts across restarts`() = runTest {
        repository().add(account("Steve"))
        repository().add(account("Alex"))

        val reopened = repository()
        reopened.load()

        assertEquals(listOf("Steve", "Alex"), reopened.state.value.accounts.map { it.username })
        // Whoever signed in last is who a launch would use.
        assertEquals("Alex", reopened.state.value.active?.username)
    }

    @Test
    fun `signing in again as the same account replaces it`() = runTest {
        val repository = repository()
        repository.add(account("Steve"))
        repository.add(account("Steve").copy(accessToken = "a-newer-token"))

        assertEquals(1, repository.state.value.accounts.size)
        assertEquals("a-newer-token", repository.state.value.active?.accessToken)
    }

    @Test
    fun `switches between accounts`() = runTest {
        val repository = repository()
        repository.add(account("Steve"))
        repository.add(account("Alex"))

        repository.setActive(account("Steve").uuid)

        assertEquals("Steve", repository.state.value.active?.username)
    }

    @Test
    fun `signing out deletes the tokens rather than deselecting`() = runTest {
        val repository = repository()
        repository.add(account("Steve"))
        repository.add(account("Alex"))

        repository.remove(account("Alex").uuid)

        assertEquals(listOf("Steve"), repository.state.value.accounts.map { it.username })
        // Somebody else takes over rather than leaving the launcher with nothing selected.
        assertEquals("Steve", repository.state.value.active?.username)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("refresh-Alex"))

        repository.remove(account("Steve").uuid)

        assertNull(repository.state.value.active)
        assertFalse("the last sign-out left a store behind", file.exists())
    }

    @Test
    fun `launches with a token that is still good`() = runTest {
        val repository = repository()
        repository.add(account("Steve", expiresAt = clock + HOUR))

        assertEquals("access-Steve", repository.activeForLaunch()?.accessToken)
        assertEquals(0, renewals)
    }

    @Test
    fun `renews a token that has expired`() = runTest {
        val repository = repository()
        repository.add(account("Steve", expiresAt = clock - 1))

        val launched = repository.activeForLaunch()

        assertEquals(1, renewals)
        assertEquals("minecraft-access-token", launched?.accessToken)
        assertEquals("a-newer-refresh-token", launched?.refreshToken)
        // The renewal is kept, so a second launch a minute later does not repeat it.
        repository.activeForLaunch()
        assertEquals(1, renewals)
    }

    @Test
    fun `renews a token that is about to expire`() = runTest {
        val repository = repository()
        // Inside the five-minute margin: accepted now, but not by the time a world has finished
        // loading, which is the failure the margin exists to avoid.
        repository.add(account("Steve", expiresAt = clock + 60_000))

        repository.activeForLaunch()

        assertEquals(1, renewals)
    }

    @Test
    fun `keeps the old refresh token when microsoft does not issue a new one`() = runTest {
        tokenResponse = HttpStatusCode.OK to
            """{"token_type":"bearer","expires_in":86400,"access_token":"a-microsoft-token"}"""
        val repository = repository()
        repository.add(account("Steve", expiresAt = clock - 1))

        repository.activeForLaunch()

        assertEquals("refresh-Steve", repository.state.value.active?.refreshToken)
    }

    @Test
    fun `says which account has to sign in again when its access was revoked`() = runTest {
        tokenResponse = HttpStatusCode.BadRequest to """{"error":"invalid_grant"}"""
        val repository = repository()
        repository.add(account("Steve", expiresAt = clock - 1))

        val failure = runCatching { repository.activeForLaunch() }.exceptionOrNull()

        assertEquals(AuthenticationError.ReauthenticationRequired::class.java, failure?.javaClass)
        assertEquals("Steve has to sign in to Microsoft again", failure?.message)
        // The account stays listed: it is one sign-in away from working, not gone.
        assertEquals(1, repository.state.value.accounts.size)
    }

    @Test
    fun `has nobody to launch as before anyone signs in`() = runTest {
        assertNull(repository().activeForLaunch())
    }

    @Test
    fun `never renews an offline account`() = runTest {
        val repository = repository()
        repository.addOffline("Player")

        val launched = repository.activeForLaunch()

        assertEquals(AccountType.OFFLINE, launched?.type)
        assertEquals("legacy", launched?.userType)
        assertEquals(0, renewals)
    }

    @Test
    fun `keeps an offline account beside a real one`() = runTest {
        val repository = repository()
        repository.add(account("Steve"))
        repository.addOffline("Player")

        repository.setActive(account("Steve").uuid)

        assertEquals(2, repository.state.value.accounts.size)
        assertNotNull(repository.activeForLaunch()?.refreshToken)
    }

    private fun repository() = AccountRepository(
        store = AccountStore(file, TokenCipher { key }, LodestoneJson),
        auth = MicrosoftAuthApi(
            client = HttpClient(
                MockEngine { request ->
                    when (request.url.toString()) {
                        MicrosoftAuthApi.TOKEN_URL -> {
                            renewals++
                            respondJson(tokenResponse.second, tokenResponse.first)
                        }

                        MicrosoftAuthApi.XBOX_AUTHENTICATE_URL -> respondJson(AuthFixtures.XBOX_TOKEN)
                        MicrosoftAuthApi.XSTS_AUTHORIZE_URL -> respondJson(AuthFixtures.XSTS_TOKEN)
                        MicrosoftAuthApi.MINECRAFT_LOGIN_URL -> respondJson(AuthFixtures.MINECRAFT_LOGIN)
                        MicrosoftAuthApi.MINECRAFT_PROFILE_URL ->
                            respondJson(AuthFixtures.profile("Steve", uuidOf("Steve")))

                        else -> respondError(HttpStatusCode.NotFound)
                    }
                },
            ) {
                expectSuccess = false
                install(ContentNegotiation) { json(LodestoneJson) }
            },
            json = LodestoneJson,
            clientId = "00000000402b5328",
        ),
        now = { clock },
    )

    private fun account(name: String, expiresAt: Long = Long.MAX_VALUE) = MinecraftAccount(
        uuid = uuidOf(name),
        username = name,
        accessToken = "access-$name",
        expiresAt = expiresAt,
        xuid = "2535000000000001",
        refreshToken = "refresh-$name",
    )

    private fun uuidOf(name: String) = name.lowercase().repeat(32).take(32)

    private companion object {
        const val HOUR = 60 * 60 * 1000L

        const val RENEWED_TOKEN =
            """{"token_type":"bearer","expires_in":86400,"access_token":"a-microsoft-token",""" +
                """"refresh_token":"a-newer-refresh-token"}"""
    }
}
