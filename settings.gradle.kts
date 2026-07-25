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

rootProject.name = "KiteArchive-KMP"

// :kitearchive — the pure-Kotlin compression + archive core (stdlib only). Codecs
// (store, DEFLATE, gzip/zlib, LZ4, Snappy), the ZIP reader/writer and the TAR/ar/cpio
// readers, checksums, the magic sniffer and the security checks. Android, iOS, JVM, JS.
include(":kitearchive")

// :kitearchive-io — the kotlinx-io adapter: four convenience functions over RawSource.
// No RawSink bridge and no seekable file handle yet. Keeps the kotlinx-io dependency
// off the core and off consumers that don't want it. Android, iOS, JVM — no JS.
include(":kitearchive-io")
