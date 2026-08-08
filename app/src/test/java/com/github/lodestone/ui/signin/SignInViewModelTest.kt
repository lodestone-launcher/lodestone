package com.github.lodestone.ui.signin

import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.data.local.account.AccountStore
import com.github.lodestone.data.local.account.TokenCipher
import com.github.lodestone.data.remote.microsoft.AuthFixtures
import com.github.lodestone.data.remote.microsoft.MicrosoftAuthApi
import com.github.lodestone.data.remote.microsoft.respondJson
import com.github.lodestone.data.repository.AccountRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/**
 * What the sign-in screen does with the URLs the WebView offers it.
 *
 * The WebView itself cannot run here, but the decision it delegates can: which navigations are the
 * redirect, how many times one code is spent, and what the screen says when a leg refuses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var profileStatus = HttpStatusCode.OK
    private var exchanges = 0
    private lateinit var accounts: AccountRepository

    @Before
    fun setUp() {
        // The sign-in chain and the account store both hop to real dispatchers, so the tests wait
        // on the state the screen would render rather than on the scheduler being idle.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        accounts = AccountRepository(
            store = AccountStore(
                File(temporary.newFolder("files"), "accounts.bin"),
                TokenCipher { key },
                LodestoneJson,
            ),
            auth = authApi(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts on a microsoft authorisation url`() = runTest {
        assertTrue(viewModel().uiState.value.authorizationUrl.startsWith(MicrosoftAuthApi.AUTHORIZE_URL))
    }

    @Test
    fun `lets the sign-in pages load`() = runTest {
        val viewModel = viewModel()

        assertFalse(viewModel.onNavigation(viewModel.uiState.value.authorizationUrl))
        assertFalse(viewModel.onNavigation("https://login.live.com/ppsecure/post.srf"))
        assertFalse(viewModel.onNavigation("https://account.live.com/recover"))
        assertEquals(0, exchanges)
    }

    @Test
    fun `takes the code off the redirect and stores the account`() = runTest {
        val viewModel = viewModel()

        // True: the WebView must not follow it. The target serves nothing, and loading it would
        // leave the code in the page's history.
        assertTrue(viewModel.onNavigation("https://login.live.com/oauth20_desktop.srf?code=a-code"))
        val settled = viewModel.awaitSettled()

        assertEquals("Steve", settled.signedInAs)
        assertNull(settled.error)
        assertEquals("Steve", accounts.state.value.active?.username)
        assertEquals("minecraft-access-token", accounts.state.value.active?.accessToken)
    }

    @Test
    fun `spends an authorisation code once`() = runTest {
        val viewModel = viewModel()

        viewModel.onNavigation("https://login.live.com/oauth20_desktop.srf?code=a-code")
        viewModel.onNavigation("https://login.live.com/oauth20_desktop.srf?code=a-code")
        viewModel.awaitSettled()

        // A code is redeemable exactly once, so the second offer — `onPageStarted` backstopping
        // `shouldOverrideUrlLoading` — must not turn into a second exchange that fails.
        assertEquals(1, exchanges)
    }

    @Test
    fun `leaves when microsoft is closed rather than signed in to`() = runTest {
        val viewModel = viewModel()

        // The redirect with no code, which is where Microsoft's own close button lands.
        assertTrue(viewModel.onNavigation("https://login.live.com/oauth20_desktop.srf?error=access_denied"))

        assertTrue(viewModel.uiState.value.cancelled)
        assertNull(viewModel.uiState.value.error)
        assertEquals(0, exchanges)
    }

    @Test
    fun `says when the account does not own the game`() = runTest {
        profileStatus = HttpStatusCode.NotFound
        val viewModel = viewModel()

        viewModel.onNavigation("https://login.live.com/oauth20_desktop.srf?code=a-code")
        val settled = viewModel.awaitSettled()

        assertEquals("This account does not own Minecraft: Java Edition", settled.error)
        assertNull(settled.signedInAs)
        assertTrue("a refused sign-in stored an account", accounts.state.value.accounts.isEmpty())
    }

    @Test
    fun `starts over with a fresh authorisation request`() = runTest {
        profileStatus = HttpStatusCode.NotFound
        val viewModel = viewModel()
        viewModel.onNavigation("https://login.live.com/oauth20_desktop.srf?code=a-code")
        viewModel.awaitSettled()

        profileStatus = HttpStatusCode.OK
        viewModel.retry()

        assertNull(viewModel.uiState.value.error)
        assertEquals(1, viewModel.uiState.value.attempt)

        viewModel.onNavigation("https://login.live.com/oauth20_desktop.srf?code=another-code")

        assertEquals("Steve", viewModel.awaitSettled().signedInAs)
    }

    /** Waits for the state the screen would draw once the chain has run to an end. */
    private suspend fun SignInViewModel.awaitSettled(): SignInUiState =
        uiState.first { it.signedInAs != null || it.error != null }

    private fun viewModel() = SignInViewModel(authApi(), accounts)

    private fun authApi() = MicrosoftAuthApi(
        client = HttpClient(
            MockEngine { request ->
                when (request.url.toString()) {
                    MicrosoftAuthApi.TOKEN_URL -> {
                        exchanges++
                        respondJson(AuthFixtures.MICROSOFT_TOKEN)
                    }

                    MicrosoftAuthApi.XBOX_AUTHENTICATE_URL -> respondJson(AuthFixtures.XBOX_TOKEN)
                    MicrosoftAuthApi.XSTS_AUTHORIZE_URL -> respondJson(AuthFixtures.XSTS_TOKEN)
                    MicrosoftAuthApi.MINECRAFT_LOGIN_URL -> respondJson(AuthFixtures.MINECRAFT_LOGIN)
                    MicrosoftAuthApi.MINECRAFT_PROFILE_URL -> if (profileStatus == HttpStatusCode.OK) {
                        respondJson(AuthFixtures.profile("Steve", "0".repeat(31) + "1"))
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
