package com.github.lodestone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.lodestone.data.local.settings.SettingsStore
import com.github.lodestone.domain.model.launch.Renderer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsStore) : ViewModel() {

    val renderer: StateFlow<Renderer> = settings.renderer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Renderer.AUTO)

    fun selectRenderer(renderer: Renderer) {
        viewModelScope.launch { settings.setRenderer(renderer) }
    }
}
