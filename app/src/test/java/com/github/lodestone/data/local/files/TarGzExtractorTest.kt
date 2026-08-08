package com.github.lodestone.data.local.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Reads archives produced by the system `tar`, rather than by a writer of our own.
 *
 * The point of the extractor is to understand what GNU tar actually emits for a JDK image, so a
 * test that round-trips through our own encoder would prove nothing about the case that matters.
 */
class TarGzExtractorTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `unpacks a tree the way tar built it`() {
        val source = temporary.newFolder("jdk")
        write(File(source, "release"), "JAVA_VERSION=\"17\"")
        write(File(source, "lib/server/libjvm.so"), "not really a jvm")
        write(File(source, "legal/java.base/LICENSE"), "GPLv2")
        File(source, "legal/java.sql").mkdirs()
        // The runtime images carry two hundred of these, all relative and all pointing at a sibling
        // module's copy of the same licence.
        Files.createSymbolicLink(
            File(source, "legal/java.sql/LICENSE").toPath(),
            File("../java.base/LICENSE").toPath(),
        )

        val archive = archive(source)
        val target = temporary.newFolder("runtime")

        val written = TarGzExtractor.extract(archive, target, stripComponents = 1)

        assertTrue("nothing was extracted", written > 0)
        assertEquals("JAVA_VERSION=\"17\"", File(target, "release").readText())
        assertTrue("libjvm is missing", File(target, "lib/server/libjvm.so").isFile)
        assertEquals("GPLv2", File(target, "legal/java.base/LICENSE").readText())
        // Followed rather than merely present: a link that resolves nowhere is as useless to the
        // linker as no link at all.
        assertEquals("GPLv2", File(target, "legal/java.sql/LICENSE").readText())
    }

    @Test
    fun `refuses entries that would escape the target`() {
        val source = temporary.newFolder("payload")
        write(File(source, "harmless"), "fine")
        val archive = temporary.newFile("escape.tar.gz")
        // `-P` is what lets tar store the `..` at all; without it the path is rewritten on the way
        // in and there is nothing to defend against.
        run("tar", "-czPf", archive.absolutePath, "-C", source.absolutePath, "harmless", "../payload/harmless")

        val target = temporary.newFolder("out")
        TarGzExtractor.extract(archive, target, stripComponents = 0)

        assertTrue(File(target, "harmless").isFile)
        // The `..` entry names the same file a second time, so the only way to tell it was refused
        // rather than merely overwritten is that nothing landed outside the target at all.
        val written = target.walkTopDown().filter(File::isFile).toList()
        assertEquals("an entry escaped the target", listOf(File(target, "harmless")), written)
        assertFalse(File(target.parentFile, "harmless").exists())
    }

    private fun archive(source: File): File {
        val archive = temporary.newFile("${source.name}.tar.gz")
        val parent = checkNotNull(source.parentFile).absolutePath
        run("tar", "-czf", archive.absolutePath, "-C", parent, source.name)
        return archive
    }

    private fun run(vararg command: String) {
        val builder = ProcessBuilder(*command).redirectErrorStream(true)
        // macOS `tar` otherwise stores an AppleDouble `._name` beside every entry, which is a
        // property of the machine running the test rather than of anything being tested.
        builder.environment()["COPYFILE_DISABLE"] = "1"
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        // A machine without tar cannot run these, which is a reason to skip rather than to fail.
        assumeTrue("tar failed: $output", process.waitFor() == 0)
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
