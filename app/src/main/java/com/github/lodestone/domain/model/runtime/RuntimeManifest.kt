package com.github.lodestone.domain.model.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Java runtimes Lodestone publishes, indexed by feature release and ABI.
 *
 * This is the runtime equivalent of Mojang's version manifest, and is treated the same way: fetched
 * from a fixed URL, cached on disk, and consulted rather than compiled in. A runtime rebuilt for a
 * new JDK update release is then a one-line change to a JSON file rather than an app release.
 */
@Serializable
data class RuntimeManifest(
    @SerialName("formatVersion") val formatVersion: Int = 1,
    @SerialName("runtimes") val runtimes: List<RuntimeDescriptor> = emptyList(),
) {

    fun find(feature: Int, abi: String): RuntimeDescriptor? =
        runtimes.firstOrNull { it.feature == feature && it.abi == abi }

    /** The feature releases published for [abi], for a message that says what *is* available. */
    fun featuresFor(abi: String): List<Int> =
        runtimes.filter { it.abi == abi }.map { it.feature }.sorted()

    companion object {
        /**
         * Served straight from the repository that produces it, so the file the app packages and
         * the file the app fetches are literally the same one and cannot drift apart.
         */
        const val URL =
            "https://raw.githubusercontent.com/lodestone-launcher/lodestone/main/app/src/main/assets/runtimes.json"
    }
}

/** One published runtime archive. [sha256] covers the `.tar.gz` exactly as it is served. */
@Serializable
data class RuntimeDescriptor(
    @SerialName("feature") val feature: Int,
    @SerialName("abi") val abi: String,
    @SerialName("url") val url: String,
    /**
     * The archive's exact length. Optional, because the checksum is what actually decides whether
     * the bytes are good — but without it there is no size to show before the download starts and
     * no way to check the space first, so a published runtime should always carry one.
     */
    @SerialName("size") val size: Long? = null,
    @SerialName("sha256") val sha256: String,
    /**
     * How much the unpacked image occupies, so the free-space check can be made before any bytes
     * are fetched. Absent for a runtime published before this was recorded, in which case it is
     * estimated from the compressed size.
     */
    @SerialName("installedSize") val installedSize: Long? = null,
    @SerialName("javaVersion") val javaVersion: String? = null,
) {

    /** Null rather than zero, so an unpublished size cannot be mistaken for an empty archive. */
    val archiveSize: Long? get() = size?.takeIf { it > 0 }

    /**
     * A gzipped JDK image unpacks to roughly three times its archive, which is the multiplier used
     * when the real figure was not published. Over-estimating only costs a refusal on a device that
     * was nearly full anyway.
     */
    val unpackedSize: Long?
        get() = installedSize ?: archiveSize?.times(ESTIMATED_EXPANSION)

    private companion object {
        const val ESTIMATED_EXPANSION = 3
    }
}
