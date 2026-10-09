import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :archivekodec-crypto backs the core's CryptoProvider interface with cryptography-kotlin,
 * which uses the platform's own AES, HMAC and PBKDF2: the JDK on the JVM and Android,
 * OpenSSL 3 (bundled) on Linux, Windows and Android Native, and Apple's libraries on Apple
 * targets. That keeps hardware-accelerated, constant-time AES out of the stdlib-only core.
 *
 * The core's readers call the provider synchronously. Browsers only offer WebCrypto,
 * which is asynchronous, so the JS and Wasm targets are not published here.
 */
kotlin {
    explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)
    }
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        optIn.add("io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation")
    }

    android {
        namespace = "io.github.yuroyami.archivekodec.crypto"
        compileSdk = 36
        minSdk = 21
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    iosSimulatorArm64()
    iosArm64()
    iosX64()
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

    jvm {
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
        commonMain.dependencies {
            api(projects.archivekodec)
            api(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// A stable JPMS name, so modular callers do not depend on the jar's file name.
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes("Automatic-Module-Name" to "io.github.yuroyami.archivekodec.crypto")
    }
}
