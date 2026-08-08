package com.github.lodestone.data.remote.microsoft

import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.domain.model.account.AuthenticationError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the five-leg sign-in chain against served responses, plus the redirect matching that
 * decides whether an authorisation code is read off a URL at all.
 *
 * The matching cases matter most: everything else here fails visibly, but a redirect test that is
 * too loose fails silently, by handing a live authorisation code to somebody else's host.
 */
class MicrosoftAuthApiTest {

    private var profileStatus = HttpStatusCode.OK
    private var tokenResponse: Pair<HttpStatusCode, String> = HttpStatusCode.OK to AuthFixtures.MICROSOFT_TOKEN
    private val submittedForms = mutableListOf<String>()

    // -------------------------------------------------------------------------------------------
    // Redirect matching
    // -------------------------------------------------------------------------------------------

    @Test
    fun `reads the code from the redirect`() {
        assertEquals(
            "M.C107_BAY.2.U.abc",
            api().authorizationCodeFrom(
                "https://login.live.com/oauth20_desktop.srf?code=M.C107_BAY.2.U.abc&lc=1033",
            ),
        )
    }

    @Test
    fun `refuses a lookalike host`() {
        // Why the host is compared rather than the URL prefixed: this one contains the redirect as
        // a substring and is served by somebody else entirely.
        assertNull(
            api().authorizationCodeFrom(
                "https://login.live.com.example.invalid/oauth20_desktop.srf?code=stolen",
            ),
        )
        assertNull(api().authorizationCodeFrom("https://evil.example.invalid/?code=stolen"))
        assertNull(
            api().authorizationCodeFrom("https://login-live.com/oauth20_desktop.srf?code=stolen"),
        )
    }

    @Test
    fun `refuses a path that merely starts with the redirect`() {
        assertNull(api().authorizationCodeFrom("https://login.live.com/oauth20_desktop.srf.evil?code=stolen"))
        assertNull(api().authorizationCodeFrom("https://login.live.com/oauth20_desktop.srf/more?code=stolen"))
    }

    @Test
    fun `refuses a downgraded scheme and a redirected port`() {
        assertNull(api().authorizationCodeFrom("http://login.live.com/oauth20_desktop.srf?code=stolen"))
        assertNull(api().authorizationCodeFrom("https://login.live.com:8443/oauth20_desktop.srf?code=stolen"))
    }

    @Test
    fun `ignores every other page of the sign-in flow`() {
        assertNull(api().authorizationCodeFrom(api().authorizationUrl()))
        assertNull(api().authorizationCodeFrom("https://login.live.com/ppsecure/post.srf"))
        // The redirect itself, carrying a refusal rather than a code.
        assertNull(api().authorizationCodeFrom("https://login.live.com/oauth20_desktop.srf?error=access_denied"))
        assertNull(api().authorizationCodeFrom("https://login.live.com/oauth20_desktop.srf?code="))
        assertNull(api().authorizationCodeFrom("about:blank"))
        assertNull(api().authorizationCodeFrom(""))
    }

    @Test
    fun `recognises the redirect even when it carries a refusal`() {
        // Microsoft's own close button lands here. It is still the end of the flow, and still a
        // page the WebView must not be left sitting on.
        assertTrue(api().isRedirect("https://login.live.com/oauth20_desktop.srf?error=access_denied"))
        assertTrue(api().isRedirect("https://login.live.com/oauth20_desktop.srf"))
        assertFalse(api().isRedirect("https://login.live.com/oauth20_authorize.srf?client_id=x"))
        assertFalse(api().isRedirect("https://login.live.com.example.invalid/oauth20_desktop.srf?code=stolen"))
    }

    @Test
    fun `asks for the account chooser so a second account can be added`() {
        val url = api().authorizationUrl()

        assertTrue(url.startsWith(MicrosoftAuthApi.AUTHORIZE_URL))
        assertTrue(url.contains("prompt=select_account"))
        assertTrue(url.contains("response_type=code"))
    }

    // -------------------------------------------------------------------------------------------
    // The chain
    // -------------------------------------------------------------------------------------------

    @Test
    fun `signs in through every leg`() = runTest {
        val account = api().signIn("an-authorization-code")

        assertEquals("00000000000000000000000000000001", account.uuid)
        assertEquals("Steve", account.username)
        assertEquals("minecraft-access-token", account.accessToken)
        assertEquals("2535000000000001", account.xuid)
        assertEquals("a-refresh-token", account.refreshToken)
        assertEquals("https://textures.example.invalid/steve", account.skinUrl)
        assertTrue(submittedForms.single().contains("grant_type=authorization_code"))
    }

    @Test
    fun `refreshes without another sign-in`() = runTest {
        val account = api().refresh("an-old-refresh-token")

        assertEquals("Steve", account.username)
        assertTrue(submittedForms.single().contains("grant_type=refresh_token"))
    }

    @Test
    fun `reports a revoked refresh token as needing another sign-in`() = runTest {
        tokenResponse = HttpStatusCode.BadRequest to
            """{"error":"invalid_grant","error_description":"The user has revoked access"}"""

        val failure = runCatching { api().refresh("a-revoked-token") }.exceptionOrNull()

        assertEquals(AuthenticationError.ReauthenticationRequired::class.java, failure?.javaClass)
    }

    @Test
    fun `reports an ordinary token failure as a failure`() = runTest {
        tokenResponse = HttpStatusCode.BadRequest to """{"error":"invalid_request"}"""

        val failure = runCatching { api().refresh("a-token") }.exceptionOrNull()

        assertEquals(AuthenticationError.MicrosoftFailed::class.java, failure?.javaClass)
    }

    @Test
    fun `reports an account that does not own the game`() = runTest {
        profileStatus = HttpStatusCode.NotFound

        val failure = runCatching { api().signIn("a-code") }.exceptionOrNull()

        assertEquals(AuthenticationError.NotEntitled::class.java, failure?.javaClass)
        assertEquals("This account does not own Minecraft: Java Edition", failure?.message)
    }

    @Test
    fun `names the accounts xbox live refuses`() = runTest {
        assertEquals(
            AuthenticationError.NoXboxAccount::class.java,
            xstsRefusal(XstsErrorResponse.NO_XBOX_ACCOUNT)?.javaClass,
        )
        assertEquals(
            AuthenticationError.ChildAccount::class.java,
            xstsRefusal(XstsErrorResponse.CHILD_ACCOUNT)?.javaClass,
        )
        assertEquals(
            AuthenticationError.BannedFromXbox::class.java,
            xstsRefusal(XstsErrorResponse.BANNED)?.javaClass,
        )
    }

    private suspend fun xstsRefusal(code: Long): Throwable? =
        runCatching { api(xstsError = code).signIn("a-code") }.exceptionOrNull()

    private fun api(xstsError: Long? = null) = MicrosoftAuthApi(
        client = HttpClient(
            MockEngine { request ->
                when (request.url.toString()) {
                    MicrosoftAuthApi.TOKEN_URL -> {
                        submittedForms += request.body.toByteArray().decodeToString()
                        respondJson(tokenResponse.second, tokenResponse.first)
                    }

                    MicrosoftAuthApi.XBOX_AUTHENTICATE_URL -> respondJson(AuthFixtures.XBOX_TOKEN)

                    MicrosoftAuthApi.XSTS_AUTHORIZE_URL -> if (xstsError == null) {
                        respondJson(AuthFixtures.XSTS_TOKEN)
                    } else {
                        respondJson("""{"XErr":$xstsError}""", HttpStatusCode.Unauthorized)
                    }

                    MicrosoftAuthApi.MINECRAFT_LOGIN_URL -> respondJson(AuthFixtures.MINECRAFT_LOGIN)

                    MicrosoftAuthApi.MINECRAFT_PROFILE_URL -> if (profileStatus == HttpStatusCode.OK) {
                        respondJson(
                            AuthFixtures.profile(
                                name = "Steve",
                                uuid = "00000000000000000000000000000001",
                                skinUrl = "https://textures.example.invalid/steve",
                            ),
                        )
                    } else {
                        respondError(profileStatus)
                    }

                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(LodestoneJson) }
        },
        json = LodestoneJson,
        clientId = "00000000402b5328",
    )
}
