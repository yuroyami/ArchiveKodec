import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :archivekodec is the pure-Kotlin compression + archive core. NO external runtime
 * deps — only kotlin-stdlib is on the classpath, exactly like :kitepdf and
 * :kitetorrent. Everything here is pure computation (no sockets, no threads, no
 * disk): the DEFLATE codec, gzip/zlib framing, checksums, the container parsers,
 * the path-traversal / decompression-bomb guards, and the seekable-source contract.
 *
 * The kotlinx-io adapters live in a separate :archivekodec-io module so the
 * kotlinx-io dependency never reaches consumers who don't want it. That module has
 * no JS target; this one does. Neither builds for wasmJs, macOS, Linux or mingw.
 */
kotlin {
    explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.archivekodec"
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
            baseName = "ArchiveKodec"
            isStatic = false
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        nodejs {
            testTask {
                useMocha {
                    // Whole-array boundary tests intentionally decode several MiB.
                    timeout = "30s"
                }
            }
        }
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
            // Intentionally empty. ArchiveKodec core depends on kotlin-stdlib only.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
