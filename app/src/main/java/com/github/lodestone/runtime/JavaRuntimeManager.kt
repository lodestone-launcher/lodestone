package com.github.lodestone.runtime

import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.domain.model.launch.Renderer
import com.github.lodestone.domain.model.launch.RendererCandidate
import com.github.lodestone.domain.model.version.JavaVersionRequirement
import timber.log.Timber
import java.io.File

/**
 * Finds and unpacks the Java runtime a version asks for.
 *
 * Minecraft names its runtime through `javaVersion.component`, and Lodestone ships one cross-built
 * runtime per feature release rather than per component: several components map onto the same JDK,
 * and a few name releases that are out of support.
 */
class JavaRuntimeManager(private val files: GameFiles) {

    /**
     * The feature release that satisfies [requirement].
     *
     * `java-runtime-alpha` asks for Java 16, which is long out of support and which nothing in the
     * game actually depends on; 17 runs those versions unchanged, so it is substituted rather than
     * cross-compiled separately.
     */
    fun featureFor(requirement: JavaVersionRequirement): Int = when {
        requirement.majorVersion <= 8 -> 8
        requirement.majorVersion == 16 -> 17
        else -> requirement.majorVersion
    }

    fun runtimeRoot(feature: Int): File = files.runtimeDirectory("java-$feature")

    /**
     * The `libjvm.so` the bridge opens, or null when the runtime is absent or incomplete.
     *
     * HotSpot lives under `lib/server/` on modern releases and `lib/<arch>/server/` on 8, so both
     * layouts are probed rather than assuming one.
     */
    fun libjvm(feature: Int): File? {
        val root = runtimeRoot(feature)
        if (!root.isDirectory) {
            return null
        }
        val candidates = listOf(
            File(root, "lib/server/libjvm.so"),
            File(root, "lib/${abiDirectory()}/server/libjvm.so"),
            File(root, "jre/lib/${abiDirectory()}/server/libjvm.so"),
        )
        return candidates.firstOrNull(File::isFile)
    }

    fun isInstalled(requirement: JavaVersionRequirement): Boolean =
        libjvm(featureFor(requirement)) != null

    /**
     * Restores the executable and readable bits on a freshly unpacked runtime.
     *
     * The runtime tarballs are stored without permissions, because Android's extraction path does
     * not preserve them reliably. Everything under `bin/` has to be executable for the launcher's
     * fallback path, and every shared object has to be readable for `dlopen`.
     */
    fun applyPermissions(feature: Int) {
        val root = runtimeRoot(feature)
        if (!root.isDirectory) {
            return
        }
        root.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
                return@forEach
            }
            file.setReadable(true, false)
            val isExecutable = file.parentFile?.name == "bin" ||
                file.name.endsWith(".so") ||
                file.name.contains(".so.")
            if (isExecutable) {
                file.setExecutable(true, false)
            }
        }
        Timber.i("Applied runtime permissions for Java %d", feature)
    }

    /**
     * The environment the hosted VM needs before it starts.
     *
     * `JAVA_HOME` is read by the JDK's own bootstrap, and `LD_LIBRARY_PATH` lets the runtime's
     * libraries resolve each other — the natives directory comes first so that our GLFW shim and
     * translation layer win over anything of the same name inside the runtime.
     *
     * The library directories come from [libraryDirectories] rather than being spelled out, because
     * 8 puts none of them where the modern releases do.
     */
    fun environmentFor(feature: Int, nativesDirectory: File): Map<String, String> {
        val root = runtimeRoot(feature)
        val libraryPath = (listOf(nativesDirectory) + libraryDirectories(root))
            .filter(File::isDirectory)
            .joinToString(":") { it.absolutePath }

        return mapOf(
            "JAVA_HOME" to root.absolutePath,
            "LD_LIBRARY_PATH" to libraryPath,
            // bionic has no locale database, so anything reading these finds a sane default rather
            // than falling back to ASCII and mangling non-Latin text.
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
        )
    }

    /**
     * The system properties that point LWJGL at our shims instead of the desktop libraries it would
     * normally unpack from its own jars.
     *
     * This is the whole reason no LWJGL patching is needed: its `opengl` and `glfw` modules are pure
     * Java that `dlopen`s whatever these name.
     */
    fun lwjglProperties(
        nativesDirectory: File,
        shimDirectory: File,
    ): List<String> = buildList {
        // LWJGL unpacks its own bundled natives unless told where ours already are. Emitted here and
        // nowhere else: the argument builder used to add it too, and two of them in one command line
        // meant the effective value was whichever the VM happened to parse last.
        add("-Dorg.lwjgl.librarypath=${nativesDirectory.absolutePath}")
        // Named where the APK unpacked them, which is where the Activity loaded the GLFW shim from.
        // The linker treats two paths to the same library as two libraries, and the shim's window
        // state has to be the one the Activity is feeding the surface into.
        add("-Dorg.lwjgl.glfw.libname=${File(shimDirectory, "liblodestone_glfw.so").absolutePath}")
        // `org.lwjgl.opengl.libname` is deliberately not set here. Which translation layer serves
        // the game is only decided in the game process, once one of them has actually come up, so
        // the Activity appends it for whichever won.
        // jemalloc is not cross-compiled: LWJGL falls back to the platform allocator, and bionic's
        // is a scudo/jemalloc hybrid already.
        add("-Dorg.lwjgl.system.allocator=system")
        // LWJGL probes for a debug console and stack traces it cannot get here.
        add("-Dorg.lwjgl.util.NoChecks=true")
    }

    /**
     * The renderers [renderer] asks for that are actually packaged, best first.
     *
     * Resolved against the APK's native library directory rather than the version's natives, so
     * that adding Zink's libraries to the build is enough to make it selectable. Being packaged is
     * only the first hurdle: whether a renderer *works* is settled later, when the shim tries to
     * bring its EGL up on this device's driver.
     */
    fun rendererCandidates(shimDirectory: File, renderer: Renderer): List<RendererCandidate> =
        renderer.chain.mapNotNull { candidate ->
            val layer = candidate.libraryName?.let { File(shimDirectory, it) } ?: return@mapNotNull null
            if (!layer.isFile) {
                return@mapNotNull null
            }
            RendererCandidate(
                renderer = candidate,
                layer = layer,
                eglLibrary = candidate.eglLibraryName
                    ?.let { File(shimDirectory, it) }
                    ?.takeIf(File::isFile),
            )
        }

    /**
     * Every directory of the runtime's own shared libraries, across both layouts.
     *
     * The same split [libjvm] probes: modern releases keep them under `lib/`, 8 under
     * `jre/lib/<arch>/`. Listing both rather than picking one keeps this from having to know which
     * feature release it was handed, and the caller drops the paths that do not exist.
     */
    private fun libraryDirectories(root: File): List<File> {
        val arch = abiDirectory()
        return listOf(
            "lib",
            "lib/server",
            "lib/jli",
            "jre/lib/$arch",
            "jre/lib/$arch/server",
            "jre/lib/$arch/jli",
        ).map { File(root, it) }
    }

    private fun abiDirectory(): String =
        when (val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "amd64"
            else -> abi ?: "aarch64"
        }
}
