package com.github.lodestone.domain.model.version

/** Supplies raw version manifests by id, from disk or from Mojang. */
fun interface VersionJsonSource {
    suspend fun load(id: String): VersionJson
}

/**
 * Flattens a version and its [VersionJson.inheritsFrom] chain into the single [ResolvedVersion] the
 * installer and launcher work from.
 */
object VersionResolver {

    /** Guards against a manifest that names itself, or a cycle between two mod-loader manifests. */
    private const val MAX_INHERITANCE_DEPTH = 16

    /**
     * The JVM arguments Mojang's launcher supplies implicitly for versions predating the structured
     * `arguments` block. Older manifests simply assume the launcher knows to pass these.
     */
    private val LEGACY_JVM_ARGUMENTS = listOf(
        Argument.of("-Djava.library.path=\${natives_directory}"),
        Argument.of("-Dminecraft.launcher.brand=\${launcher_name}"),
        Argument.of("-Dminecraft.launcher.version=\${launcher_version}"),
        Argument.of("-cp", "\${classpath}"),
    )

    suspend fun resolve(id: String, source: VersionJsonSource): ResolvedVersion =
        merge(loadChain(id, source))

    /** Returns the version and its ancestors, most-derived first. */
    private suspend fun loadChain(id: String, source: VersionJsonSource): List<VersionJson> {
        val chain = mutableListOf<VersionJson>()
        val seen = mutableSetOf<String>()
        var next: String? = id
        while (next != null) {
            check(seen.add(next)) { "Version inheritance cycle at '$next'" }
            check(chain.size < MAX_INHERITANCE_DEPTH) {
                "Version inheritance deeper than $MAX_INHERITANCE_DEPTH from '$id'"
            }
            val version = source.load(next)
            chain += version
            next = version.inheritsFrom
        }
        return chain
    }

    private fun merge(chain: List<VersionJson>): ResolvedVersion {
        val root = chain.first()

        // `firstNotNullOfOrNull` walks most-derived to least, so a mod loader's override wins and
        // anything it leaves out falls through to the vanilla version it inherits from.
        fun <T : Any> inherited(select: (VersionJson) -> T?): T? = chain.firstNotNullOfOrNull(select)

        val mainClass = inherited(VersionJson::mainClass)
            ?: error("No mainClass anywhere in the inheritance chain of '${root.id}'")

        // Libraries accumulate most-derived first; ResolvedVersion resolves duplicates in that
        // order, after rules have been applied.
        val libraries = chain.flatMap(VersionJson::libraries)

        val jarOwner = chain.firstOrNull { it.downloads?.client != null } ?: chain.last()

        return ResolvedVersion(
            id = root.id,
            mainClass = mainClass,
            arguments = mergeArguments(chain),
            assetsId = inherited(VersionJson::assets)
                ?: inherited { it.assetIndex?.id }
                ?: VersionJson.ASSETS_LEGACY,
            assetIndex = inherited(VersionJson::assetIndex),
            javaVersion = inherited(VersionJson::javaVersion) ?: JavaVersionRequirement.DEFAULT,
            clientJar = jarOwner.downloads?.client,
            clientJarVersionId = jarOwner.id,
            libraries = libraries,
            logging = inherited { it.logging?.client },
            type = inherited(VersionJson::type) ?: "release",
            releaseTime = inherited(VersionJson::releaseTime),
        )
    }

    /**
     * Combines each link's arguments, least-derived first so that a mod loader's flags land after
     * the vanilla ones. Handles the pre-1.13 `minecraftArguments` string by splitting it into game
     * arguments and supplying the JVM arguments that format left implicit.
     */
    private fun mergeArguments(chain: List<VersionJson>): Arguments {
        var merged = Arguments()
        for (version in chain.asReversed()) {
            val structured = version.arguments
            val legacy = version.minecraftArguments
            merged = when {
                legacy != null -> {
                    // A legacy manifest replaces the inherited game arguments outright rather than
                    // appending: its template is a complete command line, and appending would pass
                    // the same flags twice.
                    val game = legacy.split(' ').filter(String::isNotEmpty).map { Argument.of(it) }
                    val jvm = merged.jvm.ifEmpty { LEGACY_JVM_ARGUMENTS }
                    Arguments(game = game, jvm = jvm + structured?.jvm.orEmpty())
                }

                structured != null -> merged + structured
                else -> merged
            }
        }
        // A chain made entirely of manifests that specify neither block still has to run.
        return if (merged.jvm.isEmpty()) merged.copy(jvm = LEGACY_JVM_ARGUMENTS) else merged
    }
}
