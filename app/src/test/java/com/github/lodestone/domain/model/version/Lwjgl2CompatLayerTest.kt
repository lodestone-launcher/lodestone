package com.github.lodestone.domain.model.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the parts of the LWJGL 2 layer that are split across a build script, a Gradle file and
 * generated sources, and would otherwise only disagree on a device.
 */
class Lwjgl2CompatLayerTest {

    @Test
    fun `the relocation prefix is the same on the Java side and the native side`() {
        // The Java classes are relocated by Gradle and the JNI symbols by a sed in the native build.
        // JNI resolves a symbol from the class's own name, so the two are one decision: if they
        // drift, every GL call fails with an UnsatisfiedLinkError at the first frame.
        val gradle = PACKAGE_IN_GRADLE.find(file("app/build.gradle.kts").readText())
        val native = PACKAGE_IN_SHELL.find(file("runtime/build-lwjgl.sh").readText())
        assertEquals(
            "the relocation prefix differs between the Gradle and the native build",
            gradle?.groupValues?.get(1),
            native?.groupValues?.get(1),
        )
        assertEquals("com.github.lodestone.lwjgl3", gradle?.groupValues?.get(1))
    }

    @Test
    fun `the capabilities deny the occlusion query extension`() {
        // gl4es exports glBeginQuery and never increments the sample counter, so every occlusion
        // query answers "nothing was drawn" and Minecraft's culling would render an empty world.
        // Every version that issues one reads this flag first, so denying it is the whole fix.
        val source = file("app/src/lwjgl2/java/org/lwjgl/opengl/ContextCapabilities.java").readText()
        assertTrue(
            "ContextCapabilities no longer denies GL_ARB_occlusion_query",
            source.contains("capabilities.GL_ARB_occlusion_query = false;"),
        )
    }

    @Test
    fun `no generated binding reaches LWJGL 3 through the package it collides with`() {
        // The layer declares org.lwjgl.opengl.GL11 itself, so a forward written against the
        // unrelocated name would resolve back to the layer and recurse until the stack ran out.
        val generated = file("app/src/lwjgl2/java/org/lwjgl/opengl").listFiles().orEmpty()
            .filter { it.name.endsWith(".java") }
        assertTrue("no generated sources were found", generated.isNotEmpty())
        for (source in generated) {
            val offending = source.readLines()
                .filter { it.contains(".opengl.") && !it.contains("com.github.lodestone.lwjgl3") }
            assertEquals("${source.name} names an unrelocated package", emptyList<String>(), offending)
        }
    }

    /** Unit tests run from the module directory, and two of these files are above it. */
    private fun file(path: String): File = File(File("").absoluteFile.parentFile, path)

    private companion object {
        val PACKAGE_IN_GRADLE = Regex("""val lwjgl2Package = "([\w.]+)"""")
        val PACKAGE_IN_SHELL = Regex("""LWJGL2_PACKAGE="([\w.]+)"""")
    }
}
