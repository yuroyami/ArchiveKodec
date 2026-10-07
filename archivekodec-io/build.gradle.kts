import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.archivekodec.io"
        compileSdk = 36
        minSdk = 21
        withHostTest {}
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

    jvm()

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
