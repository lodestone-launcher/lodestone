package com.github.lodestone.domain.model.account

import kotlinx.serialization.Serializable

/**
 * A player Lodestone can launch as.
 *
 * [accessToken] is the Minecraft services token, not the Microsoft one; it is what the game is
 * given on its command line and what multiplayer servers validate against session servers.
 */
@Serializable
data class MinecraftAccount(
    /** The profile UUID, without dashes, exactly as Mojang returns it. */
    val uuid: String,
    val username: String,
    val accessToken: String,
    /** Epoch milliseconds at which [accessToken] stops being accepted. */
    val expiresAt: Long,
    /** The Xbox user id, passed to the game as `--xuid`. Empty for an offline account. */
    val xuid: String = "",
    /** The Microsoft refresh token, used to renew the whole chain without a new sign-in. */
    val refreshToken: String? = null,
    val type: AccountType = AccountType.MICROSOFT,
    val skinUrl: String? = null,
) {
    /**
     * Renewed a little before the real deadline so a launch that starts near the boundary does not
     * hand the game a token that expires while the world is loading.
     */
    fun isExpired(now: Long, marginMillis: Long = EXPIRY_MARGIN_MILLIS): Boolean =
        type == AccountType.MICROSOFT && now >= expiresAt - marginMillis

    /** The value Minecraft expects for `--userType`. */
    val userType: String
        get() = when (type) {
            AccountType.MICROSOFT -> "msa"
            AccountType.OFFLINE -> "legacy"
        }

    companion object {
        private const val EXPIRY_MARGIN_MILLIS = 5 * 60 * 1000L
    }
}

enum class AccountType {
    MICROSOFT,

    /**
     * A local-only account with a name and a derived UUID and no token. Debug builds allow these so
     * the runtime and graphics work can be exercised without a Mojang round trip; release builds
     * refuse to create them.
     */
    OFFLINE,
}

/** Everything that can go wrong along the Microsoft → Xbox → Minecraft chain. */
sealed class AuthenticationError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class SignInCancelled : AuthenticationError("Sign-in was cancelled")

    class MicrosoftFailed(message: String, cause: Throwable? = null) :
        AuthenticationError(message, cause)

    /** The Microsoft account has never been used with Xbox Live, so there is nothing to sign in to. */
    class NoXboxAccount : AuthenticationError("This Microsoft account has no Xbox profile")

    /** A child account that an adult has not added to a family group. */
    class ChildAccount : AuthenticationError("This account must be added to a Microsoft family group")

    class BannedFromXbox : AuthenticationError("This account is banned from Xbox Live")

    /** Signed in successfully, but the account does not own Minecraft: Java Edition. */
    class NotEntitled : AuthenticationError("This account does not own Minecraft: Java Edition")

    /**
     * The refresh token is no longer accepted, which Microsoft reports as `invalid_grant`. It means
     * the user revoked the launcher's access or changed their password, so there is nothing to
     * retry — only this account signing in again.
     */
    class ReauthenticationRequired(val username: String? = null) : AuthenticationError(
        if (username == null) {
            "This account has to sign in to Microsoft again"
        } else {
            "$username has to sign in to Microsoft again"
        },
    )

    class Network(cause: Throwable) : AuthenticationError("Could not reach the sign-in service", cause)
}
