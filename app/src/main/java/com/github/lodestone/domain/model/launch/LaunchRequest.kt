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
/** A [RendererCandidate] as it crosses the process boundary. */
@Serializable
data class RendererChoice(
    /** The [Renderer]'s id, carried so the log names what was tried rather than a bare path. */
    val id: String,
    val layerPath: String,
    val eglLibraryPath: String? = null,
)

@Serializable
data class LaunchRequest(
    val versionId: String,
    val mainClass: String,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val libjvmPath: String,
    /** Becomes the process working directory, which is what the game resolves relative paths on. */
    val gameDirectory: String,
    /**
     * The renderers the game Activity tries, best first, before the VM starts.
     *
     * Empty when there is no layer to open at all.
     */
    val renderers: List<RendererChoice> = emptyList(),
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
            renderers = spec.renderers.map { candidate ->
                RendererChoice(
                    id = candidate.renderer.id,
                    layerPath = candidate.layer.absolutePath,
                    eglLibraryPath = candidate.eglLibrary?.absolutePath,
                )
            },
            environment = spec.environment,
        )

        fun readFrom(file: File): LaunchRequest? = runCatching {
            LodestoneJson.decodeFromString(serializer(), file.readText())
        }.getOrNull()
    }
}
