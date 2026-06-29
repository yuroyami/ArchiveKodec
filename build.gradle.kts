plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html (deployed to /api/).
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.yuroyami"
    version = "0.0.1-SNAPSHOT"
}

// Aggregate the published library modules into a single Dokka API reference.
dependencies {
    dokka(project(":kitearchive"))
    dokka(project(":kitearchive-io"))
}

dokka {
    moduleName.set("KiteArchive")
}
