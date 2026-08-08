package com.github.lodestone.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.lodestone.data.remote.microsoft.MicrosoftAuthApi
import com.github.lodestone.data.repository.AccountRepository
import com.github.lodestone.domain.model.account.AuthenticationError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class SignInUiState(
    val authorizationUrl: String,
    /** Bumped to throw the WebView away and start over, which is what "try again" has to mean. */
    val attempt: Int = 0,
    val isCompleting: Boolean = false,
    val error: String? = null,
    /** Set once the account is stored, which is the screen's cue to leave. */
    val signedInAs: String? = null,
    /** Set when the flow ended without a code, which is the screen's cue to leave empty-handed. */
    val cancelled: Boolean = false,
)

/**
 * Turns the authorisation code the WebView catches into a stored account.
 *
 * Nothing here logs the code or either token. The chain runs over the shared Ktor client, whose
 * logging is capped at headers for the same reason — a bug report with a live refresh token in it
 * is an account handed to whoever reads the log.
 */
class SignInViewModel(
    private val auth: MicrosoftAuthApi,
    private val accounts: AccountRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState(authorizationUrl = auth.authorizationUrl()))
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    /** An authorisation code is redeemable once, so a second navigation must not spend it again. */
    private var consumed = false

    /**
     * Offers a URL the WebView is about to load.
     *
     * @return true when it was the redirect, which the caller must then refuse to follow: the
     * redirect target serves nothing, and letting it load would leave the code in page history.
     */
    fun onNavigation(url: String): Boolean {
        if (!auth.isRedirect(url)) {
            return false
        }
        if (consumed) {
            return true
        }
        consumed = true
        val code = auth.authorizationCodeFrom(url)
        if (code == null) {
            // The redirect without a code: the user closed Microsoft's page, or it refused. The
            // target itself renders "you have reached a page that is not normally shown", which is
            // not somewhere to strand anyone.
            Timber.i("Sign-in was cancelled")
            _uiState.update { it.copy(cancelled = true) }
            return true
        }
        completeSignIn(code)
        return true
    }

    /** Reports a page that could not be loaded, which is almost always the network. */
    fun onPageFailed(description: String) {
        if (_uiState.value.isCompleting || _uiState.value.signedInAs != null) {
            return
        }
        _uiState.update { it.copy(error = "Could not load the Microsoft sign-in page: $description") }
    }

    fun retry() {
        consumed = false
        _uiState.update {
            it.copy(
                attempt = it.attempt + 1,
                error = null,
                isCompleting = false,
                cancelled = false,
                // A new authorisation request rather than the old URL, so a code that was already
                // spent or a session that has since gone stale is not replayed.
                authorizationUrl = auth.authorizationUrl(),
            )
        }
    }

    private fun completeSignIn(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCompleting = true, error = null) }
            runCatching { auth.signIn(code) }
                .onSuccess { account ->
                    accounts.add(account)
                    Timber.i("Signed in as %s", account.username)
                    _uiState.update { it.copy(isCompleting = false, signedInAs = account.username) }
                }
                .onFailure { failure ->
                    // By type and message only: the chain's own errors are safe to print, and
                    // anything else would risk a URL with a code in it reaching the log.
                    Timber.w("Sign-in failed: %s", failure.javaClass.simpleName)
                    _uiState.update {
                        it.copy(
                            isCompleting = false,
                            error = (failure as? AuthenticationError)?.message
                                ?: "Sign-in failed. Please try again.",
                        )
                    }
                }
        }
    }
}
