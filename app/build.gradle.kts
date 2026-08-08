import java.util.Properties

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

room {
    // Checking generated schemas in makes migrations reviewable in a pull request.
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
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
