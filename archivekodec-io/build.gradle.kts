import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :archivekodec-io is the kotlinx-io adapter. Today it is four convenience functions
 * over RawSource: drain to a ByteArray, wrap as an in-memory RandomAccessSource, and
 * run a Codec over a source. There is no RawSink bridge and no seekable file handle
 * yet — toRandomAccessSource() reads the whole source into memory.
 *
 * The target matrix is the same as :archivekodec's, so a consumer can put both in
 * one commonMain. kotlinx-io publishes a variant for every one of these targets.
 *
 * Filesystem access (Android SAF, the iOS sandbox) stays the consumer's problem —
 * this module only adapts whatever Source the app already holds.
 */
kotlin {
    explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }
    // Build with JDK 21, but emit Java 8 bytecode against the Java 8 API, the same
    // floor as kotlin-stdlib and kotlinx-io. The language and API versions are pinned
    // so older Kotlin compilers can read the metadata (see the README's Install section).
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }

    android {
        namespace = "io.github.yuroyami.archivekodec.io"
        compileSdk = 36
        minSdk = 21
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ArchiveKodecIo"
            isStatic = false
            export(project(":archivekodec"))
            export(libs.kotlinx.io.core)
        }
    }

    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()

    linuxX64()
    linuxArm64()
    mingwX64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    js {
        browser()
        nodejs()
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
        binaries.library()
    }

    jvm {
        // Only the published classes have the floor. The JVM tests compare against
        // newer JDK APIs (CRC32C, HexFormat, readAllBytes).
        compilations.named("main") {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                    freeCompilerArgs.add("-Xjdk-release=1.8")
                }
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            api(projects.archivekodec)
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named("check") {
    dependsOn(":io-consumer-test:compileJava")
}

// A stable JPMS name, so modular callers do not depend on the jar's file name.
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes("Automatic-Module-Name" to "io.github.yuroyami.archivekodec.io")
    }
}
