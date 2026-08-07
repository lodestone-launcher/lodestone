package com.github.lodestone.domain.model.version

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One downloadable file. `sha1` and `size` are optional because mod-loader manifests frequently
 * omit them; the download engine verifies whatever is present and settles for a size check, or for
 * mere existence, when it is not.
 */
@Serializable
data class Artifact(
    /** Repository-relative destination. Absent on `downloads.client`, which is placed by version. */
    val path: String? = null,
    val sha1: String? = null,
    val size: Long? = null,
    val url: String? = null,
)

@Serializable
data class LibraryDownloads(
    val artifact: Artifact? = null,
    /**
     * Pre-1.19 manifests keep native jars here, keyed by the classifier that `Library.natives`
     * maps an OS name to. Modern manifests dropped this in favour of one library entry per
     * platform with an ordinary `artifact`.
     */
    val classifiers: Map<String, Artifact>? = null,
)

/** Files excluded when unpacking a native jar — always at least `META-INF/`. */
@Serializable
data class ExtractRule(
    val exclude: List<String> = emptyList(),
) {
    fun excludes(entryName: String): Boolean = exclude.any(entryName::startsWith)
}

@Serializable
data class AssetIndexReference(
    val id: String,
    val sha1: String? = null,
    val size: Long? = null,
    /** Total bytes of every object in the index, used to size the download progress bar. */
    val totalSize: Long? = null,
    val url: String? = null,
)

/**
 * The runtime a version needs. `component` names a Mojang runtime bundle (`jre-legacy`,
 * `java-runtime-gamma`, `java-runtime-delta`, `java-runtime-epsilon`, …); `majorVersion` is the
 * feature release. Versions older than 1.17 omit this block entirely and imply Java 8.
 */
@Serializable
data class JavaVersionRequirement(
    val component: String = LEGACY_COMPONENT,
    val majorVersion: Int = 8,
) {
    companion object {
        const val LEGACY_COMPONENT = "jre-legacy"
        val DEFAULT = JavaVersionRequirement()
    }
}

@Serializable
data class LoggingFile(
    val id: String,
    val sha1: String? = null,
    val size: Long? = null,
    val url: String? = null,
)

@Serializable
data class LoggingConfiguration(
    /** A JVM argument template containing `${path}`, e.g. `-Dlog4j.configurationFile=${path}`. */
    val argument: String,
    val file: LoggingFile,
    val type: String? = null,
)

@Serializable
data class Logging(
    val client: LoggingConfiguration? = null,
)

@Serializable
data class VersionDownloads(
    val client: Artifact? = null,
    val server: Artifact? = null,
    @SerialName("client_mappings") val clientMappings: Artifact? = null,
    @SerialName("server_mappings") val serverMappings: Artifact? = null,
)
