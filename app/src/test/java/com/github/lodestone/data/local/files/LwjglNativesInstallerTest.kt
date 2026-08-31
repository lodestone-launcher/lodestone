package com.github.lodestone.data.local.files

import com.github.lodestone.domain.model.version.LwjglNativeSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class LwjglNativesInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Stands in for the packaged assets, labelling each library with the set it came from. */
    private fun source(
        missing: Set<String> = emptySet(),
        revision: String = "build-1",
        content: (LwjglNativeSet, String) -> String = { set, name -> "${set.version}/$name" },
    ) = object : LwjglNativesSource {
        override fun open(set: LwjglNativeSet, name: String) =
            if (name in missing) null else ByteArrayInputStream(content(set, name).toByteArray())

        override val revision: String = revision
    }

    private fun natives(): File = temporaryFolder.newFolder("natives")

    private fun apkLibraries(): File = temporaryFolder.newFolder("lib").apply {
        File(this, "libfreetype.so").writeText("freetype")
        File(this, "libopenal.so").writeText("openal")
        // Never copied: the Activity has this one open already, and a second path would be a
        // second library with its own window state.
        File(this, "liblodestone_glfw.so").writeText("shim")
    }

    @Test
    fun `installs the selected set beside the shared libraries`() {
        val target = natives()
        LwjglNativesInstaller.install(LwjglNativeSet.V3_4_1, source(), apkLibraries(), target)

        for (name in LwjglNativeSet.LIBRARIES) {
            assertEquals("3.4.1/$name", File(target, name).readText())
        }
        assertEquals("freetype", File(target, "libfreetype.so").readText())
        assertEquals("openal", File(target, "libopenal.so").readText())
        assertFalse(File(target, "liblodestone_glfw.so").exists())
    }

    @Test
    fun `switching versions replaces every library of the previous set`() {
        val target = natives()
        val apk = apkLibraries()
        LwjglNativesInstaller.install(LwjglNativeSet.V3_3_3, source(), apk, target)
        LwjglNativesInstaller.install(LwjglNativeSet.V3_4_1, source(), apk, target)

        for (name in LwjglNativeSet.LIBRARIES) {
            assertEquals("3.4.1/$name", File(target, name).readText())
        }
    }

    @Test
    fun `a directory already holding the set is left alone`() {
        val target = natives()
        val apk = apkLibraries()
        LwjglNativesInstaller.install(LwjglNativeSet.V3_3_3, source(), apk, target)

        // Nothing should overwrite this, which is what proves the second install did no work.
        val untouched = File(target, "liblwjgl.so").apply { writeText("sentinel") }
        LwjglNativesInstaller.install(LwjglNativeSet.V3_3_3, source(), apk, target)
        assertEquals("sentinel", untouched.readText())
    }

    @Test
    fun `the lwjgl 2 layer gets the relocated opengl bindings under the stock name`() {
        val target = natives()
        val apk = apkLibraries()
        LwjglNativesInstaller.install(LwjglNativeSet.COMPAT2, source(), apk, target, compat2 = true)

        // Same file name, different build: the relocated Java side still asks for `lwjgl_opengl`,
        // and only the asset directory tells the two symbol sets apart.
        assertEquals(
            "3.3.3/${LwjglNativeSet.COMPAT2_OPENGL}",
            File(target, "liblwjgl_opengl.so").readText(),
        )
        assertEquals("3.3.3/liblwjgl.so", File(target, "liblwjgl.so").readText())
    }

    @Test
    fun `a directory holding the stock opengl bindings is reinstalled for the lwjgl 2 layer`() {
        val target = natives()
        val apk = apkLibraries()
        LwjglNativesInstaller.install(LwjglNativeSet.COMPAT2, source(), apk, target)
        LwjglNativesInstaller.install(LwjglNativeSet.COMPAT2, source(), apk, target, compat2 = true)

        // The marker records the same set both times, so only the compat2 half of the stamp can
        // tell the launch that the symbols in there are the wrong ones.
        assertEquals(
            "3.3.3/${LwjglNativeSet.COMPAT2_OPENGL}",
            File(target, "liblwjgl_opengl.so").readText(),
        )
    }

    @Test
    fun `an incomplete set is retried rather than recorded as installed`() {
        val target = natives()
        val apk = apkLibraries()
        LwjglNativesInstaller.install(
            set = LwjglNativeSet.V3_4_1,
            source = source(missing = setOf("liblwjgl_tinyfd.so")),
            nativeLibraryDir = apk,
            target = target,
        )
        assertFalse(File(target, "liblwjgl_tinyfd.so").exists())

        LwjglNativesInstaller.install(LwjglNativeSet.V3_4_1, source(), apk, target)
        assertTrue(LwjglNativeSet.LIBRARIES.all { File(target, it).isFile })
    }

    @Test
    fun `a rebuilt set replaces libraries the previous build of the same version installed`() {
        val target = natives()
        val apk = apkLibraries()
        LwjglNativesInstaller.install(
            LwjglNativeSet.V3_4_1,
            source(revision = "build-1") { _, name -> "old/$name" },
            apk,
            target,
        )
        assertEquals("old/liblwjgl.so", File(target, "liblwjgl.so").readText())

        // Same LWJGL release, same library names, different build of them — which is what shipping
        // a fix to one of our own cross-compiled libraries looks like. The version cannot say that
        // anything changed, so the APK the assets came out of has to.
        LwjglNativesInstaller.install(
            LwjglNativeSet.V3_4_1,
            source(revision = "build-2") { _, name -> "new/$name" },
            apk,
            target,
        )
        for (name in LwjglNativeSet.V3_4_1.libraries) {
            assertEquals("new/$name", File(target, name).readText())
        }
    }

    @Test
    fun `a set that gained libraries is reinstalled rather than reported as current`() {
        val target = natives()
        val apk = apkLibraries()
        LwjglNativesInstaller.install(LwjglNativeSet.V3_4_1, source(), apk, target)

        // Delete one of the Vulkan libraries to stand for a directory installed before the set
        // grew them. The marker still matches, so only checking the files catches this.
        val vulkan = File(target, LwjglNativeSet.VULKAN_LIBRARIES.first())
        assertTrue(vulkan.delete())

        LwjglNativesInstaller.install(LwjglNativeSet.V3_4_1, source(), apk, target)
        assertTrue(vulkan.isFile)
    }
}
