package com.github.lodestone.domain.model.launch

import com.github.lodestone.common.LodestoneJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A [LaunchSpec] flattened for the trip into the `:game` process.
 *
 * The game runs in its own process, so the spec cannot simply be handed over in memory. Everything
 * here is a plain string: an Intent extra would work for the small fields, but the classpath alone
 * routinely exceeds the Binder transaction limit, so the whole request goes through a file instead.
 */
@Serializable
data class LaunchRequest(
    val versionId: String,
    val mainClass: String,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val libjvmPath: String,
    /** Becomes the process working directory, which is what the game resolves relative paths on. */
    val gameDirectory: String,
    /** Opened by the game Activity before the VM starts; null when there is no layer to open. */
    val translationLayerPath: String? = null,
    val environment: Map<String, String>,
) {
    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(LodestoneJson.encodeToString(serializer(), this))
    }

    companion object {
        fun from(spec: LaunchSpec): LaunchRequest = LaunchRequest(
            versionId = spec.versionId,
            mainClass = spec.mainClass,
            jvmArgs = spec.jvmArgs,
            gameArgs = spec.gameArgs,
            libjvmPath = spec.libjvm.absolutePath,
            gameDirectory = spec.gameDirectory.absolutePath,
            translationLayerPath = spec.translationLayer?.absolutePath,
            environment = spec.environment,
        )

        fun readFrom(file: File): LaunchRequest? = runCatching {
            LodestoneJson.decodeFromString(serializer(), file.readText())
        }.getOrNull()
    }
}
