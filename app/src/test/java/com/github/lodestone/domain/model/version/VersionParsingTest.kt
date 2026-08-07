package com.github.lodestone.domain.model.version

import com.github.lodestone.common.LodestoneJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses real published manifests spanning every format Mojang has shipped. These are the actual
 * files served by piston-meta, so a regression here is a regression against production data.
 */
class VersionParsingTest {

    private val android = LaunchEnvironment(osVersion = "5.15.0", osArch = "aarch64")

    private fun fixture(id: String): VersionJson {
        val stream = checkNotNull(javaClass.getResourceAsStream("/versions/$id.json")) {
            "missing fixture for $id"
        }
        return LodestoneJson.decodeFromString(VersionJson.serializer(), stream.reader().readText())
    }

    private suspend fun resolve(id: String): ResolvedVersion =
        VersionResolver.resolve(id, VersionJsonSource(::fixture))

    @Test
    fun `parses every published manifest format`() {
        for (id in ALL_FIXTURES) {
            val version = fixture(id)
            assertEquals(id, version.id)
            assertNotNull("$id has no mainClass", version.mainClass)
            assertTrue("$id has no libraries", version.libraries.isNotEmpty())
        }
    }

    @Test
    fun `legacy manifests expose their argument template as game arguments`() = runTest {
        val resolved = resolve("1.7.10")
        val game = resolved.arguments.game.flatMap(Argument::values)
        assertTrue(game.containsAll(listOf("--username", "\${auth_player_name}")))
        assertTrue(game.contains("--assetIndex"))
        // Nothing in a pre-1.13 manifest describes the JVM side, so the resolver supplies it.
        val jvm = resolved.arguments.jvm.flatMap(Argument::values)
        assertTrue(jvm.contains("-cp"))
        assertTrue(jvm.contains("\${classpath}"))
        assertTrue(jvm.any { it.startsWith("-Djava.library.path=") })
    }

    @Test
    fun `structured manifests keep their jvm arguments`() = runTest {
        val resolved = resolve("1.21.4")
        assertTrue(resolved.arguments.jvm.flatMap(Argument::values).contains("\${classpath}"))
        assertTrue(resolved.arguments.game.flatMap(Argument::values).contains("--accessToken"))
    }

    @Test
    fun `rules select the linux branch and drop the mac and windows ones`() {
        val allowed = fixture("1.21.4").libraries.filter { it.isAllowed(android) }.map(Library::name)
        assertTrue(allowed.any { it.contains("natives-linux") })
        assertTrue(allowed.none { it.contains("natives-windows") })
        assertTrue(allowed.none { it.contains("natives-macos") })
        // The macOS-only Objective-C bridge must not survive onto an Android classpath.
        assertTrue(allowed.none { it.startsWith("ca.weblite:java-objc-bridge") })
    }

    @Test
    fun `legacy native libraries resolve through the classifier map`() {
        val lwjglPlatform = fixture("1.7.10").libraries.single {
            it.name.startsWith("org.lwjgl.lwjgl:lwjgl-platform:") && it.isAllowed(android)
        }
        assertEquals("natives-linux", lwjglPlatform.nativeClassifier(android))

        val artifacts = lwjglPlatform.artifactsFor(android)
        // LWJGL 2's platform artifact is native-only: no classpath jar is listed for it.
        assertEquals(1, artifacts.size)
        assertEquals(LibraryArtifactKind.NATIVE, artifacts.single().kind)
        assertTrue(artifacts.single().path.endsWith("-natives-linux.jar"))
    }

    @Test
    fun `modern native libraries are classified by their coordinate`() = runTest {
        val resolved = resolve("26.2")
        val natives = resolved.nativeLibraries(android)
        assertTrue(natives.isNotEmpty())
        assertTrue(natives.all { it.path.contains("natives-linux") })

        // `org.lwjgl:lwjgl:3.4.1:unsafe` carries a classifier but is a normal classpath jar.
        val classpath = resolved.classpathLibraries(android)
        assertTrue(classpath.any { it.library.name.endsWith(":unsafe") })
        assertTrue(classpath.none { it.path.contains("natives-") })
    }

    @Test
    fun `classpath entries are de-duplicated per module`() = runTest {
        // 1.16.5 lists LWJGL 3.2.1 for macOS and 3.2.2 elsewhere; only one may reach the classpath.
        val classpath = resolve("1.16.5").classpathLibraries(android)
        val lwjglCore = classpath.filter { it.library.name.startsWith("org.lwjgl:lwjgl:") }
        assertEquals(1, lwjglCore.size)
        assertTrue(lwjglCore.single().library.name.contains("3.2.2"))
    }

    @Test
    fun `versions without a javaVersion block fall back to java 8`() = runTest {
        assertEquals(null, fixture("1.6.4").javaVersion)
        val resolved = resolve("1.6.4")
        assertEquals(8, resolved.javaVersion.majorVersion)
        assertEquals(JavaVersionRequirement.LEGACY_COMPONENT, resolved.javaVersion.component)
    }

    @Test
    fun `java runtime requirements are read across the range`() = runTest {
        assertEquals(8, resolve("1.16.5").javaVersion.majorVersion)
        assertEquals(16, resolve("1.17.1").javaVersion.majorVersion)
        assertEquals(21, resolve("1.21.4").javaVersion.majorVersion)
        assertEquals(25, resolve("26.2").javaVersion.majorVersion)
    }

    @Test
    fun `alpha assets are flagged for the pre-1_6 layout`() = runTest {
        val alpha = resolve("a1.1.2_01")
        assertEquals(VersionJson.ASSETS_PRE_1_6, alpha.assetsId)
        assertTrue(alpha.usesLegacyAssetLayout)
        assertTrue(alpha.mapsAssetsToResources)

        val legacy = resolve("1.6.4")
        assertEquals(VersionJson.ASSETS_LEGACY, legacy.assetsId)
        assertTrue(legacy.usesLegacyAssetLayout)
        assertFalse(legacy.mapsAssetsToResources)

        assertFalse(resolve("1.21.4").usesLegacyAssetLayout)
    }

    @Test
    fun `mod loader manifests inherit the vanilla version they extend`() = runBlocking {
        // Shaped like a Fabric manifest: overrides the main class, adds libraries, inherits the rest.
        val loader = VersionJson(
            id = "fabric-loader-0.16.9-1.21.4",
            inheritsFrom = "1.21.4",
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            libraries = listOf(
                Library(name = "net.fabricmc:fabric-loader:0.16.9", url = "https://maven.fabricmc.net/"),
            ),
            arguments = Arguments(jvm = listOf(Argument.of("-DFabricMcEmu=net.minecraft.client.main.Main"))),
        )
        val resolved = VersionResolver.resolve(loader.id) { id ->
            if (id == loader.id) loader else fixture(id)
        }

        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", resolved.mainClass)
        // The jar and assets still come from the vanilla parent.
        assertEquals("1.21.4", resolved.clientJarVersionId)
        assertNotNull(resolved.clientJar)
        assertEquals(21, resolved.javaVersion.majorVersion)

        val jvm = resolved.arguments.jvm.flatMap(Argument::values)
        assertTrue(jvm.contains("\${classpath}"))
        assertTrue(jvm.contains("-DFabricMcEmu=net.minecraft.client.main.Main"))

        // A library with no `downloads` block resolves against the repository it names.
        val fabricLoader = resolved.classpathLibraries(android)
            .single { it.library.name.startsWith("net.fabricmc:fabric-loader") }
        assertEquals(
            "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar",
            fabricLoader.artifact.url,
        )
    }

    @Test
    fun `inheritance cycles are rejected rather than hanging`() {
        val a = VersionJson(id = "a", inheritsFrom = "b", mainClass = "M")
        val b = VersionJson(id = "b", inheritsFrom = "a", mainClass = "M")
        val failure = runCatching {
            runBlocking {
                VersionResolver.resolve("a") { if (it == "a") a else b }
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `argument entries round-trip through serialization`() {
        val conditional = Argument(
            values = listOf("--width", "\${resolution_width}"),
            rules = listOf(Rule(features = mapOf("has_custom_resolution" to true))),
        )
        for (argument in listOf(conditional, Argument.of("-XstartOnFirstThread"))) {
            val encoded = LodestoneJson.encodeToJsonElement(argument)
            assertEquals(argument, LodestoneJson.decodeFromJsonElement(Argument.serializer(), encoded))
        }
    }

    @Test
    fun `maven coordinates map onto repository paths`() {
        assertEquals(
            "org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1.jar",
            MavenCoordinate.parseOrNull("org.lwjgl:lwjgl:3.4.1")?.path,
        )
        assertEquals(
            "org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1-natives-linux.jar",
            MavenCoordinate.parseOrNull("org.lwjgl:lwjgl:3.4.1:natives-linux")?.path,
        )
        assertEquals(
            "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.zip",
            MavenCoordinate.parseOrNull("net.minecraftforge:forge:1.20.1-47.2.0:universal@zip")?.path,
        )
        assertEquals(null, MavenCoordinate.parseOrNull("not-a-coordinate"))
    }

    @Test
    fun `rule evaluation follows last-match-wins with a deny default`() {
        val allowLinux = listOf(Rule(RuleAction.ALLOW, os = OsConstraint(name = "linux")))
        assertTrue(allowLinux.allows(android))
        assertFalse(allowLinux.allows(android.copy(osName = "windows")))

        // Mojang's shape for "everywhere except macOS".
        val allowExceptMac = listOf(
            Rule(RuleAction.ALLOW),
            Rule(RuleAction.DISALLOW, os = OsConstraint(name = "osx")),
        )
        assertTrue(allowExceptMac.allows(android))
        assertFalse(allowExceptMac.allows(android.copy(osName = "macos")))

        assertTrue(emptyList<Rule>().allows(android))

        val x86Only = listOf(Rule(RuleAction.ALLOW, os = OsConstraint(arch = "x86")))
        assertFalse(x86Only.allows(android))
        assertTrue(x86Only.allows(android.copy(osArch = "x86")))
    }

    @Test
    fun `feature rules honour the requested feature set`() {
        val demoOnly = listOf(Rule(features = mapOf("is_demo_user" to true)))
        assertFalse(demoOnly.allows(android))
        assertTrue(demoOnly.allows(android.copy(features = setOf("is_demo_user"))))
    }

    private companion object {
        val ALL_FIXTURES =
            listOf("a1.1.2_01", "1.6.4", "1.7.10", "1.16.5", "1.17.1", "1.21.4", "26.2")
    }
}
