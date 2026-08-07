package com.github.lodestone.domain.model.version

import kotlinx.serialization.Serializable

/**
 * An asset index: a flat map from a logical resource name such as `minecraft/sounds/music/menu.ogg`
 * to the hash of its content.
 */
@Serializable
data class AssetIndex(
    val objects: Map<String, AssetObject> = emptyMap(),
    /**
     * Set on the `pre-1.6` index. When true the game reads assets from the game directory by their
     * logical names rather than from the hashed object store.
     */
    @Suppress("PropertyName")
    val map_to_resources: Boolean = false,
    /** Set on the `legacy` index, meaning the objects must also exist under their logical names. */
    val virtual: Boolean = false,
) {
    val totalSize: Long get() = objects.values.sumOf(AssetObject::size)
}

@Serializable
data class AssetObject(
    val hash: String,
    val size: Long = 0,
) {
    /**
     * Objects live under a directory named after the first two characters of their hash, which is
     * how Mojang keeps any one directory from holding every asset in the game.
     */
    val objectPath: String get() = "${hash.take(2)}/$hash"

    val url: String get() = "$OBJECTS_BASE_URL$objectPath"

    companion object {
        const val OBJECTS_BASE_URL = "https://resources.download.minecraft.net/"
    }
}
