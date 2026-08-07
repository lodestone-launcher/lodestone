package com.github.lodestone.domain.model.version

import kotlinx.serialization.Serializable

/** `version_manifest_v2.json`: the index of every version Mojang publishes. */
@Serializable
data class VersionManifest(
    val latest: LatestVersions = LatestVersions(),
    val versions: List<VersionEntry> = emptyList(),
) {
    fun find(id: String): VersionEntry? = versions.firstOrNull { it.id == id }

    companion object {
        const val URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    }
}

@Serializable
data class LatestVersions(
    val release: String? = null,
    val snapshot: String? = null,
)

@Serializable
data class VersionEntry(
    val id: String,
    val type: String,
    val url: String,
    val time: String? = null,
    val releaseTime: String? = null,
    /** SHA-1 of the version JSON at [url]; v2 of the manifest added it so responses can be cached. */
    val sha1: String? = null,
    val complianceLevel: Int = 0,
) {
    val channel: VersionChannel get() = VersionChannel.of(type)
}

enum class VersionChannel(val id: String, val label: String) {
    RELEASE("release", "Releases"),
    SNAPSHOT("snapshot", "Snapshots"),
    OLD_BETA("old_beta", "Betas"),
    OLD_ALPHA("old_alpha", "Alphas"),
    UNKNOWN("unknown", "Other");

    companion object {
        fun of(type: String): VersionChannel = entries.firstOrNull { it.id == type } ?: UNKNOWN
    }
}
