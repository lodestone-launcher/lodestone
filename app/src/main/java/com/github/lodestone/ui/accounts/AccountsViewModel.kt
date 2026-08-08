package com.github.lodestone.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.lodestone.BuildConfig
import com.github.lodestone.data.repository.AccountRepository
import com.github.lodestone.domain.model.account.MinecraftAccount
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountsUiState(
    val accounts: List<MinecraftAccount> = emptyList(),
    val activeUuid: String? = null,
    /** Offline accounts are a debug affordance, so the button that makes one is too. */
    val canAddOffline: Boolean = BuildConfig.ALLOW_OFFLINE_ACCOUNTS,
)

/** Lists the accounts, switches between them, and forgets the ones that are signed out. */
class AccountsViewModel(private val accounts: AccountRepository) : ViewModel() {

    val uiState: StateFlow<AccountsUiState> = accounts.state
        .map { AccountsUiState(accounts = it.accounts, activeUuid = it.activeUuid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AccountsUiState())

    init {
        viewModelScope.launch { accounts.load() }
    }

    fun select(uuid: String) {
        viewModelScope.launch { accounts.setActive(uuid) }
    }

    fun signOut(uuid: String) {
        viewModelScope.launch { accounts.remove(uuid) }
    }

    fun addOffline(username: String) {
        viewModelScope.launch { accounts.addOffline(username.trim().ifBlank { "Player" }) }
    }

    private companion object {
        /** Long enough to survive a rotation without reloading the store. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
