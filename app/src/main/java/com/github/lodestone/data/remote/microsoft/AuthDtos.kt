package com.github.lodestone.data.remote.microsoft

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------
// Microsoft account (login.live.com)
// ---------------------------------------------------------------------------------------------

@Serializable
data class MicrosoftTokenResponse(
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("user_id") val userId: String? = null,
)

@Serializable
data class MicrosoftErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Xbox Live (user.auth.xboxlive.com and xsts.auth.xboxlive.com)
// ---------------------------------------------------------------------------------------------

@Serializable
data class XboxAuthRequest(
    @SerialName("Properties") val properties: XboxAuthProperties,
    @SerialName("RelyingParty") val relyingParty: String,
    @SerialName("TokenType") val tokenType: String = "JWT",
)

@Serializable
data class XboxAuthProperties(
    @SerialName("AuthMethod") val authMethod: String? = null,
    @SerialName("SiteName") val siteName: String? = null,
    /** The Microsoft access token, for the user-authenticate leg. */
    @SerialName("RpsTicket") val rpsTicket: String? = null,
    /** Always `RETAIL` for the shipping game, for the XSTS leg. */
    @SerialName("SandboxId") val sandboxId: String? = null,
    @SerialName("UserTokens") val userTokens: List<String>? = null,
)

@Serializable
data class XboxAuthResponse(
    @SerialName("IssueInstant") val issueInstant: String? = null,
    @SerialName("NotAfter") val notAfter: String? = null,
    @SerialName("Token") val token: String = "",
    @SerialName("DisplayClaims") val displayClaims: XboxDisplayClaims? = null,
) {
    /**
     * The user hash. It has to be sent alongside the token in the `XBL3.0 x=<uhs>;<token>` header
     * Minecraft services expects — the token alone is not enough.
     */
    val userHash: String? get() = displayClaims?.xui?.firstOrNull()?.userHash

    /** The Xbox user id, which the game wants as `--xuid`. */
    val xuid: String? get() = displayClaims?.xui?.firstOrNull()?.xuid
}

@Serializable
data class XboxDisplayClaims(
    val xui: List<XboxUserInfo> = emptyList(),
)

@Serializable
data class XboxUserInfo(
    @SerialName("uhs") val userHash: String? = null,
    @SerialName("xid") val xuid: String? = null,
    @SerialName("gtg") val gamertag: String? = null,
)

/**
 * XSTS rejects some accounts with a 401 carrying a numeric reason rather than a message. These are
 * the cases worth telling the user about by name, because each has a different fix.
 */
@Serializable
data class XstsErrorResponse(
    @SerialName("Identity") val identity: String? = null,
    @SerialName("XErr") val code: Long = 0,
    @SerialName("Message") val message: String? = null,
    @SerialName("Redirect") val redirect: String? = null,
) {
    companion object {
        const val NO_XBOX_ACCOUNT = 2148916233L
        const val COUNTRY_UNAVAILABLE = 2148916235L
        const val ADULT_VERIFICATION_REQUIRED = 2148916236L
        const val ADULT_VERIFICATION_REQUIRED_ALT = 2148916237L
        const val CHILD_ACCOUNT = 2148916238L
        const val BANNED = 2148916227L
    }
}

// ---------------------------------------------------------------------------------------------
// Minecraft services (api.minecraftservices.com)
// ---------------------------------------------------------------------------------------------

@Serializable
data class MinecraftLoginRequest(
    /** The literal form `XBL3.0 x=<userHash>;<xstsToken>`. */
    val identityToken: String,
)

@Serializable
data class MinecraftLoginResponse(
    /** An opaque account id, *not* the profile UUID and not the player name. */
    val username: String = "",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class EntitlementsResponse(
    val items: List<Entitlement> = emptyList(),
) {
    /**
     * Ownership shows up as one of these two products. Game Pass subscribers get a profile without
     * either, so a missing entitlement is only conclusive when the profile lookup also fails.
     */
    val ownsJavaEdition: Boolean
        get() = items.any { it.name == "product_minecraft" || it.name == "game_minecraft" }
}

@Serializable
data class Entitlement(
    val name: String = "",
    val signature: String? = null,
)

@Serializable
data class MinecraftProfileResponse(
    /** The profile UUID without dashes. */
    val id: String = "",
    val name: String = "",
    val skins: List<ProfileTexture> = emptyList(),
    val capes: List<ProfileTexture> = emptyList(),
)

@Serializable
data class ProfileTexture(
    val id: String = "",
    val state: String = "",
    val url: String = "",
    val variant: String? = null,
)
