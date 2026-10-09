@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.abi.AbiValidationToolchain
import org.jetbrains.kotlin.buildtools.api.abi.KlibTargetId
import org.jetbrains.kotlin.buildtools.api.abi.KlibTargetType
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile

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

// The README and docs install snippets name the release being developed: the Gradle
// version without -SNAPSHOT. This keeps them on the same number as ArchiveKodec.VERSION.
val checkReadmeVersion = tasks.register("checkReadmeVersion") {
    group = "verification"
    description = "Check that the README and docs coordinates name the version in gradle.properties"
    val release = providers.gradleProperty("version").map { it.removeSuffix("-SNAPSHOT") }
    val pages = listOf(layout.projectDirectory.file("README.md"), layout.projectDirectory.file("docs/index.md"))
    inputs.property("release", release)
    inputs.files(pages)
    doLast {
        val coordinate = Regex("""io\.github\.yuroyami:(archivekodec[a-z-]*):([0-9][^"\s)`]*)""")
        for (page in pages.map { it.asFile }) {
            val found = coordinate.findAll(page.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
            if (found.isEmpty()) throw GradleException("${page.name} names no archivekodec coordinates")
            val stale = found.filter { it.second != release.get() }
            if (stale.isNotEmpty()) throw GradleException("${page.name} coordinates $stale do not name release ${release.get()}")
        }
    }
}

// Aggregate the published library modules into a single Dokka API reference.
dependencies {
    dokka(project(":archivekodec"))
    dokka(project(":archivekodec-io"))
    dokka(project(":archivekodec-crypto"))
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
                reportUndocumented.set(true)
                val moduleDoc = layout.projectDirectory.file("Module.md")
                if (moduleDoc.asFile.exists()) {
                    includes.from(moduleDoc)
                }
                sourceLink {
                    localDirectory.set(layout.projectDirectory.dir("src"))
                    remoteUrl("https://github.com/yuroyami/ArchiveKodec/blob/main/${project.name}/src")
                    remoteLineSuffix.set("#L")
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

// AGP's external Android target is not included by KGP's legacy Android
// collector. Read the final AAR classes using the same public ABI toolchain.
val abiTools = configurations.create("abiTools") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(abiTools.name, "org.jetbrains.kotlin:kotlin-build-tools-impl:${libs.versions.kotlin.get()}")
}

data class PortableAbiBinary(val module: String, val target: String, val file: File)
val portableAbiBinaries = listOf("archivekodec", "archivekodec-io", "archivekodec-crypto").flatMap { module ->
    listOf(
        PortableAbiBinary(module, "jvm", file("$module/build/libs/$module-jvm-${project.version}.jar")),
        PortableAbiBinary(module, "android", file("$module/build/outputs/aar/$module.aar")),
    )
}
fun PortableAbiBinary.referenceFile(): File = file("$module/api/$target/$module.api")
fun PortableAbiBinary.actualFile(): File = layout.buildDirectory.file("abi/portable/$module/$target/$module.api").get().asFile

val dumpPortableAbi = tasks.register("dumpPortableAbi") {
    dependsOn(":archivekodec:jvmJar", ":archivekodec-io:jvmJar", ":archivekodec-crypto:jvmJar",
        ":archivekodec:bundleAndroidMainAar", ":archivekodec-io:bundleAndroidMainAar", ":archivekodec-crypto:bundleAndroidMainAar")
    inputs.files(portableAbiBinaries.map { it.file }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(abiTools).withNormalizer(ClasspathNormalizer::class.java)
    outputs.files(portableAbiBinaries.map { it.actualFile() })
    doLast {
        val toolchains = KotlinToolchains.loadImplementation(abiTools.files.map { it.toPath() })
        val abi = toolchains.getToolchain(AbiValidationToolchain::class.java)
        toolchains.createBuildSession().use { session ->
            for (binary in portableAbiBinaries) {
                val classes = if (binary.target == "android") {
                    val extracted = temporaryDir.resolve("${binary.module}-classes.jar")
                    ZipFile(binary.file).use { aar ->
                        val entry = aar.getEntry("classes.jar") ?: error("${binary.file} has no classes.jar")
                        aar.getInputStream(entry).use { input -> extracted.outputStream().use { input.copyTo(it) } }
                    }
                    extracted
                } else binary.file
                val output = binary.actualFile()
                output.parentFile.mkdirs()
                output.bufferedWriter().use { writer ->
                    session.executeOperation(abi.dumpJvmAbiToStringOperationBuilder(writer, listOf(classes.toPath())).build())
                }
            }
        }
    }
}

val checkPortableAbi = tasks.register("checkPortableAbi") {
    group = "verification"
    description = "Check the public ABI of both JVM JARs and both Android AARs"
    dependsOn(dumpPortableAbi)
    inputs.files(portableAbiBinaries.map { it.referenceFile() })
    inputs.files(portableAbiBinaries.map { it.actualFile() })
    doLast {
        val toolchains = KotlinToolchains.loadImplementation(abiTools.files.map { it.toPath() })
        val abi = toolchains.getToolchain(AbiValidationToolchain::class.java)
        toolchains.createBuildSession().use { session ->
            for (binary in portableAbiBinaries) {
                val reference = binary.referenceFile()
                if (!reference.isFile) throw GradleException("Missing ABI reference $reference; run updateKotlinAbi deliberately")
                val diff = StringBuilder()
                session.executeOperation(abi.compareAbiTextFilesOperationBuilder(diff, reference.toPath(), binary.actualFile().toPath()).build())
                if (diff.isNotEmpty()) throw GradleException("Public ABI changed for ${binary.module}:${binary.target}:\n$diff")
                logger.lifecycle("Verified ABI for ${binary.module}:${binary.target}")
            }
        }
    }
}

val updateAndroidAbi = tasks.register("updateAndroidAbi") {
    group = "verification"
    description = "Deliberately update the Android AAR ABI reference files"
    dependsOn(dumpPortableAbi)
    doLast {
        portableAbiBinaries.filter { it.target == "android" }.forEach { binary ->
            val reference = binary.referenceFile()
            reference.parentFile.mkdirs()
            binary.actualFile().copyTo(reference, overwrite = true)
        }
    }
}

tasks.register("checkKotlinAbi") {
    group = "verification"
    description = "Check every compiled JVM, Android, JS and iOS public ABI"
    dependsOn(":archivekodec:checkKotlinAbi", ":archivekodec-io:checkKotlinAbi", ":archivekodec-crypto:checkKotlinAbi", checkPortableAbi)
}
tasks.register("checkLegacyAbi") {
    group = "verification"
    description = "Compatibility command for the aggregate current ABI check"
    dependsOn("checkKotlinAbi")
}
tasks.register("updateKotlinAbi") {
    group = "verification"
    description = "Deliberately update every current ABI reference on a host supporting all targets"
    dependsOn(":archivekodec:updateKotlinAbi", ":archivekodec-io:updateKotlinAbi", ":archivekodec-crypto:updateKotlinAbi", updateAndroidAbi)
}

tasks.named("check") {
    group = "verification"
    dependsOn(":archivekodec:check", ":archivekodec-io:check", ":archivekodec-crypto:check", "checkKotlinAbi", checkReadmeVersion)
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkPortableAbi"))
    }
}

tasks.register("recordPublishedAbi") {
    group = "verification"
    description = "Record the verified historical 0.1.0 binaries; never compares them with the renamed source"
    doLast {
        val inputDir = file(providers.gradleProperty("publishedAbiInputDir").get())
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(inputDir.resolve("manifest.json")) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val records = manifest["artifacts"] as List<Map<String, Any>>
        val expected = mapOf("kitearchive" to setOf("jvm", "android", "js", "iosarm64", "iossimulatorarm64", "iosx64"),
            "kitearchive-io" to setOf("jvm", "android", "iosarm64", "iossimulatorarm64", "iosx64"))
        check(manifest["version"] == "0.1.0" && records.size == 11)
        val actual = records.groupBy { it["module"] }.mapValues { (_, rows) -> rows.map { it["target"] }.toSet() }
        check(actual == expected) { "Published target matrix is incomplete: $actual" }
        for (record in records) {
            val binary = inputDir.resolve(record["filename"] as String)
            val digest = MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == record["sha256"]) { "Published binary checksum differs: $binary" }
        }
        val outputDir = file("api/published/0.1.0").also { it.mkdirs() }
        val toolchains = KotlinToolchains.loadImplementation(abiTools.files.map { it.toPath() })
        val abi = toolchains.getToolchain(AbiValidationToolchain::class.java)
        toolchains.createBuildSession().use { session ->
            for ((module, rows) in records.groupBy { it["module"] as String }) {
                val klibs = mutableMapOf<KlibTargetId, java.nio.file.Path>()
                for (record in rows) {
                    val target = record["target"] as String
                    val binary = inputDir.resolve(record["filename"] as String)
                    if (target == "jvm" || target == "android") {
                        val classes = if (target == "android") {
                            temporaryDir.resolve("$module-classes.jar").also { extracted ->
                                ZipFile(binary).use { aar ->
                                    aar.getInputStream(aar.getEntry("classes.jar")).use { input -> extracted.outputStream().use { input.copyTo(it) } }
                                }
                            }
                        } else binary
                        outputDir.resolve("$module-$target.api").bufferedWriter().use { writer ->
                            session.executeOperation(abi.dumpJvmAbiToStringOperationBuilder(writer, listOf(classes.toPath())).build())
                        }
                    } else {
                        val (canonical, name) = when (target) {
                            "js" -> "js" to "js"
                            "iosarm64" -> "ios_arm64" to "iosArm64"
                            "iossimulatorarm64" -> "ios_simulator_arm64" to "iosSimulatorArm64"
                            "iosx64" -> "ios_x64" to "iosX64"
                            else -> error("Unknown published target $target")
                        }
                        val type = if (target == "js") KlibTargetType.JS else KlibTargetType.fromKonanTargetName(canonical)
                        klibs[KlibTargetId(type, name)] = binary.toPath()
                    }
                }
                outputDir.resolve("$module.klib.api").bufferedWriter().use { writer ->
                    session.executeOperation(abi.dumpKlibAbiToStringOperationBuilder(writer, klibs).build())
                }
            }
        }
        inputDir.resolve("manifest.json").copyTo(outputDir.resolve("manifest.json"), overwrite = true)
    }
}
