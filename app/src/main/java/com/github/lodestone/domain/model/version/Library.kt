package com.github.lodestone.domain.model.version

import kotlinx.serialization.Serializable

/** Mojang's own repository, and the implied base for any library that carries no explicit URL. */
const val MOJANG_LIBRARIES_URL = "https://libraries.minecraft.net/"

@Serializable
data class Library(
    val name: String,
    val downloads: LibraryDownloads? = null,
    /**
     * Maps an OS name to the classifier holding that platform's native jar. Present only in the
     * pre-1.19 layout. Values may contain `${arch}`, which expands to the JVM's word size.
     */
    val natives: Map<String, String>? = null,
    val extract: ExtractRule? = null,
    val rules: List<Rule>? = null,
    /**
     * A Maven repository root. Mod loaders use this in place of a `downloads` block, expecting the
     * launcher to append the coordinate's path to it.
     */
    val url: String? = null,
) {
    val coordinate: MavenCoordinate? get() = MavenCoordinate.parseOrNull(name)

    fun isAllowed(environment: LaunchEnvironment): Boolean = rules.allows(environment)

    /**
     * The classifier of this library's native jar for [environment], or null if it has none.
     *
     * Only the legacy layout reaches this. Modern manifests express the same thing as separate
     * library entries whose coordinate already carries the classifier, which [artifactsFor] detects
     * from the coordinate instead.
     */
    fun nativeClassifier(environment: LaunchEnvironment): String? {
        val declared = natives?.get(canonicalOsName(environment.osName)) ?: return null
        return declared.replace(ARCH_PLACEHOLDER, environment.wordSize())
    }

    /**
     * Every file this library contributes for [environment], already labelled as classpath or
     * native content.
     *
     * Returns an empty list when the library's rules exclude it. A library can contribute both a
     * classpath jar and a native jar: LWJGL 3 in the legacy layout does exactly that.
     */
    fun artifactsFor(environment: LaunchEnvironment): List<LibraryArtifact> {
        if (!isAllowed(environment)) {
            return emptyList()
        }
        val coordinate = coordinate ?: return emptyList()
        val result = mutableListOf<LibraryArtifact>()

        val classifier = nativeClassifier(environment)
        // In the legacy layout a library that declares `natives` contributes a classpath jar only
        // if one is actually listed; LWJGL 2 ships native-only entries.
        if (classifier == null || downloads?.artifact != null) {
            val mainArtifact = downloads?.artifact ?: syntheticArtifact(coordinate)
            result += LibraryArtifact(
                library = this,
                artifact = mainArtifact,
                path = mainArtifact.path ?: coordinate.path,
                kind = if (classifier != null) LibraryArtifactKind.CLASSPATH else kindOf(coordinate),
            )
        }

        if (classifier != null) {
            val nativeCoordinate = coordinate.withClassifier(classifier)
            val nativeArtifact = downloads?.classifiers?.get(classifier)
                ?: syntheticArtifact(nativeCoordinate)
            result += LibraryArtifact(
                library = this,
                artifact = nativeArtifact,
                path = nativeArtifact.path ?: nativeCoordinate.path,
                kind = LibraryArtifactKind.NATIVE,
            )
        }
        return result
    }

    /**
     * Builds an artifact for a library that has no `downloads` block by resolving its coordinate
     * against [url], falling back to Mojang's repository.
     */
    private fun syntheticArtifact(coordinate: MavenCoordinate): Artifact {
        val base = (url ?: MOJANG_LIBRARIES_URL).let { if (it.endsWith('/')) it else "$it/" }
        return Artifact(path = coordinate.path, url = base + coordinate.path)
    }

    /**
     * Classifies a modern-layout entry. Since 1.19 the native jars are ordinary libraries whose
     * coordinate ends in a `natives-…` classifier, and they must be unpacked rather than put on the
     * classpath.
     */
    private fun kindOf(coordinate: MavenCoordinate): LibraryArtifactKind =
        if (coordinate.classifier?.startsWith("natives-") == true) {
            LibraryArtifactKind.NATIVE
        } else {
            LibraryArtifactKind.CLASSPATH
        }

    private fun LaunchEnvironment.wordSize(): String =
        if (osArch in setOf("x86", "arm32", "arm")) "32" else "64"

    private companion object {
        const val ARCH_PLACEHOLDER = "\${arch}"
    }
}

enum class LibraryArtifactKind {
    /** Goes on the JVM classpath. */
    CLASSPATH,

    /** A jar of shared objects to unpack into the natives directory. */
    NATIVE,
}

data class LibraryArtifact(
    val library: Library,
    val artifact: Artifact,
    /** Path relative to the shared `libraries/` directory. */
    val path: String,
    val kind: LibraryArtifactKind,
)
