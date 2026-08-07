package com.github.lodestone.data.local.files

import java.io.File

/**
 * The on-disk layout of a Minecraft installation.
 *
 * This mirrors the official launcher's directory structure exactly. That is not nostalgia: mod
 * loaders, mod managers and the game itself all hard-code these relative paths, and a launcher that
 * invents its own layout cannot run Forge or import an existing instance.
 *
 * @param root the game directory, which becomes `${game_directory}` at launch.
 */
class GameFiles(val root: File) {

    val versions: File get() = File(root, "versions")
    val libraries: File get() = File(root, "libraries")
    val assets: File get() = File(root, "assets")
    val assetIndexes: File get() = File(assets, "indexes")
    val assetObjects: File get() = File(assets, "objects")

    /**
     * Where `legacy` assets are materialised under their logical names. The game reads from here
     * directly, so the files have to exist as real paths rather than as hashed objects.
     */
    val assetsVirtual: File get() = File(assets, "virtual")

    /** Where `pre-1.6` assets go: the game looks for `resources/` beside its working directory. */
    val resources: File get() = File(root, "resources")

    val logConfigs: File get() = File(root, "log_configs")

    /** Java runtimes, one directory per `javaVersion.component`, as the official launcher does. */
    val runtimes: File get() = File(root, "runtimes")

    fun versionDirectory(id: String): File = File(versions, id)

    fun versionJson(id: String): File = File(versionDirectory(id), "$id.json")

    fun versionJar(id: String): File = File(versionDirectory(id), "$id.jar")

    /**
     * Natives are unpacked per version rather than shared. Two versions can want different builds
     * of the same LWJGL shared object, and a shared directory would leave whichever launched last
     * to overwrite the other.
     */
    fun nativesDirectory(id: String): File = File(versionDirectory(id), "natives")

    fun library(path: String): File = File(libraries, path)

    fun assetObject(objectPath: String): File = File(assetObjects, objectPath)

    fun assetIndex(id: String): File = File(assetIndexes, "$id.json")

    fun virtualAssets(indexId: String): File = File(assetsVirtual, indexId)

    fun runtimeDirectory(component: String): File = File(runtimes, component)

    /** Creates the directories that must exist before an install starts. */
    fun prepare() {
        listOf(versions, libraries, assetIndexes, assetObjects, logConfigs, runtimes)
            .forEach(File::mkdirs)
    }
}
