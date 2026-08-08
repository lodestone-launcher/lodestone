package com.github.lodestone.domain.model.runtime

import com.github.lodestone.common.LodestoneJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the manifest the app packages, which is the same file the published URL serves.
 *
 * A runtime is only ever added by editing this JSON, so the mistakes worth catching are the ones a
 * human makes there: a truncated checksum, a size left at zero, a URL that does not name the
 * archive it claims to.
 */
class RuntimeManifestTest {

    private val bundled: RuntimeManifest = LodestoneJson.decodeFromString(
        RuntimeManifest.serializer(),
        File("src/main/assets/runtimes.json").readText(),
    )

    @Test
    fun `every published runtime is fully described`() {
        assertTrue("no runtimes are published", bundled.runtimes.isNotEmpty())
        for (runtime in bundled.runtimes) {
            val name = "java-${runtime.feature} ${runtime.abi}"
            assertTrue("$name has no size", (runtime.archiveSize ?: 0) > 0)
            assertTrue("$name has no unpacked size", (runtime.unpackedSize ?: 0) > 0)
            assertEquals("$name has a malformed sha256", 64, runtime.sha256.length)
            assertTrue(
                "$name has a non-hex sha256",
                runtime.sha256.all { it.isDigit() || it in 'a'..'f' },
            )
            assertTrue("$name is not served over https", runtime.url.startsWith("https://"))
            // The archive name carries the feature and ABI, so a copy-paste that leaves the wrong
            // URL behind is visible without downloading anything.
            assertTrue(
                "$name points at ${runtime.url}",
                runtime.url.endsWith("lodestone-jre${runtime.feature}-${runtime.abi}.tar.gz"),
            )
        }
    }

    @Test
    fun `a runtime is looked up by feature and abi together`() {
        val arm = bundled.featuresFor("arm64-v8a")
        assertTrue("nothing is published for arm64-v8a", arm.isNotEmpty())
        assertEquals(arm.sorted(), arm)

        val feature = arm.first()
        assertNotNull(bundled.find(feature, "arm64-v8a"))
        // A runtime for another ABI must never be offered as a substitute: it would download and
        // unpack cleanly and then fail at dlopen, which is far harder to diagnose.
        assertNull(bundled.find(feature, "x86_64"))
    }

    @Test
    fun `an unpublished runtime is absent rather than empty`() {
        // Only the releases Minecraft asks for are built, so asking for a feature nobody publishes
        // has to be answerable without a crash.
        assertNull(bundled.find(feature = 6, abi = "arm64-v8a"))
    }
}
