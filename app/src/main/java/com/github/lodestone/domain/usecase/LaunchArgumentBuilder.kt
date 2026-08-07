package com.github.lodestone.domain.usecase

import com.github.lodestone.domain.model.account.AccountType
import com.github.lodestone.domain.model.account.MinecraftAccount
import com.github.lodestone.domain.model.launch.LaunchOptions
import com.github.lodestone.domain.model.version.Argument
import com.github.lodestone.domain.model.version.LaunchEnvironment
import com.github.lodestone.domain.model.version.ResolvedVersion
import com.github.lodestone.domain.model.version.VersionJson
import java.io.File

/**
 * Turns a resolved version's argument templates into a concrete command line.
 *
 * Minecraft's manifests describe arguments as templates full of `${...}` placeholders. Substituting
 * them is the whole of "how do I launch this version" — every format difference across seventeen
 * years of releases has already been normalised away by [com.github.lodestone.domain.model.version.VersionResolver]
 * by the time the arguments reach here.
 */
class LaunchArgumentBuilder(
    private val launcherName: String = "lodestone",
    private val launcherVersion: String = "1.0",
) {

    data class Paths(
        val gameDirectory: File,
        val assetsRoot: File,
        /** Where legacy assets were materialised, if this version needs them. */
        val virtualAssets: File?,
        val nativesDirectory: File,
        val librariesDirectory: File,
        val classpath: List<File>,
        /** The downloaded log4j configuration, when the version publishes one. */
        val loggingConfig: File?,
    )

    /**
     * Builds the JVM arguments: the version's own, then logging, then memory and user overrides.
     *
     * Order matters. The user's extra arguments go last so that a flag they set deliberately wins
     * over the defaults, and the heap sizing precedes them so it can be overridden too.
     */
    fun buildJvmArgs(
        version: ResolvedVersion,
        environment: LaunchEnvironment,
        paths: Paths,
        options: LaunchOptions,
    ): List<String> {
        val substitutions = jvmSubstitutions(version, paths)
        val arguments = mutableListOf<String>()

        version.arguments.jvm
            .filter { it.isAllowed(environment) }
            .flatMap(Argument::values)
            .mapTo(arguments) { substitute(it, substitutions) }

        version.logging?.let { logging ->
            val configFile = paths.loggingConfig
            if (configFile != null && configFile.isFile) {
                arguments += logging.argument.replace("\${path}", configFile.absolutePath)
            }
        }

        arguments += "-Xmx${options.maxMemoryMb}M"
        arguments += "-Xms${options.minMemoryMb}M"

        // bionic has no locale database, so the JVM's charset detection finds nothing to work with
        // and would otherwise fall back to ASCII, mangling every non-Latin character in the game.
        arguments += "-Dfile.encoding=UTF-8"
        arguments += "-Dsun.jnu.encoding=UTF-8"
        // Android has no writable system temp directory the JVM can discover on its own.
        arguments += "-Djava.io.tmpdir=${File(paths.gameDirectory, "tmp").absolutePath}"
        arguments += "-Duser.home=${paths.gameDirectory.absolutePath}"
        // The game is always the foreground app here, so there is no windowing system to talk to
        // beyond the surface the shim owns.
        arguments += "-Djava.awt.headless=true"
        // LWJGL unpacks its own bundled natives unless told where ours already are.
        arguments += "-Dorg.lwjgl.librarypath=${paths.nativesDirectory.absolutePath}"

        arguments += options.extraJvmArgs
        return arguments
    }

    /** Builds the game arguments, including the account details the session depends on. */
    fun buildGameArgs(
        version: ResolvedVersion,
        environment: LaunchEnvironment,
        paths: Paths,
        account: MinecraftAccount,
        options: LaunchOptions,
    ): List<String> {
        val substitutions = gameSubstitutions(version, paths, account, options)
        return version.arguments.game
            .filter { it.isAllowed(environment) }
            .flatMap(Argument::values)
            .map { substitute(it, substitutions) }
    }

    /**
     * The features a launch enables, which drive the `features` rules in modern manifests.
     *
     * A feature that is absent counts as disabled, so this only ever lists what is actually on.
     */
    fun features(options: LaunchOptions): Set<String> = buildSet {
        if (options.demo) {
            add(LaunchEnvironment.FEATURE_DEMO_USER)
        }
        if (options.resolutionWidth != null && options.resolutionHeight != null) {
            add(LaunchEnvironment.FEATURE_CUSTOM_RESOLUTION)
        }
    }

    private fun jvmSubstitutions(version: ResolvedVersion, paths: Paths): Map<String, String> =
        mapOf(
            "natives_directory" to paths.nativesDirectory.absolutePath,
            "launcher_name" to launcherName,
            "launcher_version" to launcherVersion,
            "classpath" to paths.classpath.joinToString(File.pathSeparator) { it.absolutePath },
            "classpath_separator" to File.pathSeparator,
            // Forge's manifests reference the libraries root directly to build their module path.
            "library_directory" to paths.librariesDirectory.absolutePath,
            "version_name" to version.id,
        )

    private fun gameSubstitutions(
        version: ResolvedVersion,
        paths: Paths,
        account: MinecraftAccount,
        options: LaunchOptions,
    ): Map<String, String> {
        // Pre-1.6 versions read their assets straight out of the game directory, and versions up to
        // 1.7 want the virtual tree rather than the hashed object store.
        val gameAssets = when {
            version.mapsAssetsToResources -> File(paths.gameDirectory, "resources")
            version.usesLegacyAssetLayout -> paths.virtualAssets ?: paths.assetsRoot
            else -> paths.assetsRoot
        }

        return buildMap {
            put("auth_player_name", account.username)
            put("version_name", version.id)
            put("game_directory", paths.gameDirectory.absolutePath)
            put("assets_root", paths.assetsRoot.absolutePath)
            put("game_assets", gameAssets.absolutePath)
            put("assets_index_name", version.assetsId)
            put("auth_uuid", account.uuid)
            put("auth_access_token", account.accessToken)
            put("auth_xuid", account.xuid)
            put("user_type", account.userType)
            put("version_type", version.type)
            // The 1.6-era `--session` argument, which predates separate token and uuid arguments.
            put("auth_session", legacySession(account))
            // A launcher-instance id. The official launcher sends a per-install UUID; ours is
            // per-account so that it stays stable without identifying the device.
            put("clientid", account.uuid)
            // Removed from the game long ago, but 1.7-era versions still interpolate it and would
            // otherwise be handed the literal placeholder.
            put("user_properties", "{}")

            options.resolutionWidth?.let { put("resolution_width", it.toString()) }
            options.resolutionHeight?.let { put("resolution_height", it.toString()) }
        }
    }

    /**
     * The pre-1.6 session token format. Offline accounts have no token, and the game accepts the
     * literal `-` there as "not signed in".
     */
    private fun legacySession(account: MinecraftAccount): String =
        if (account.type == AccountType.OFFLINE) "-" else "token:${account.accessToken}:${account.uuid}"

    /**
     * Replaces every `${name}` for which a value is known.
     *
     * Unknown placeholders are deliberately left as-is rather than blanked. A version referencing
     * something we do not implement produces a visible `${quickPlayPath}` in the log, which is far
     * easier to diagnose than an argument that silently became an empty string.
     */
    private fun substitute(template: String, values: Map<String, String>): String {
        if (!template.contains("\${")) {
            return template
        }
        return PLACEHOLDER.replace(template) { match ->
            values[match.groupValues[1]] ?: match.value
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""\$\{([A-Za-z0-9_]+)}""")
    }
}

/** Extension point used by the installer to decide whether a version needs a virtual asset tree. */
val ResolvedVersion.needsVirtualAssets: Boolean
    get() = assetsId == VersionJson.ASSETS_LEGACY
