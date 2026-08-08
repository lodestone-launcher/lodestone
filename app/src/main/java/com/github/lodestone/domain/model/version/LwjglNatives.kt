package com.github.lodestone.domain.model.version

/**
 * A set of LWJGL native libraries packaged with the app, listed oldest first.
 *
 * LWJGL's Java classes and its JNI libraries are generated together from one release and bind only
 * to each other, so a single set cannot serve every version: 26.2 moved to 3.4.1, whose `LibFFI`
 * declares an `ffi_get_closure_size` that 3.3.3's `liblwjgl.so` never exported, and the launch died
 * at the first `GLFWErrorCallback.create`. Lodestone therefore ships one set per LWJGL release the
 * supported versions pin, and [forVersion] decides which one a manifest gets.
 */
enum class LwjglNativeSet(val version: String) {
    V3_3_3("3.3.3"),
    V3_4_1("3.4.1"),
    ;

    /** Where this set sits in the APK, above the per-ABI directory. */
    val assetPath: String get() = "$ASSET_ROOT/$version"

    companion object {
        /** The asset directory the sets are packaged under, one subdirectory per version. */
        const val ASSET_ROOT = "lwjgl"

        /**
         * The libraries that make up a set.
         *
         * Only these four are generated from the bindings of one exact release, which is what makes
         * them version-coupled. `libfreetype.so` and `libopenal.so` also come out of LWJGL's natives
         * jars, but they are upstream projects the bindings reach through libffi by symbol name
         * rather than through generated JNI stubs, so one build of each serves every set and they
         * are packaged once. There is no `liblwjgl_freetype.so` or `liblwjgl_openal.so` — read back
         * off the published `-natives-linux` jars, those two modules ship no stub library at all.
         */
        val LIBRARIES = listOf(
            "liblwjgl.so",
            "liblwjgl_opengl.so",
            "liblwjgl_stb.so",
            "liblwjgl_tinyfd.so",
        )

        /**
         * The set to serve a version that asks for [version], or null when nothing here can.
         *
         * Only an exact match is correct, and an inexact one is unsafe in both directions: 3.4.1
         * adds `ffi_get_closure_size` and at the same time drops 125 of the `JNI.call*` overloads
         * 3.3.3's Java side binds to, so neither set is a superset of the other. What is left is a
         * best effort, and it clamps rather than fails — the newest set that is not newer than
         * [version], or the oldest when [version] predates all of them. A future 3.5.x therefore
         * lands on the newest set we ship, which is the only one that has grown towards it, instead
         * of on an ancient one or on a launch that refuses to start. The real fix in that case is to
         * add the set.
         *
         * LWJGL 2 returns null instead. Its natives are laid out differently and come from a
         * different coordinate entirely, so there is nothing here to clamp onto and pretending
         * otherwise would hand a pre-1.13 version libraries it cannot load.
         */
        fun forVersion(version: String): LwjglNativeSet? {
            val requested = componentsOf(version)
            if (requested.firstOrNull() != 3) {
                return null
            }
            return entries.lastOrNull { compare(componentsOf(it.version), requested) <= 0 }
                ?: entries.first()
        }

        /**
         * `3.4.1` becomes `[3, 4, 1]`.
         *
         * A qualifier ends the parse rather than failing it, so the nightly builds 1.6.4 pins
         * (`2.9.1-nightly-20130708-debug3`) still compare as the release they were cut from.
         */
        private fun componentsOf(version: String): List<Int> =
            version.split('.')
                .map { part -> part.takeWhile(Char::isDigit) }
                .takeWhile(String::isNotEmpty)
                .map(String::toInt)

        private fun compare(left: List<Int>, right: List<Int>): Int {
            for (index in 0 until maxOf(left.size, right.size)) {
                val order = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
                if (order != 0) {
                    return order
                }
            }
            return 0
        }
    }
}

/** What a version's manifest asks of the packaged LWJGL sets. */
sealed interface LwjglSelection {

    /** [set] will be installed for a version whose manifest pins [requested]. */
    data class Packaged(val requested: String, val set: LwjglNativeSet) : LwjglSelection {
        /** False when [set] is a clamped best effort rather than the release the version pins. */
        val isExact: Boolean get() = requested == set.version
    }

    /** The version pins LWJGL 2, which none of the packaged sets can stand in for. */
    data class Unsupported(val requested: String) : LwjglSelection

    /** The manifest names no LWJGL at all, so there is nothing to install. */
    data object Absent : LwjglSelection
}

/**
 * The LWJGL set this version needs, read from the `org.lwjgl:lwjgl` coordinate its manifest pins.
 *
 * Taken from the resolved libraries rather than by re-reading the manifest, so a mod loader's
 * override and the `inheritsFrom` chain have already been flattened, and rules have already dropped
 * the entries meant for other platforms — 1.16.5 lists 3.2.1 for macOS beside 3.2.2 for everything
 * else, and only the second is on the classpath here. Any classifier will do: 26.2 has no plain
 * `org.lwjgl:lwjgl:3.4.1` entry at all, only `:unsafe` and the `natives-*` ones.
 */
fun ResolvedVersion.lwjglSelection(environment: LaunchEnvironment): LwjglSelection {
    val coordinate = libraries.asSequence()
        .filter { it.isAllowed(environment) }
        .mapNotNull(Library::coordinate)
        .firstOrNull { it.artifact == LWJGL_ARTIFACT && it.group in LWJGL_GROUPS }
        ?: return LwjglSelection.Absent

    return LwjglNativeSet.forVersion(coordinate.version)
        ?.let { LwjglSelection.Packaged(coordinate.version, it) }
        ?: LwjglSelection.Unsupported(coordinate.version)
}

private const val LWJGL_ARTIFACT = "lwjgl"

/** LWJGL 3 publishes under `org.lwjgl`; LWJGL 2, which pre-1.13 versions pin, under `org.lwjgl.lwjgl`. */
private val LWJGL_GROUPS = setOf("org.lwjgl", "org.lwjgl.lwjgl")
