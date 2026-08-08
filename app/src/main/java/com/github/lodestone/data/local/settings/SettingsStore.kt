package com.github.lodestone.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.lodestone.domain.model.launch.Renderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The choices the person using the launcher has made, as opposed to what a version manifest asks
 * for.
 *
 * DataStore rather than a file of our own — unlike the account store, these are small independent
 * values that are read one at a time and want no encryption, which is the case DataStore is for.
 */
class SettingsStore(private val preferences: DataStore<Preferences>) {

    /** How OpenGL calls reach the device, defaulting to letting the launcher work it out. */
    val renderer: Flow<Renderer> = preferences.data.map { stored ->
        val id = stored[RENDERER]
        Renderer.entries.firstOrNull { it.id == id } ?: Renderer.AUTO
    }

    suspend fun setRenderer(renderer: Renderer) {
        preferences.edit { it[RENDERER] = renderer.id }
    }

    private companion object {
        /**
         * Stored by id rather than by ordinal.
         *
         * The ordinal would silently repoint at a different renderer the moment anyone reorders the
         * enum, and the symptom — someone's saved choice quietly becoming another one — would not
         * look like a storage bug.
         */
        val RENDERER = stringPreferencesKey("renderer")
    }
}
