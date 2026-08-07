package com.github.lodestone.domain.model.version

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The platform a version is being resolved for.
 *
 * Lodestone always describes itself as Linux. Android *is* Linux as far as every rule in every
 * version manifest is concerned, and Mojang has never shipped an `android` branch — claiming
 * anything else would select the macOS or Windows native classifiers and leave us with no usable
 * libraries at all.
 */
data class LaunchEnvironment(
    val osName: String = LINUX,
    /** Matched as a regular expression by rules, so it must look like a kernel release string. */
    val osVersion: String,
    /** The value Java reports as `os.arch`: `aarch64` on 64-bit ARM, `amd64` on 64-bit x86. */
    val osArch: String,
    /**
     * Launcher features a version may branch on, such as `has_custom_resolution` or
     * `is_demo_user`. Absent names count as disabled.
     */
    val features: Set<String> = emptySet(),
) {
    companion object {
        const val LINUX = "linux"

        const val FEATURE_DEMO_USER = "is_demo_user"
        const val FEATURE_CUSTOM_RESOLUTION = "has_custom_resolution"
        const val FEATURE_QUICK_PLAY_SUPPORT = "has_quick_plays_support"
        const val FEATURE_QUICK_PLAY_SINGLEPLAYER = "is_quick_play_singleplayer"
        const val FEATURE_QUICK_PLAY_MULTIPLAYER = "is_quick_play_multiplayer"
        const val FEATURE_QUICK_PLAY_REALMS = "is_quick_play_realms"
    }
}

@Serializable
enum class RuleAction {
    @SerialName("allow")
    ALLOW,

    @SerialName("disallow")
    DISALLOW,
}

@Serializable
data class OsConstraint(
    val name: String? = null,
    /** A regular expression, not a literal — Mojang uses patterns like `^10\\.` here. */
    val version: String? = null,
    val arch: String? = null,
)

@Serializable
data class Rule(
    val action: RuleAction = RuleAction.ALLOW,
    val os: OsConstraint? = null,
    val features: Map<String, Boolean>? = null,
) {
    fun matches(environment: LaunchEnvironment): Boolean {
        os?.let { constraint ->
            if (constraint.name != null && !osNameMatches(constraint.name, environment.osName)) {
                return false
            }
            if (constraint.arch != null && constraint.arch != environment.osArch) {
                return false
            }
            if (constraint.version != null) {
                val pattern = runCatching { Regex(constraint.version) }.getOrNull() ?: return false
                if (!pattern.containsMatchIn(environment.osVersion)) {
                    return false
                }
            }
        }
        features?.forEach { (name, required) ->
            if (environment.features.contains(name) != required) {
                return false
            }
        }
        return true
    }

    private fun osNameMatches(ruleName: String, environmentName: String): Boolean =
        canonicalOsName(ruleName) == canonicalOsName(environmentName)
}

/**
 * Applies Mojang's rule semantics: with no rules the subject is allowed, otherwise it starts
 * disallowed and every *matching* rule overwrites the verdict in order, so the last match wins.
 */
fun List<Rule>?.allows(environment: LaunchEnvironment): Boolean {
    if (this.isNullOrEmpty()) {
        return true
    }
    var allowed = false
    for (rule in this) {
        if (rule.matches(environment)) {
            allowed = rule.action == RuleAction.ALLOW
        }
    }
    return allowed
}

/** Mojang has spelled macOS both `osx` and `macos`; rules and native maps use them interchangeably. */
internal fun canonicalOsName(name: String): String = if (name == "macos") "osx" else name
