package com.github.lodestone.data.repository

import com.github.lodestone.common.io.HashAlgorithm
import com.github.lodestone.common.io.Hashing
import com.github.lodestone.data.local.files.GameFiles
import com.github.lodestone.data.remote.download.DownloadEngine
import com.github.lodestone.domain.model.runtime.RuntimeManifest
import com.github.lodestone.runtime.JavaRuntimeManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives the whole runtime install against a served archive: manifest, download, checksum, unpack,
 * rename and the libjvm check that decides whether any of it worked.
 *
 * The archive is a real gzipped tar built by the system `tar`, laid out like a JDK image, so the
 * only things this cannot speak for are the network and the device.
 */
class RuntimeInstallerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var files: GameFiles
    private lateinit var runtimes: JavaRuntimeManager
    private lateinit var archive: File
    private var served = 0

    @Before
    fun setUp() {
        files = GameFiles(temporary.newFolder("minecraft"))
        runtimes = JavaRuntimeManager(files)
        archive = buildArchive()
        served = 0
    }

    @Test
    fun `fetches unpacks and keeps a runtime`() = runTest {
        val installer = installer()

        assertTrue("nothing was installed", installer.ensureInstalled(FEATURE))

        val root = runtimes.runtimeRoot(FEATURE)
        assertEquals(File(root, "lib/server/libjvm.so"), runtimes.libjvm(FEATURE))
        assertEquals("hotspot", File(root, "lib/server/libjvm.so").readText())
        assertTrue("bin/java was not made executable", File(root, "bin/java").canExecute())
        // The image's own top-level directory must not survive: `runtimes/java-N` is the JAVA_HOME.
        assertFalse(File(root, "jdk").exists())
        // Two hundred megabytes of archive are not worth keeping once the runtime is unpacked.
        assertFalse(File(files.runtimes, archive.name).exists())
        assertFalse(File(files.runtimes, "java-$FEATURE.incomplete").exists())
    }

    @Test
    fun `an installed runtime is not fetched again`() = runTest {
        val installer = installer()
        installer.ensureInstalled(FEATURE)
        val downloads = served

        assertFalse("the runtime was fetched twice", installer.ensureInstalled(FEATURE))
        assertEquals(downloads, served)
    }

    @Test
    fun `a half-unpacked runtime is discarded rather than adopted`() = runTest {
        // Exactly what a process killed mid-unpack leaves behind: a staging directory holding some
        // of a runtime, with no way to tell how much.
        val staging = File(files.runtimes, "java-$FEATURE.incomplete")
        File(staging, "lib/server").mkdirs()
        File(staging, "lib/server/libjvm.so").writeText("truncated")

        assertTrue(installer().ensureInstalled(FEATURE))

        assertEquals("hotspot", File(runtimes.runtimeRoot(FEATURE), "lib/server/libjvm.so").readText())
        assertFalse(staging.exists())
    }

    @Test
    fun `an unpublished runtime is refused with a message naming what exists`() = runTest {
        val failure = runCatching { installer().ensureInstalled(feature = 8) }.exceptionOrNull()

        assertEquals(RuntimeUnavailableException::class.java, failure?.javaClass)
        val message = failure?.message.orEmpty()
        assertTrue(message, message.contains("No Java 8 runtime"))
        assertTrue(message, message.contains("$FEATURE"))
        assertFalse(runtimes.runtimeRoot(8).exists())
    }

    @Test
    fun `refuses before downloading when the runtime will not fit`() = runTest {
        val installer = installer(installedSize = Long.MAX_VALUE / 4)

        val failure = runCatching { installer.ensureInstalled(FEATURE) }.exceptionOrNull()

        assertEquals(RuntimeUnavailableException::class.java, failure?.javaClass)
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("free"))
        // The point of checking first is that nothing is spent finding out.
        assertEquals(0, served)
    }

    @Test
    fun `falls back to the packaged manifest when the published one cannot be reached`() = runTest {
        val installer = installer(manifestReachable = false)

        assertTrue(installer.ensureInstalled(FEATURE))
        assertEquals(File(runtimes.runtimeRoot(FEATURE), "lib/server/libjvm.so"), runtimes.libjvm(FEATURE))
    }

    // ---------------------------------------------------------------------------------------------

    private fun installer(
        manifestReachable: Boolean = true,
        installedSize: Long = 1024,
    ): RuntimeInstaller {
        val manifest = manifest(installedSize)
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.toString() == RuntimeManifest.URL ->
                        if (manifestReachable) {
                            respond(manifest, HttpStatusCode.OK)
                        } else {
                            respondError(HttpStatusCode.ServiceUnavailable)
                        }

                    request.url.toString() == ARCHIVE_URL -> {
                        served++
                        respond(archive.readBytes(), HttpStatusCode.OK)
                    }

                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
        )
        return RuntimeInstaller(
            client = client,
            downloads = DownloadEngine(client, parallelism = 1),
            files = files,
            runtimes = runtimes,
            bundledManifest = { manifest.byteInputStream() },
            abi = ABI,
        )
    }

    private fun manifest(installedSize: Long): String = """
        {
          "formatVersion": 1,
          "runtimes": [
            {
              "feature": $FEATURE,
              "abi": "$ABI",
              "url": "$ARCHIVE_URL",
              "size": ${archive.length()},
              "installedSize": $installedSize,
              "sha256": "${Hashing.hash(archive, HashAlgorithm.SHA256)}"
            }
          ]
        }
    """.trimIndent()

    /** A miniature JDK image: the paths the launcher actually looks for, wrapped in `jdk/`. */
    private fun buildArchive(): File {
        val image = temporary.newFolder("image")
        val jdk = File(image, "jdk")
        write(File(jdk, "release"), "JAVA_VERSION=\"$FEATURE\"")
        write(File(jdk, "bin/java"), "#!/bin/sh")
        write(File(jdk, "lib/server/libjvm.so"), "hotspot")

        val archive = File(temporary.newFolder("published"), "lodestone-jre$FEATURE-$ABI.tar.gz")
        val builder = ProcessBuilder("tar", "-czf", archive.absolutePath, "-C", image.absolutePath, "jdk")
            .redirectErrorStream(true)
        builder.environment()["COPYFILE_DISABLE"] = "1"
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assumeTrue("tar failed: $output", process.waitFor() == 0)
        return archive
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private companion object {
        const val FEATURE = 21
        const val ABI = "arm64-v8a"
        const val ARCHIVE_URL = "https://example.invalid/lodestone-jre21-arm64-v8a.tar.gz"
    }
}
