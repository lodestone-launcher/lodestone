package com.github.lodestone.domain.model.launch

import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.domain.model.version.GraphicsBackend
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
    /**
     * Which backend the game will drive, as [GraphicsBackend.name].
     *
     * The activity needs this before the VM starts, and needs it as more than "are there
     * renderers": a Vulkan launch must not have EGL brought up underneath it, and an OpenGL launch
     * with nothing packaged must not start at all.
     */
    val graphicsBackend: String = GraphicsBackend.OPENGL.name,
    /** The library LWJGL's OpenGL bootstrap loads, whichever backend actually renders. */
    val openglLibraryPath: String? = null,
    val environment: Map<String, String>,
) {
    /** [graphicsBackend] as the enum, falling back to OpenGL for a request this build cannot read. */
    val backend: GraphicsBackend
        get() = GraphicsBackend.entries.firstOrNull { it.name == graphicsBackend }
            ?: GraphicsBackend.OPENGL

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
            graphicsBackend = spec.graphicsBackend.name,
            openglLibraryPath = spec.openglLibrary?.absolutePath,
            renderers = spec.renderers.map { candidate ->
                RendererChoice(
                    id = candidate.renderer.id,
                    layerPath = candidate.layer.absolutePath,
                )
            },
            environment = spec.environment,
        )

        fun readFrom(file: File): LaunchRequest? = runCatching {
            LodestoneJson.decodeFromString(serializer(), file.readText())
        }.getOrNull()
    }
}
