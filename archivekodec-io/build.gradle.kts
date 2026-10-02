import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

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
 * This module deliberately has no JS target, so it does not match :archivekodec's
 * target set. A consumer that builds for JS cannot put both in one commonMain.
 *
 * Filesystem access (Android SAF, the iOS sandbox) stays the consumer's problem —
 * this module only adapts whatever Source the app already holds.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.archivekodec.io"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ArchiveKodecIo"
            isStatic = false
        }
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
            implementation(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.io.core)
        }
    }
}

