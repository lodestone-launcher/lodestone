package com.github.lodestone.data.local.settings

import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.domain.model.controls.ControlLayout
import timber.log.Timber
import java.io.File

/**
 * Where a player's arrangement of the on-screen controls is kept.
 *
 * A file of its own rather than a preference beside the others, and the reason is the process
 * split. The launcher UI and the game run in separate processes, and DataStore does not support
 * being opened in two — it keeps an in-memory copy per process and neither learns of the other's
 * writes. This layout is arranged in the game process and read in the game process, so a plain
 * file keeps it honest and leaves the door open for the launcher to show it later without either
 * side silently overwriting the other.
 *
 * Every failure here falls back to the default arrangement. A layout is a convenience; there is no
 * version of "cannot read the file" that is worth refusing to draw any controls over.
 */
class ControlLayoutStore(private val file: File) {

    fun load(): ControlLayout {
        if (!file.isFile) {
            return ControlLayout.Default
        }
        return runCatching {
            LodestoneJson.decodeFromString(ControlLayout.serializer(), file.readText())
        }.onFailure {
            Timber.w(it, "Could not read the control layout; using the default")
        }.getOrDefault(ControlLayout.Default)
            // Filled in rather than taken as written, so a layout arranged before a control existed
            // still gets it.
            .completed()
    }

    fun save(layout: ControlLayout) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(LodestoneJson.encodeToString(ControlLayout.serializer(), layout))
        }.onFailure { Timber.w(it, "Could not save the control layout") }
    }

    companion object {
        const val FILE_NAME = "touch_layout.json"
    }
}
