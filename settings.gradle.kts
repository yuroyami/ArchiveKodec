enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ArchiveKodec-KMP"

// :archivekodec — the pure-Kotlin compression + archive core (stdlib only). Codecs
// (store, DEFLATE, gzip/zlib, LZ4, Snappy), the ZIP reader/writer and the TAR/ar/cpio
// readers, checksums, the magic sniffer and the security checks. Builds for every target
// KitePDF and ImageKodec use: Android, JVM, JS, Wasm, Apple, Linux, mingw, androidNative.
include(":archivekodec")

// :archivekodec-io — the kotlinx-io adapter: four convenience functions over RawSource.
// No RawSink bridge and no seekable file handle yet. Keeps the kotlinx-io dependency
// off the core and off consumers that don't want it. The same targets as :archivekodec.
include(":archivekodec-io")

// Compile a downstream JVM consumer against only the IO module's public dependencies.
include(":io-consumer-test")
project(":io-consumer-test").projectDir = file("integration-tests/io-consumer")

// Unpublished CPU-time benchmarks with shared JVM/Node corpora and JDK references.
include(":benchmarks")
