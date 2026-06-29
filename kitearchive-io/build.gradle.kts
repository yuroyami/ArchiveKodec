import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitearchive-io is the thin I/O adapter (Tier 2). It bridges the pure
 * :kitearchive core to kotlinx-io:
 *   - kotlinx-io RawSource/RawSink  ↔  the core's streaming byte contracts
 *   - kotlinx-io files / SystemFileSystem  →  a seekable RandomAccessSource so the
 *     ZIP central-directory reader can browse and single-entry-extract by seek.
 *
 * No JS/WASM target: the browser has no filesystem for the seekable-source path.
 * Filesystem access (Android SAF, the iOS sandbox) stays the *consumer's* problem —
 * this module only adapts whatever Source/Sink/handle the app already holds.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitearchive.io"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteArchiveIo"
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
            api(projects.kitearchive)
            implementation(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
