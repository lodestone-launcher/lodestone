package com.github.lodestone.domain.model.version

/**
 * A Maven coordinate in the `group:artifact:version[:classifier][@extension]` form used throughout
 * Minecraft version manifests.
 *
 * Mod loaders routinely omit the `downloads` block and expect the launcher to derive a repository
 * path from the coordinate alone, so the parsing here has to match Maven's layout exactly.
 */
data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar",
) {
    /** The repository-relative path, e.g. `org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1-natives-linux.jar`. */
    val path: String
        get() = buildString {
            append(group.replace('.', '/'))
            append('/').append(artifact)
            append('/').append(version)
            append('/').append(artifact).append('-').append(version)
            if (classifier != null) {
                append('-').append(classifier)
            }
            append('.').append(extension)
        }

    /** The same coordinate with a classifier applied, used to locate native-library artifacts. */
    fun withClassifier(classifier: String?): MavenCoordinate = copy(classifier = classifier)

    /** Identity for classpath de-duplication: same artifact and role, regardless of version. */
    val moduleKey: String get() = "$group:$artifact:${classifier.orEmpty()}"

    override fun toString(): String = buildString {
        append(group).append(':').append(artifact).append(':').append(version)
        if (classifier != null) {
            append(':').append(classifier)
        }
        if (extension != "jar") {
            append('@').append(extension)
        }
    }

    companion object {
        /** Returns null rather than throwing, so one malformed entry cannot fail a whole install. */
        fun parseOrNull(coordinate: String): MavenCoordinate? {
            val atIndex = coordinate.lastIndexOf('@')
            val extension: String
            val withoutExtension: String
            if (atIndex > 0 && !coordinate.substring(atIndex + 1).contains(':')) {
                extension = coordinate.substring(atIndex + 1)
                withoutExtension = coordinate.substring(0, atIndex)
            } else {
                extension = "jar"
                withoutExtension = coordinate
            }

            val parts = withoutExtension.split(':')
            if (parts.size < 3 || parts.any(String::isEmpty)) {
                return null
            }
            return MavenCoordinate(
                group = parts[0],
                artifact = parts[1],
                version = parts[2],
                classifier = parts.getOrNull(3),
                extension = extension,
            )
        }
    }
}
