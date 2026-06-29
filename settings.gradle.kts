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
// (DEFLATE, gzip/zlib, store, …), container parsers (ZIP, TAR, …), checksums, and
// the security limits all live here and port cleanly to every KMP target.
include(":kitearchive")

// :kitearchive-io — the thin kotlinx-io adapter (Tier 2). Bridges kotlinx-io
// RawSource/RawSink and seekable file handles to the core's stream/RandomAccessSource
// contracts. Keeps the experimental kotlinx-io dependency off the core and off
// consumers that don't want it.
include(":kitearchive-io")
