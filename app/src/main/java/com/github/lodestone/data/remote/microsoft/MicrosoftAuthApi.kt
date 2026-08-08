package com.github.lodestone.data.remote.microsoft

import com.github.lodestone.BuildConfig
import com.github.lodestone.domain.model.account.AuthenticationError
import com.github.lodestone.domain.model.account.MinecraftAccount
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.Parameters
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.IOException

/**
 * Signs in through Microsoft, Xbox Live and Minecraft services.
 *
 * The chain is fixed and each leg feeds the next:
 *
 *  1. `login.live.com` turns an authorisation code into a Microsoft access token.
 *  2. `user.auth.xboxlive.com` exchanges that for an Xbox Live token and a user hash.
 *  3. `xsts.auth.xboxlive.com` authorises that token for Minecraft's relying party.
 *  4. `api.minecraftservices.com` exchanges the XSTS token for the token the game actually uses.
 *  5. The entitlement and profile lookups confirm the account owns the game and name the player.
 *
 * The official launcher uses Microsoft's SISU endpoint, which collapses steps 2 and 3 into one
 * call, but that path is gated on a registered Xbox title id and is not open to third parties.
 * This is the documented flow it also supports.
 */
class MicrosoftAuthApi(
    private val client: HttpClient,
    private val json: Json,
    private val clientId: String = BuildConfig.MSA_CLIENT_ID,
) {

    /**
     * The URL to load in a WebView to begin sign-in. When the view navigates to [REDIRECT_URI], the
     * `code` query parameter is the authorisation code for [signIn].
     */
    fun authorizationUrl(): String = URLBuilder(AUTHORIZE_URL).apply {
        parameters.append("client_id", clientId)
        parameters.append("response_type", "code")
        parameters.append("scope", XBOX_SCOPE)
        parameters.append("redirect_uri", REDIRECT_URI)
        // Forces the account chooser so a second account can be added on a shared device.
        parameters.append("prompt", "select_account")
    }.buildString()

    /**
     * Whether the WebView has reached the end of the flow, whatever it ended in.
     *
     * The redirect is the only page the WebView must never be allowed to load: it is where the
     * result arrives, and following it would leave that result in the page's history.
     */
    fun isRedirect(url: String): Boolean = redirectParameters(url) != null

    /**
     * Extracts the authorisation code from a redirect the WebView was about to follow, or returns
     * null when there is none — either because this is not the redirect, or because it arrived
     * carrying a refusal instead.
     */
    fun authorizationCodeFrom(url: String): String? =
        redirectParameters(url)?.get("code")?.takeIf(String::isNotBlank)

    /**
     * The query of [url] when it is [REDIRECT_URI], and null for every other page.
     *
     * Scheme, host, port and path all have to match exactly. A prefix or `contains` test would
     * accept `https://login.live.com.example.invalid/oauth20_desktop.srf?code=…` and hand a live
     * authorisation code to whoever owns that name — one redeemable code is all it takes to own
     * the account for as long as the refresh token lives.
     */
    private fun redirectParameters(url: String): Parameters? {
        val candidate = runCatching { Url(url) }.getOrNull() ?: return null
        if (candidate.protocol.name != REDIRECT.protocol.name) return null
        if (!candidate.host.equals(REDIRECT.host, ignoreCase = true)) return null
        if (candidate.port != REDIRECT.port) return null
        if (candidate.encodedPath != REDIRECT.encodedPath) return null
        return candidate.parameters
    }

    /** Completes sign-in for a code obtained from [authorizationCodeFrom]. */
    suspend fun signIn(authorizationCode: String): MinecraftAccount {
        val microsoft = requestMicrosoftToken(
            parameters {
                append("client_id", clientId)
                append("code", authorizationCode)
                append("grant_type", "authorization_code")
                append("redirect_uri", REDIRECT_URI)
                append("scope", XBOX_SCOPE)
            },
        )
        return completeChain(microsoft)
    }

    /** Renews an account whose Minecraft token has expired, without another sign-in. */
    suspend fun refresh(refreshToken: String): MinecraftAccount {
        val microsoft = requestMicrosoftToken(
            parameters {
                append("client_id", clientId)
                append("refresh_token", refreshToken)
                append("grant_type", "refresh_token")
                append("scope", XBOX_SCOPE)
            },
        )
        return completeChain(microsoft)
    }

    private suspend fun completeChain(microsoft: MicrosoftTokenResponse): MinecraftAccount {
        val xbox = authenticateWithXbox(microsoft.accessToken)
        val userHash = xbox.userHash
            ?: throw AuthenticationError.MicrosoftFailed("Xbox Live returned no user hash")

        val xsts = authorizeWithXsts(xbox.token)
        val identityToken = "XBL3.0 x=$userHash;${xsts.token}"

        val minecraft = loginWithXbox(identityToken)
        val profile = fetchProfile(minecraft.accessToken)

        return MinecraftAccount(
            uuid = profile.id,
            username = profile.name,
            accessToken = minecraft.accessToken,
            expiresAt = System.currentTimeMillis() + minecraft.expiresIn * 1000,
            xuid = xsts.xuid ?: xbox.xuid.orEmpty(),
            refreshToken = microsoft.refreshToken,
            skinUrl = profile.skins.firstOrNull { it.state == "ACTIVE" }?.url,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Step 1 — Microsoft
    // -----------------------------------------------------------------------------------------

    private suspend fun requestMicrosoftToken(form: Parameters): MicrosoftTokenResponse {
        val response = runNetwork {
            client.submitForm(url = TOKEN_URL, formParameters = form)
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            val error = runCatching {
                json.decodeFromString(MicrosoftErrorResponse.serializer(), body)
            }.getOrNull()
            // `invalid_grant` on a refresh means the user revoked access or changed their password;
            // there is nothing to retry, the account has to sign in again. Named separately from
            // the rest so the UI can say that instead of showing a failure that looks transient.
            if (error?.error == INVALID_GRANT) {
                throw AuthenticationError.ReauthenticationRequired()
            }
            throw AuthenticationError.MicrosoftFailed(
                error?.errorDescription ?: error?.error ?: "Microsoft sign-in failed (${response.status.value})",
            )
        }
        return response.body()
    }

    // -----------------------------------------------------------------------------------------
    // Step 2 — Xbox Live user token
    // -----------------------------------------------------------------------------------------

    private suspend fun authenticateWithXbox(microsoftAccessToken: String): XboxAuthResponse {
        val request = XboxAuthRequest(
            properties = XboxAuthProperties(
                authMethod = "RPS",
                siteName = "user.auth.xboxlive.com",
                // The legacy launcher client receives an MBI_SSL ticket, which Xbox accepts raw.
                // Tokens from an Azure AD registration must be prefixed with `d=` instead.
                rpsTicket = microsoftAccessToken,
            ),
            relyingParty = "http://auth.xboxlive.com",
        )
        val response = runNetwork {
            client.post(XBOX_AUTHENTICATE_URL) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json)
                setBody(request)
            }
        }
        if (!response.status.isSuccess()) {
            throw AuthenticationError.MicrosoftFailed(
                "Xbox Live rejected the sign-in (${response.status.value})",
            )
        }
        return response.body()
    }

    // -----------------------------------------------------------------------------------------
    // Step 3 — XSTS authorisation for Minecraft
    // -----------------------------------------------------------------------------------------

    private suspend fun authorizeWithXsts(xboxToken: String): XboxAuthResponse {
        val request = XboxAuthRequest(
            properties = XboxAuthProperties(
                sandboxId = "RETAIL",
                userTokens = listOf(xboxToken),
            ),
            relyingParty = MINECRAFT_RELYING_PARTY,
        )
        val response = runNetwork {
            client.post(XSTS_AUTHORIZE_URL) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json)
                setBody(request)
            }
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw xstsFailure(response.bodyAsText())
        }
        if (!response.status.isSuccess()) {
            throw AuthenticationError.MicrosoftFailed(
                "Xbox authorisation failed (${response.status.value})",
            )
        }
        return response.body()
    }

    /** Maps XSTS's numeric reason codes onto errors a user can act on. */
    private fun xstsFailure(body: String): AuthenticationError {
        val error = runCatching {
            json.decodeFromString(XstsErrorResponse.serializer(), body)
        }.getOrNull() ?: return AuthenticationError.MicrosoftFailed("Xbox authorisation was refused")

        return when (error.code) {
            XstsErrorResponse.NO_XBOX_ACCOUNT -> AuthenticationError.NoXboxAccount()
            XstsErrorResponse.CHILD_ACCOUNT -> AuthenticationError.ChildAccount()
            XstsErrorResponse.BANNED -> AuthenticationError.BannedFromXbox()
            else -> {
                Timber.w("Unhandled XSTS error %d: %s", error.code, error.message)
                AuthenticationError.MicrosoftFailed(
                    error.message ?: "Xbox authorisation was refused (${error.code})",
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Steps 4 and 5 — Minecraft services
    // -----------------------------------------------------------------------------------------

    private suspend fun loginWithXbox(identityToken: String): MinecraftLoginResponse {
        val response = runNetwork {
            client.post(MINECRAFT_LOGIN_URL) {
                contentType(ContentType.Application.Json)
                setBody(MinecraftLoginRequest(identityToken))
            }
        }
        if (!response.status.isSuccess()) {
            throw AuthenticationError.MicrosoftFailed(
                "Minecraft sign-in failed (${response.status.value})",
            )
        }
        return response.body()
    }

    /**
     * Fetches the player's profile, which doubles as the ownership check: Minecraft services
     * answers 404 for an account that does not own the game.
     *
     * The entitlement list is consulted only to tell "does not own it" apart from a transient
     * failure, because Game Pass accounts legitimately have an empty list.
     */
    private suspend fun fetchProfile(minecraftToken: String): MinecraftProfileResponse {
        val response = runNetwork {
            client.get(MINECRAFT_PROFILE_URL) {
                header(HttpHeaders.Authorization, "Bearer $minecraftToken")
            }
        }
        if (response.status == HttpStatusCode.NotFound) {
            throw AuthenticationError.NotEntitled()
        }
        if (!response.status.isSuccess()) {
            throw AuthenticationError.MicrosoftFailed(
                "Could not read the Minecraft profile (${response.status.value})",
            )
        }
        val profile: MinecraftProfileResponse = response.body()
        if (profile.id.isBlank() || profile.name.isBlank()) {
            throw AuthenticationError.NotEntitled()
        }
        return profile
    }

    /** Reports whether the account holds a Java Edition entitlement, for display purposes. */
    suspend fun fetchEntitlements(minecraftToken: String): EntitlementsResponse = runNetwork {
        client.get(MINECRAFT_ENTITLEMENTS_URL) {
            header(HttpHeaders.Authorization, "Bearer $minecraftToken")
        }
    }.body()

    /** Wraps transport failures so callers see one error type across the whole chain. */
    private suspend fun runNetwork(block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            throw AuthenticationError.Network(failure)
        }

    companion object {
        /**
         * The scope the Minecraft launcher itself requests. `MBI_SSL` yields a ticket Xbox Live
         * accepts directly, which is why no `d=` prefix is needed in step 2.
         */
        const val XBOX_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"

        /**
         * The desktop redirect target. Nothing is served from it — the WebView is stopped the
         * moment it tries to navigate there, and the `code` parameter is read off the URL.
         */
        const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"

        /** Parsed once, because [authorizationCodeFrom] runs for every URL the WebView visits. */
        private val REDIRECT = Url(REDIRECT_URI)

        /** Microsoft's OAuth code for a refresh token that will never be accepted again. */
        private const val INVALID_GRANT = "invalid_grant"

        const val AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf"
        const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"
        const val LOGOUT_URL = "https://login.live.com/oauth20_logout.srf"

        const val XBOX_AUTHENTICATE_URL = "https://user.auth.xboxlive.com/user/authenticate"
        const val XSTS_AUTHORIZE_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        const val MINECRAFT_RELYING_PARTY = "rp://api.minecraftservices.com/"

        const val MINECRAFT_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox"
        const val MINECRAFT_ENTITLEMENTS_URL =
            "https://api.minecraftservices.com/entitlements/mcstore"
        const val MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
    }
}
