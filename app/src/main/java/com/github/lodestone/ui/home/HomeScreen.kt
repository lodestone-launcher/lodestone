package com.github.lodestone.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.lodestone.domain.model.version.VersionChannel
import com.github.lodestone.domain.model.version.VersionEntry

/**
 * The version browser: pick a Minecraft version and install it.
 *
 * This is the first screen that actually exercises the download and install path end to end
 * against Mojang's servers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val account by viewModel.activeAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    state.runtimePrompt?.let { prompt ->
        RuntimeDownloadDialog(
            prompt = prompt,
            onConfirm = { viewModel.confirmRuntimeDownload(context) },
            onDismiss = viewModel::dismissRuntimePrompt,
        )
    }

    state.signInRequired?.let { reason ->
        SignInRequiredDialog(
            reason = reason,
            onConfirm = {
                viewModel.dismissSignInPrompt()
                onOpenAccounts()
            },
            onDismiss = viewModel::dismissSignInPrompt,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Lodestone") },
                actions = {
                    // The name doubles as the button: who is signed in and where to change it are
                    // the same question.
                    account?.let { Text(it.username, style = MaterialTheme.typography.labelLarge) }
                    IconButton(onClick = onOpenAccounts) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = account?.let { "Accounts, signed in as ${it.username}" }
                                ?: "Sign in",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ChannelFilter(
                selected = state.channel,
                onSelect = viewModel::selectChannel,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.installing != null) {
                InstallProgress(
                    versionId = state.installing.orEmpty(),
                    label = state.progressLabel,
                    progress = state.progress,
                )
            }

            HorizontalDivider()

            if (state.isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.visibleVersions, key = VersionEntry::id) { entry ->
                        VersionRow(
                            entry = entry,
                            enabled = state.installing == null,
                            onInstall = { viewModel.install(entry) },
                            onPlay = { viewModel.launch(context, entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelFilter(
    selected: VersionChannel,
    onSelect: (VersionChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        // `UNKNOWN` is a parsing fallback rather than something Mojang publishes, so it is not
        // offered as a filter.
        VersionChannel.entries.filter { it != VersionChannel.UNKNOWN }.forEach { channel ->
            FilterChip(
                selected = channel == selected,
                onClick = { onSelect(channel) },
                label = { Text(channel.label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/**
 * Asks before spending the download.
 *
 * The size is in the body rather than buried in a log line: this is the one moment where someone on
 * a metered connection can still say no.
 */
@Composable
private fun RuntimeDownloadDialog(
    prompt: RuntimePrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download Java ${prompt.feature}?") },
        text = {
            Text(
                "${prompt.entry.id} needs the Java ${prompt.feature} runtime, which is not " +
                    "installed. It is a ${prompt.size} download and is kept for every version " +
                    "that needs it.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * Raised when Play is pressed with nobody to play as, or with an account whose access was revoked.
 *
 * The reason is passed through rather than written here: "Steve has to sign in to Microsoft again"
 * and "sign in with the account that owns the game" send someone to two different places.
 */
@Composable
private fun SignInRequiredDialog(reason: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to play") },
        text = { Text(reason) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Accounts") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun InstallProgress(versionId: String, label: String?, progress: Float) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Installing $versionId", style = MaterialTheme.typography.titleSmall)
        label?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun VersionRow(
    entry: VersionEntry,
    enabled: Boolean,
    onInstall: () -> Unit,
    onPlay: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(entry.id) },
        supportingContent = { Text(entry.releaseTime?.take(10).orEmpty()) },
        trailingContent = {
            Row {
                Button(onClick = onInstall, enabled = enabled) { Text("Install") }
                Button(
                    onClick = onPlay,
                    enabled = enabled,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Play") }
            }
        },
    )
}
