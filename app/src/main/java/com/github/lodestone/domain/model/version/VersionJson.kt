package com.github.lodestone.domain.model.version

import kotlinx.serialization.Serializable

/**
 * A version manifest, exactly as published or as written by a mod loader.
 *
 * Almost every field is optional because this one type has to describe seventeen years of format
 * drift, and because a mod-loader manifest carries only its own overrides and defers the rest to
 * the version named by [inheritsFrom]. Use [VersionResolver] to flatten that chain before reading
 * anything here for launch.
 */
@Serializable
data class VersionJson(
    val id: String,
    /** The parent version whose fields this one overrides; set by Forge, Fabric, Quilt and NeoForge. */
    val inheritsFrom: String? = null,
    val type: String? = null,
    val time: String? = null,
    val releaseTime: String? = null,
    val minimumLauncherVersion: Int = 0,
    val complianceLevel: Int = 0,
    val mainClass: String? = null,
    /**
     * The pre-1.13 game arguments: a single space-separated template string. Mutually exclusive
     * with [arguments] in practice, though a mod loader may set this while its parent sets the other.
     */
    val minecraftArguments: String? = null,
    val arguments: Arguments? = null,
    /**
     * The asset index name. Two values are special: `pre-1.6`, where objects are copied into the
     * game directory under their real names, and `legacy`, where they are laid out in a shared
     * `assets/virtual/legacy` tree.
     */
    val assets: String? = null,
    val assetIndex: AssetIndexReference? = null,
    val javaVersion: JavaVersionRequirement? = null,
    val downloads: VersionDownloads? = null,
    val libraries: List<Library> = emptyList(),
    val logging: Logging? = null,
) {
    companion object {
        const val ASSETS_PRE_1_6 = "pre-1.6"
        const val ASSETS_LEGACY = "legacy"
    }
}

/**
 * A version with its inheritance chain already flattened, so every field needed to launch is
 * present. Produced by [VersionResolver.resolve].
 */
data class ResolvedVersion(
    val id: String,
    val mainClass: String,
    val arguments: Arguments,
    val assetsId: String,
    val assetIndex: AssetIndexReference?,
    val javaVersion: JavaVersionRequirement,
    val clientJar: Artifact?,
    /**
     * The version whose `client.jar` is actually downloaded and put on the classpath. Mod-loader
     * manifests inherit the jar from their parent rather than shipping one.
     */
    val clientJarVersionId: String,
    val libraries: List<Library>,
    val logging: LoggingConfiguration?,
    val type: String,
    val releaseTime: String?,
) {
    /** True when assets are addressed by their original names instead of by hash. */
    val usesLegacyAssetLayout: Boolean
        get() = assetsId == VersionJson.ASSETS_PRE_1_6 || assetsId == VersionJson.ASSETS_LEGACY

    /** True when assets must be copied into `<gameDir>/resources` rather than a shared virtual tree. */
    val mapsAssetsToResources: Boolean
        get() = assetsId == VersionJson.ASSETS_PRE_1_6

    /**
     * The classpath entries for [environment], most-derived first and de-duplicated by module so a
     * mod loader's pinned version of a shared library wins over the vanilla one.
     */
    fun classpathLibraries(environment: LaunchEnvironment): List<LibraryArtifact> {
        val seen = mutableSetOf<String>()
        return libraries
            .flatMap { it.artifactsFor(environment) }
            .filter { it.kind == LibraryArtifactKind.CLASSPATH }
            .filter { seen.add(it.library.coordinate?.moduleKey ?: it.path) }
    }

    /** The native jars to unpack for [environment], de-duplicated the same way. */
    fun nativeLibraries(environment: LaunchEnvironment): List<LibraryArtifact> {
        val seen = mutableSetOf<String>()
        return libraries
            .flatMap { it.artifactsFor(environment) }
            .filter { it.kind == LibraryArtifactKind.NATIVE }
            .filter { seen.add(it.library.coordinate?.moduleKey ?: it.path) }
    }
}
