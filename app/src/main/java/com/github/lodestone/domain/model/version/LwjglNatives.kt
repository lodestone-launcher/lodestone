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
enum class LwjglNativeSet(val version: String, val servesVulkanBackend: Boolean = false) {
    V3_3_3("3.3.3"),
    V3_4_1("3.4.1", servesVulkanBackend = true),
    ;

    /** Where this set sits in the APK, above the per-ABI directory. */
    val assetPath: String get() = "$ASSET_ROOT/$version"

    /**
     * Every library this set is packaged with.
     *
     * Sets that can serve Minecraft's Vulkan backend carry three more. They are listed per set
     * rather than globally because a set that does not carry them is not incomplete — the game it
     * serves has no Vulkan renderer to load them — and the installer treats a library it cannot
     * find as a broken build.
     */
    val libraries: List<String>
        get() = if (servesVulkanBackend) LIBRARIES + VULKAN_LIBRARIES else LIBRARIES

    companion object {
        /** The asset directory the sets are packaged under, one subdirectory per version. */
        const val ASSET_ROOT = "lwjgl"

        /**
         * The set the LWJGL 2 compatibility layer is built on.
         *
         * Pinned rather than following the newest set, because the layer bundles that release's
         * Java bindings and the relocated JNI library beside this set was generated from the same
         * tag. Moving it means rebuilding both together.
         */
        val COMPAT2 = V3_3_3

        /**
         * Where the relocated OpenGL bindings sit inside a set, relative to the ABI directory.
         *
         * The compatibility layer declares `org.lwjgl.opengl.GL11` itself, so the LWJGL 3 class of
         * that name is relocated out of its way and its JNI symbols renamed to match. Same file
         * name as the stock library, because the relocated Java side still asks for `lwjgl_opengl`;
         * only the directory tells them apart.
         */
        const val COMPAT2_OPENGL = "lwjgl2/liblwjgl_opengl.so"

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
         * What Minecraft's Vulkan backend adds on top.
         *
         * `liblwjgl_vma.so` is generated JNI like the four above, and version-coupled with them.
         * The other two are not: shaderc and SPIRV-Cross are upstream projects the bindings reach
         * through libffi by symbol name, and they are packaged per set only because the sets that
         * need them are the sets that carry them.
         *
         * Vulkan itself is absent by design — it is a system library on Android, and the launcher
         * points LWJGL at `libvulkan.so` rather than shipping one.
         */
        val VULKAN_LIBRARIES = listOf(
            "liblwjgl_vma.so",
            SHADERC,
            SPVC,
        )

        /**
         * shaderc and SPIRV-Cross keep the file names their own builds produce.
         *
         * LWJGL looks for plain `shaderc` and `spirv-cross`, but CMake writes each library's
         * SONAME from its target name and appends its own `-Wl,-soname` after anything the build
         * passes, so a renamed file would disagree with the name Android's linker records it
         * under. The launcher points `org.lwjgl.shaderc.libname` and `org.lwjgl.spvc.libname` at
         * these instead, which is the same mechanism the GLFW shim and the GL layer already use.
         */
        const val SHADERC = "libshaderc_shared.so"

        const val SPVC = "libspirv-cross-c-shared.so"

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

    /**
     * The version pins LWJGL 2, for which no arm64 build exists or ever will.
     *
     * Served by the compatibility layer instead: it declares LWJGL 2's API and forwards it to
     * [set], whose relocated OpenGL bindings are installed in place of the stock ones.
     */
    data class Compat2(val requested: String, val set: LwjglNativeSet) : LwjglSelection

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

    // The group id is the whole test, and it is exact rather than a heuristic: every version from
    // a1.2.6 to 1.12.2 pins org.lwjgl.lwjgl:lwjgl, and 1.13 onwards org.lwjgl:lwjgl.
    if (coordinate.group == LWJGL2_GROUP) {
        return LwjglSelection.Compat2(coordinate.version, LwjglNativeSet.COMPAT2)
    }
    // Everything else under org.lwjgl is 3 or later. forVersion clamps within 3.x; a future major
    // lands on the newest set, which is the only one that has grown towards it.
    val set = LwjglNativeSet.forVersion(coordinate.version) ?: LwjglNativeSet.entries.last()
    return LwjglSelection.Packaged(coordinate.version, set)
}

/**
 * The libraries [this] stands in for, which have to come off the classpath.
 *
 * LWJGL 2's own jars are dropped because the layer declares those classes itself and a jar that
 * also declares them would win or lose by classpath order alone. `lwjgl_util` is the exception:
 * 116 classes of pure Java with no natives, colliding with nothing in LWJGL 3, and it supplies the
 * matrix and vector types and GLU for free. jinput is dropped because it is reachable only through
 * `Controllers`, which the layer stubs, and it would otherwise look for natives that do not exist.
 */
fun LibraryArtifact.isSupersededByLwjgl2Compat(): Boolean {
    val coordinate = library.coordinate ?: return false
    return when (coordinate.group) {
        LWJGL2_GROUP -> coordinate.artifact != LWJGL2_UTIL_ARTIFACT
        in JINPUT_GROUPS -> true
        else -> false
    }
}

/**
 * Where the compatibility layer is installed, so that it reads as an ordinary library in a launch
 * log rather than as a path out of nowhere. Versioned by the LWJGL 3 release it is built on.
 */
val LWJGL2_COMPAT_COORDINATE = MavenCoordinate(
    group = "com.github.lodestone",
    artifact = "lwjgl2-compat",
    version = LwjglNativeSet.COMPAT2.version,
)

private const val LWJGL_ARTIFACT = "lwjgl"

private const val LWJGL2_UTIL_ARTIFACT = "lwjgl_util"

/** LWJGL 3 publishes under `org.lwjgl`; LWJGL 2, which pre-1.13 versions pin, under `org.lwjgl.lwjgl`. */
private const val LWJGL2_GROUP = "org.lwjgl.lwjgl"

private val LWJGL_GROUPS = setOf("org.lwjgl", LWJGL2_GROUP)

private val JINPUT_GROUPS = setOf("net.java.jinput", "net.java.jutils")
