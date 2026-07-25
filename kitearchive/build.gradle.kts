import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitearchive is the pure-Kotlin compression + archive core. NO external runtime
 * deps — only kotlin-stdlib is on the classpath, exactly like :kitepdf and
 * :kitetorrent. Everything here is pure computation (no sockets, no threads, no
 * disk): the DEFLATE codec, gzip/zlib framing, checksums, the container parsers,
 * the path-traversal / decompression-bomb guards, and the seekable-source contract.
 *
 * The kotlinx-io adapters live in a separate :kitearchive-io module so the
 * kotlinx-io dependency never reaches consumers who don't want it. That module has
 * no JS target; this one does. Neither builds for wasmJs, macOS, Linux or mingw.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitearchive"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteArchive"
            isStatic = false
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // Intentionally empty. KiteArchive core depends on kotlin-stdlib only.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

