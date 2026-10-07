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
    // Build with JDK 21, but emit Java 8 bytecode against the Java 8 API, the same
    // floor as kotlin-stdlib and kotlinx-io. The language and API versions are pinned
    // so older Kotlin compilers can read the metadata (see the README's Install section).
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }

    android {
        namespace = "io.github.yuroyami.archivekodec"
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

// A stable JPMS name, so modular callers do not depend on the jar's file name.
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes("Automatic-Module-Name" to "io.github.yuroyami.archivekodec")
    }
}
