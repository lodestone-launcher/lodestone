package com.github.lodestone.ui.signin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Microsoft's own sign-in page, hosted in a WebView.
 *
 * The launcher never sees a password: the page belongs to Microsoft, and all this screen does is
 * watch for the redirect it ends on and take the authorisation code off it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    onSignedIn: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedInAs, state.cancelled) {
        when {
            state.signedInAs != null -> onSignedIn()
            state.cancelled -> onClose()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Sign in to Microsoft") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel sign-in")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.error != null -> SignInFailure(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::retry,
                    onClose = onClose,
                )

                state.isCompleting || state.signedInAs != null -> Busy("Signing in…")

                else -> key(state.attempt) {
                    // Keyed on the attempt so "try again" builds a new WebView rather than reusing
                    // one that is still holding the failed page and its cookies.
                    SignInWebView(
                        url = state.authorizationUrl,
                        onNavigation = viewModel::onNavigation,
                        onPageFailed = viewModel::onPageFailed,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Busy(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

/**
 * What went wrong, in the words of whichever step refused.
 *
 * "This account does not own Minecraft: Java Edition" and "this account has no Xbox profile" are
 * problems the person can go and fix; a generic "sign-in failed" would send them nowhere.
 */
@Composable
private fun SignInFailure(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) { Text("Try again") }
        TextButton(onClick = onClose) { Text("Close") }
    }
}

/**
 * The WebView itself, deliberately given as little of the app as it can be given.
 *
 * JavaScript is on because Microsoft's sign-in page is a JavaScript application and renders nothing
 * without it. Nothing else is: no bridge object, so there is no `window.` anything that reaches
 * Kotlin, and no file or content access, so a page cannot read `file:///data/...` or walk content
 * providers on behalf of whoever served it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInWebView(
    url: String,
    onNavigation: (String) -> Boolean,
    onPageFailed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnNavigation by rememberUpdatedState(onNavigation)
    val currentOnPageFailed by rememberUpdatedState(onPageFailed)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    // Microsoft's page keeps its flow state in session storage; without this it
                    // loops back to the account chooser after entering a password.
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // Credentials are not something to leave in a disk cache on a shared device,
                    // and the flow is short enough that caching buys nothing.
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    setGeolocationEnabled(false)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = request.isForMainFrame && currentOnNavigation(request.url.toString())

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        // A backstop for navigations the client is not consulted about, such as one
                        // begun by a form POST. Stopping here still keeps the code out of the page.
                        if (currentOnNavigation(url)) {
                            view.stopLoading()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        // Subresource failures are Microsoft's business; only a main frame that
                        // will not load is something the user has to be told about.
                        if (request.isForMainFrame) {
                            currentOnPageFailed(error.description.toString())
                        }
                    }
                }
                // A stale Microsoft session would silently sign the same account in again, which is
                // both wrong for "add another account" and a live session left on a shared device.
                clearSignInSession()
                loadUrl(url)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.clearSignInSession()
            webView.destroy()
        },
    )
}

/** Leaves nothing behind that could sign somebody in without asking. */
private fun WebView.clearSignInSession() {
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        // Cookies are written back lazily, so without this they survive until the process ends.
        flush()
    }
    WebStorage.getInstance().deleteAllData()
    clearCache(true)
    clearFormData()
    clearHistory()
}
