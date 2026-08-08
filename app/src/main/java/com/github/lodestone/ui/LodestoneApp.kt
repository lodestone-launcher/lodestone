package com.github.lodestone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.github.lodestone.di.AppGraph
import com.github.lodestone.ui.accounts.AccountsScreen
import com.github.lodestone.ui.accounts.AccountsViewModel
import com.github.lodestone.ui.home.HomeScreen
import com.github.lodestone.ui.home.HomeViewModel
import com.github.lodestone.ui.signin.SignInScreen
import com.github.lodestone.ui.signin.SignInViewModel
import kotlinx.serialization.Serializable

@Serializable
private data object HomeRoute : NavKey

@Serializable
private data object AccountsRoute : NavKey

@Serializable
private data object SignInRoute : NavKey

/**
 * The launcher's screens and the paths between them.
 *
 * Every entry gets its own view model store, so leaving sign-in clears its state rather than
 * leaving a half-finished flow to be found again on the next visit.
 */
@Composable
fun LodestoneApp(graph: AppGraph, modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(HomeRoute)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<HomeRoute> {
                HomeScreen(
                    viewModel = graphViewModel { HomeViewModel(
                        graph.versionInstaller,
                        graph.runtimeInstaller,
                        graph.buildLaunchSpec,
                        graph.accounts,
                    ) },
                    onOpenAccounts = { backStack.add(AccountsRoute) },
                )
            }

            entry<AccountsRoute> {
                AccountsScreen(
                    viewModel = graphViewModel { AccountsViewModel(graph.accounts) },
                    onSignIn = { backStack.add(SignInRoute) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }

            entry<SignInRoute> {
                SignInScreen(
                    viewModel = graphViewModel { SignInViewModel(graph.microsoftAuth, graph.accounts) },
                    // Straight back to the account list, where the new account is now selected.
                    onSignedIn = { backStack.removeLastOrNull() },
                    onClose = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

/**
 * Builds a view model from the Metro graph and hands it to the store of whichever entry is showing.
 *
 * Metro resolves dependencies at compile time and knows nothing about `ViewModelStore`, so this is
 * the seam between the two: construction stays explicit, and the instance still survives a
 * configuration change.
 */
@Composable
private inline fun <reified T : ViewModel> graphViewModel(crossinline create: () -> T): T =
    viewModel(factory = viewModelFactory { initializer { create() } })
