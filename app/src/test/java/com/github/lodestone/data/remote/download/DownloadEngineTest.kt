package com.github.lodestone.data.remote.download

import com.github.lodestone.common.io.Checksum
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the checksum contract, which is the only thing standing between a corrupted transfer and
 * a runtime that fails to `dlopen` several minutes later with no explanation.
 */
class DownloadEngineTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val payload = "lodestone".toByteArray()

    private fun engine() = DownloadEngine(
        client = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }),
        parallelism = 1,
    )

    private fun request(checksum: Checksum?, size: Long? = payload.size.toLong()) = DownloadRequest(
        url = "https://example.invalid/artifact.bin",
        destination = File(temporary.newFolder(), "artifact.bin"),
        checksum = checksum,
        size = size,
    )

    @Test
    fun `verifies a sha256 the way it verifies a sha1`() = runTest {
        // `printf lodestone | shasum -a 256`.
        val request = request(
            Checksum.sha256("35afb1fd32c63410aab1a5077f23ac8bd5be52bb001e16ca91d88e88c6a93db3"),
        )

        engine().download(listOf(request))

        assertEquals(payload.toList(), request.destination.readBytes().toList())
    }

    @Test
    fun `still verifies the sha1 mojang publishes`() = runTest {
        // `printf lodestone | shasum -a 1`.
        val request = request(Checksum.sha1("0682f3899aae0e9ef088cc2fe84e6b9c398dfec5"))

        engine().download(listOf(request))

        assertEquals(payload.toList(), request.destination.readBytes().toList())
    }

    @Test
    fun `refuses bytes that do not match the published checksum`() = runTest {
        val request = request(Checksum.sha256("0".repeat(64)))

        val failure = runCatching { engine().download(listOf(request)) }.exceptionOrNull()

        assertEquals(DownloadException::class.java, failure?.javaClass)
        // Nothing is left behind under either name, so the next run cannot adopt bad bytes.
        assertFalse(request.destination.exists())
        assertFalse(File(request.destination.parentFile, "artifact.bin.part").exists())
    }

    @Test
    fun `a size mismatch is a failure even when the checksum is absent`() = runTest {
        val request = request(checksum = null, size = payload.size + 1L)

        val failure = runCatching { engine().download(listOf(request)) }.exceptionOrNull()

        assertEquals(DownloadException::class.java, failure?.javaClass)
        assertFalse(request.destination.exists())
    }
}
