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
 * :archivekodec is the pure-Kotlin compression + archive core. NO external runtime
 * deps — only kotlin-stdlib is on the classpath, exactly like :kitepdf and
 * :kitetorrent. Everything here is pure computation (no sockets, no threads, no
 * disk): the DEFLATE codec, gzip/zlib framing, checksums, the container parsers,
 * the path-traversal / decompression-bomb guards, and the seekable-source contract.
 *
 * The kotlinx-io adapters live in a separate :archivekodec-io module so the
 * kotlinx-io dependency never reaches consumers who don't want it. Both modules
 * declare the same target matrix, so a consumer can put both in one commonMain.
 * That matrix covers every target :kitepdf-core and :imagekodec declare. Nothing
 * here is expect/actual, so the default hierarchy template supplies the shared
 * native, Apple and web source sets without extra configuration.
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

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs {
            testTask {
                useMocha {
                    timeout = "30s"
                }
            }
        }
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs {
            testTask {
                useMocha {
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
