plugins {
    // Declared here with apply false so the publish plugin's shared build service
    // is loaded by one classloader for the whole build. Without this, applying it
    // to sibling modules makes publishAndReleaseToMavenCentral fail.
    alias(libs.plugins.vanniktech.publish).apply(false)
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html (deployed to /api/).
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.yuroyami"
    version = "0.1.0"
}

// Aggregate the published library modules into a single Dokka API reference.
dependencies {
    dokka(project(":archivekodec"))
    dokka(project(":archivekodec-io"))
}

dokka {
    moduleName.set("ArchiveKodec")
}

// Shared Kite theme. Sources live in ../_kite-docs; ./_kite-docs/sync.sh copies
// them here, so this repo still builds standalone from a fresh clone.
//
// This has to be applied to every project that has Dokka, not just the root:
// under aggregation the root only renders the "all modules" landing page, and
// each module renders its own pages from its own configuration. Configuring
// only the root leaves every actual API page on the stock theme.
allprojects {
    plugins.withId("org.jetbrains.dokka") {
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            pluginsConfiguration.html {
                customStyleSheets.from(
                    rootProject.layout.projectDirectory.file("docs/api-theme/kite.css"),
                )
                templatesDir.set(
                    rootProject.layout.projectDirectory.dir("dokka-templates"),
                )
                footerMessage.set("Apache-2.0 · ArchiveKodec is part of the Kite family.")
            }

            // A module with a Module.md gets its description onto the aggregated
            // "all modules" landing page, which is otherwise a bare list of names.
            dokkaSourceSets.configureEach {
                val moduleDoc = layout.projectDirectory.file("Module.md")
                if (moduleDoc.asFile.exists()) {
                    includes.from(moduleDoc)
                }
            }

        }
    }
}


// Compile and execute downstream Swift consumers against the actual simulator frameworks.
tasks.register<Exec>("swiftConsumerTest") {
    group = "verification"
    description = "Verify NSError bridges and run core/IO Swift consumers on an iOS simulator"
    for (target in listOf("IosSimulatorArm64", "IosArm64", "IosX64")) {
        dependsOn(":archivekodec:linkDebugFramework$target", ":archivekodec-io:linkDebugFramework$target")
    }
    commandLine("python3", rootProject.file("integration-tests/swift-consumer/run.py").absolutePath, "--skip-build")
}
