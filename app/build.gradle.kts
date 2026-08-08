import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.metro)
}

/**
 * Reads a build-time value from, in order of precedence, a Gradle property, an environment
 * variable, then `local.properties`. Keeps credentials out of version control while staying usable
 * from CI, where only environment variables are available.
 */
fun secret(key: String, environmentKey: String, default: String): String {
    (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv(environmentKey)?.takeIf { it.isNotBlank() }?.let { return it }
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        val properties = Properties()
        localProperties.inputStream().use(properties::load)
        properties.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return default
}

android {
    namespace = "com.github.lodestone"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.github.lodestone"
        // Set by the Java runtime, not by the app: HotSpot needs dlinfo, which bionic only exposes
        // from API 30. Android 11 is also a sensible floor for a device expected to run a JVM, a
        // GL translation layer and Minecraft at once.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 32-bit ARM is deliberately excluded: a 4 GiB address space cannot hold a modern
            // Minecraft heap alongside the graphics translation layer and the JIT code cache.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-fno-exceptions", "-fno-rtti", "-fvisibility=hidden")
            }
        }

        buildConfigField(
            "String",
            "MSA_CLIENT_ID",
            "\"${secret("lodestone.msaClientId", "LODESTONE_MSA_CLIENT_ID", "00000000402b5328")}\"",
        )
    }

    buildTypes {
        debug {
            // Offline accounts skip Mojang authentication entirely, so they exist only in debug
            // builds, where they cannot be used to sidestep an ownership check in the wild.
            buildConfigField("boolean", "ALLOW_OFFLINE_ACCOUNTS", "true")
        }
        release {
            buildConfigField("boolean", "ALLOW_OFFLINE_ACCOUNTS", "false")
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // The hosted Java runtime `dlopen`s our shims by absolute path, which only works if the
            // installer materialises them as real files under `nativeLibraryDir`.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ------------------------------------------------------------------------------------------------
// The LWJGL 2 compatibility layer
// ------------------------------------------------------------------------------------------------
// Minecraft before 1.13 links against LWJGL 2, which has no arm64 build and never will. The layer
// under src/lwjgl2 declares LWJGL 2's API and forwards it to LWJGL 3. It is a source set rather than
// a module because it is not Android code at all: it is a Java 8 library that runs inside the hosted
// HotSpot on the device and must not be dexed, so it cannot be part of :app's own compilation.
//
// LWJGL 2 and LWJGL 3 share the org.lwjgl package root and collide on 243 class names under
// org.lwjgl.opengl alone, so a layer that declares org.lwjgl.opengl.GL11 hides the LWJGL 3 class it
// wants to call. LWJGL 3's OpenGL module is therefore relocated into a package of ours before the
// layer is compiled against it. runtime/build-lwjgl.sh applies the matching rename to the JNI
// symbols, which are compiled from the same release's generated sources.
val lwjgl2Package = "com.github.lodestone.lwjgl3"

// Bundled as they are. The core module carries org.lwjgl.BufferUtils and PointerBuffer, whose LWJGL
// 3 versions have the same descriptors and the same semantics as LWJGL 2's, so the layer declares
// neither and two more collisions disappear. GLFW has no LWJGL 2 counterpart to collide with.
val lwjgl2Bundled: Configuration by configurations.creating

// Relocated. This is the only module with JNI code of its own, and the only one that collides.
// Not transitive: it depends on the core module, which belongs in the bundle above unmoved.
val lwjgl2Relocated: Configuration by configurations.creating {
    isTransitive = false
}

/**
 * Moves a jar's classes into another package, in place of Gradle Shadow.
 *
 * Shadow refuses outright to apply to a project that has AGP, and the layer has to be built inside
 * :app for Lodestone to stay one module, so the one thing needed of it is done here. Every type
 * name in a class file is a UTF-8 constant-pool entry; rewriting those and their length prefixes
 * moves the package, and the rest of the file addresses everything by pool index rather than by
 * byte offset, so it is copied through untouched.
 *
 * Unlike Shadow this deliberately leaves string constants alone, and asserts that it can: LWJGL
 * names its own packages in strings that reach `Configuration` and `Library.loadSystem`, and those
 * are dotted, whereas the slashed form rewritten here appears only in type names.
 */
abstract class RelocateJar : DefaultTask() {

    @get:InputFiles
    abstract val source: ConfigurableFileCollection

    @get:Input
    abstract val fromPackage: Property<String>

    @get:Input
    abstract val toPackage: Property<String>

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun relocate() {
        val from = fromPackage.get().replace('.', '/') + "/"
        val to = toPackage.get().replace('.', '/') + "/"
        var moved = 0
        val written = mutableSetOf<String>()
        ZipOutputStream(target.get().asFile.outputStream().buffered()).use { out ->
            for (jar in source.files) {
                ZipFile(jar).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory || !written.add(entry.name)) continue
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val rewritten = if (entry.name.endsWith(".class")) {
                            moved++
                            rewriteClass(bytes, from, to)
                        } else {
                            bytes
                        }
                        out.putNextEntry(ZipEntry(entry.name.replace(from, to)))
                        out.write(rewritten)
                        out.closeEntry()
                    }
                }
            }
        }
        logger.lifecycle("Relocated $moved classes from ${fromPackage.get()} to ${toPackage.get()}")
    }

    private fun rewriteClass(bytes: ByteArray, from: String, to: String): ByteArray {
        val input = DataInputStream(bytes.inputStream())
        val body = ByteArrayOutputStream(bytes.size)
        val output = DataOutputStream(body)
        output.writeInt(input.readInt())
        output.writeShort(input.readUnsignedShort())
        output.writeShort(input.readUnsignedShort())
        val count = input.readUnsignedShort()
        output.writeShort(count)
        // Which pool entries a CONSTANT_String points at, so that the assertion below can tell a
        // type name from a string literal that happens to look like one.
        val literals = mutableSetOf<Int>()
        val text = mutableMapOf<Int, String>()
        var index = 1
        while (index < count) {
            val tag = input.readUnsignedByte()
            output.writeByte(tag)
            when (tag) {
                1 -> {
                    val value = input.readUTF()
                    text[index] = value
                    output.writeUTF(value.replace(from, to))
                }
                3, 4, 9, 10, 11, 12, 17, 18 -> output.writeInt(input.readInt())
                5, 6 -> {
                    output.writeLong(input.readLong())
                    index++
                }
                7, 16, 19, 20 -> output.writeShort(input.readUnsignedShort())
                8 -> {
                    val at = input.readUnsignedShort()
                    literals.add(at)
                    output.writeShort(at)
                }
                15 -> {
                    output.writeByte(input.readUnsignedByte())
                    output.writeShort(input.readUnsignedShort())
                }
                else -> throw GradleException("Unknown constant pool tag $tag")
            }
            index++
        }
        for (at in literals) {
            val value = text[at] ?: continue
            if (value.contains(from)) {
                throw GradleException("A string constant names $from: \"$value\"")
            }
        }
        val rest = ByteArray(input.available())
        input.readFully(rest)
        output.write(rest)
        output.flush()
        return body.toByteArray()
    }
}

val relocateLwjgl3Opengl = tasks.register<RelocateJar>("relocateLwjgl3Opengl") {
    description = "Relocates LWJGL 3's OpenGL bindings out of the way of LWJGL 2's class names."
    source.from(lwjgl2Relocated)
    fromPackage = "org.lwjgl.opengl"
    toPackage = "$lwjgl2Package.opengl"
    target = layout.buildDirectory.file("lwjgl2/lwjgl3-opengl-relocated.jar")
}

val compileLwjgl2 = tasks.register<JavaCompile>("compileLwjgl2") {
    description = "Compiles the LWJGL 2 compatibility layer for the hosted runtime, not for Android."
    source = fileTree("src/lwjgl2/java")
    include("**/*.java")
    classpath = files(relocateLwjgl3Opengl, lwjgl2Bundled)
    destinationDirectory = layout.buildDirectory.dir("lwjgl2/classes")
    // The oldest runtime any of these versions asks for is Java 8, and --release rather than
    // -source/-target so that a Java 9 API cannot be reached by accident and fail only on device.
    options.release = 8
    options.encoding = "UTF-8"
}

val lwjgl2CompatJar = tasks.register<Jar>("lwjgl2CompatJar") {
    description = "Packages the LWJGL 2 compatibility layer as the jar the launcher puts on the game's classpath."
    archiveFileName = "lwjgl2-compat.jar"
    // Self-contained: a pre-1.13 manifest names no LWJGL 3 coordinate at all, so anything not
    // carried here would have to be synthesised into the version's library list to be downloaded.
    from(compileLwjgl2.map { it.destinationDirectory })
    from(relocateLwjgl3Opengl.map { zipTree(it.target) })
    from(provider { lwjgl2Bundled.map(::zipTree) })
    // Signatures cover the jars these came out of, not this one; module-info describes a module
    // path that a Java 8 runtime does not have.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

androidComponents {
    onVariants { variant ->
        // Through the variant API rather than a source set: AGP 9 refuses a provider there, and
        // this is what carries the task dependency across so the jar is built before it is packaged.
        variant.sources.assets?.addGeneratedSourceDirectory(lwjgl2CompatJar, Jar::getDestinationDirectory)
    }
}

room {
    // Checking generated schemas in makes migrations reviewable in a pull request.
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    lwjgl2Bundled(libs.lwjgl)
    lwjgl2Bundled(libs.lwjgl.glfw)
    lwjgl2Relocated(libs.lwjgl.opengl)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // The device tests build a repository, which needs an auth API it will never call.
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
