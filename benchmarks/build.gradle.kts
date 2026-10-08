import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        nodejs()
        binaries.executable()
    }
    sourceSets.commonMain.dependencies {
        implementation(project(":archivekodec"))
    }
}

val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
tasks.register<JavaExec>("jvmBenchmark") {
    group = "verification"
    description = "Run CPU-time benchmarks and JDK references (BENCHMARK_PROFILE=smoke or full)"
    dependsOn(jvmMain.compileTaskProvider)
    classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("io.github.yuroyami.archivekodec.benchmarks.JvmMainKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "2g"
}
