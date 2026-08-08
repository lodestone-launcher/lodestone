package com.github.lodestone.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.lodestone.domain.model.account.AccountType
import com.github.lodestone.domain.model.account.MinecraftAccount

/** Who the launcher can play as, and which of them it will play as next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var offlineNamePrompt by rememberSaveable { mutableStateOf(false) }
    var signOutPrompt by remember { mutableStateOf<MinecraftAccount?>(null) }

    if (offlineNamePrompt) {
        OfflineNameDialog(
            onConfirm = {
                viewModel.addOffline(it)
                offlineNamePrompt = false
            },
            onDismiss = { offlineNamePrompt = false },
        )
    }

    signOutPrompt?.let { account ->
        SignOutDialog(
            account = account,
            onConfirm = {
                viewModel.signOut(account.uuid)
                signOutPrompt = null
            },
            onDismiss = { signOutPrompt = null },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = onSignIn) { Text("Add Microsoft account") }
                if (state.canAddOffline) {
                    OutlinedButton(
                        onClick = { offlineNamePrompt = true },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Add offline") }
                }
            }

            HorizontalDivider()

            if (state.accounts.isEmpty()) {
                Text(
                    text = "No accounts yet. Sign in with the Microsoft account that owns " +
                        "Minecraft: Java Edition to play.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.accounts, key = MinecraftAccount::uuid) { account ->
                        AccountRow(
                            account = account,
                            isActive = account.uuid == state.activeUuid,
                            onSelect = { viewModel.select(account.uuid) },
                            onSignOut = { signOutPrompt = account },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: MinecraftAccount,
    isActive: Boolean,
    onSelect: () -> Unit,
    onSignOut: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onSelect),
        headlineContent = { Text(account.username) },
        supportingContent = {
            Text(
                when (account.type) {
                    AccountType.MICROSOFT -> "Microsoft"
                    AccountType.OFFLINE -> "Offline — cannot join online servers"
                },
            )
        },
        leadingContent = { RadioButton(selected = isActive, onClick = onSelect) },
        trailingContent = {
            IconButton(onClick = onSignOut) {
                Icon(Icons.Default.Delete, contentDescription = "Sign out ${account.username}")
            }
        },
    )
}

/**
 * Confirms before signing out, because there is nothing to undo: the tokens are deleted rather
 * than deselected, so coming back means another trip through Microsoft.
 */
@Composable
private fun SignOutDialog(account: MinecraftAccount, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out ${account.username}?") },
        text = { Text("The saved session is deleted from this device. Nothing on the account itself changes.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OfflineNameDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("Player") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an offline account") },
        text = {
            Column {
                Text("Offline accounts have no Mojang session, so multiplayer will refuse them.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Player name") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
