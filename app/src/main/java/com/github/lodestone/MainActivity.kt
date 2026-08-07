package com.github.lodestone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.lodestone.ui.home.HomeScreen
import com.github.lodestone.ui.home.HomeViewModel
import com.github.lodestone.ui.theme.LodestoneTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as LodestoneApplication).graph

        setContent {
            LodestoneTheme {
                // Metro resolves the dependency graph, and this factory bridges it to the
                // ViewModelStore so the install survives configuration changes.
                val homeViewModel: HomeViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            HomeViewModel(graph.versionInstaller, graph.buildLaunchSpec) as T
                    },
                )
                HomeScreen(viewModel = homeViewModel)
            }
        }
    }
}
