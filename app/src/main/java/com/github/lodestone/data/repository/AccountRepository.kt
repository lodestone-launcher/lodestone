package com.github.lodestone.data.repository

import com.github.lodestone.BuildConfig
import com.github.lodestone.data.local.account.AccountStore
import com.github.lodestone.data.local.account.StoredAccounts
import com.github.lodestone.data.remote.microsoft.MicrosoftAuthApi
import com.github.lodestone.domain.model.account.AccountType
import com.github.lodestone.domain.model.account.AuthenticationError
import com.github.lodestone.domain.model.account.MinecraftAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID

/** The accounts the launcher knows about, and the one a launch will use. */
data class AccountsState(
    val accounts: List<MinecraftAccount> = emptyList(),
    val activeUuid: String? = null,
) {
    val active: MinecraftAccount? get() = accounts.firstOrNull { it.uuid == activeUuid }
}

/**
 * Owns the signed-in accounts: which ones exist, which is current, and keeping the current one's
 * token usable.
 *
 * Loading is lazy rather than done in a constructor-held scope, so nothing touches the key store
 * until something actually asks who is signed in — a cold start that goes straight to the version
 * list never decrypts anything.
 */
class AccountRepository(
    private val store: AccountStore,
    private val auth: MicrosoftAuthApi,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private var loaded = false

    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    /** Reads the store on first call and does nothing on every one after. */
    suspend fun load() {
        if (loaded) {
            return
        }
        mutex.withLock {
            if (loaded) {
                return
            }
            val stored = store.load()
            _state.value = AccountsState(stored.accounts, stored.activeUuid ?: stored.accounts.firstOrNull()?.uuid)
            loaded = true
        }
    }

    /**
     * Adds a freshly signed-in account and makes it current.
     *
     * Signing in again as someone already listed replaces that entry rather than adding a second
     * one, because the account is the same account — only its tokens are newer.
     */
    suspend fun add(account: MinecraftAccount) {
        load()
        mutate { current ->
            StoredAccounts(
                accounts = current.accounts.filterNot { it.uuid == account.uuid } + account,
                activeUuid = account.uuid,
            )
        }
    }

    suspend fun setActive(uuid: String) {
        load()
        mutate { current ->
            if (current.accounts.none { it.uuid == uuid }) {
                return
            }
            StoredAccounts(current.accounts, uuid)
        }
    }

    /**
     * Forgets an account entirely.
     *
     * The tokens go with it: the store is rewritten without them rather than the account merely
     * being deselected, so signing out actually revokes this device's ability to play as them.
     */
    suspend fun remove(uuid: String) {
        load()
        mutate { current ->
            val remaining = current.accounts.filterNot { it.uuid == uuid }
            StoredAccounts(
                accounts = remaining,
                activeUuid = current.activeUuid.takeIf { it != uuid } ?: remaining.firstOrNull()?.uuid,
            )
        }
    }

    /**
     * Creates a local account with no Mojang session behind it.
     *
     * Debug builds only, and enforced here rather than at the call site so there is exactly one
     * place that can produce one.
     */
    suspend fun addOffline(username: String) {
        check(BuildConfig.ALLOW_OFFLINE_ACCOUNTS) { "Offline accounts are debug-only" }
        add(
            MinecraftAccount(
                // Mirrors Mojang's scheme for offline identities so the world save directory is
                // stable across launches and matches what other launchers would produce.
                uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray())
                    .toString().replace("-", ""),
                username = username,
                accessToken = "0",
                expiresAt = Long.MAX_VALUE,
                type = AccountType.OFFLINE,
            ),
        )
    }

    /**
     * The account to launch as, with a token that will still be valid when the world loads.
     *
     * @return null when nobody is signed in, which the caller turns into a sign-in prompt.
     * @throws AuthenticationError.ReauthenticationRequired when the refresh token has been revoked.
     */
    suspend fun activeForLaunch(): MinecraftAccount? {
        load()
        val account = _state.value.active ?: return null
        if (!account.isExpired(now())) {
            return account
        }

        val refreshToken = account.refreshToken
            ?: throw AuthenticationError.ReauthenticationRequired(account.username)
        Timber.i("Renewing the session for %s", account.username)
        val renewed = try {
            auth.refresh(refreshToken)
        } catch (revoked: AuthenticationError.ReauthenticationRequired) {
            // Named here so the message can say who has to sign in, which matters once there is
            // more than one account in the list.
            throw AuthenticationError.ReauthenticationRequired(account.username)
        }

        // Microsoft normally rotates the refresh token on every renewal, but is not required to
        // return one. Dropping it in that case would cost the account its only way back.
        val updated = renewed.copy(refreshToken = renewed.refreshToken ?: refreshToken)
        add(updated)
        return updated
    }

    private suspend inline fun mutate(block: (AccountsState) -> StoredAccounts) {
        mutex.withLock {
            val next = block(_state.value)
            store.save(next)
            _state.value = AccountsState(next.accounts, next.activeUuid)
        }
    }
}
