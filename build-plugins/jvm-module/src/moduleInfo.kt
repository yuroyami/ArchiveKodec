/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.jvmmodule

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.ModuleSources
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeBytes

private val packageLine = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

/**
 * Writes `module-info.class` for the module's JVM jar. The module exports the package of every Kotlin and Java
 * file in [JvmModuleSettings.sourceDirs], and requires [JvmModuleSettings.requires] transitively. This matches an
 * automatic module, but with a fixed name. The class file targets Java 9, so Java 8 never loads it.
 */
@OptIn(ExperimentalPathApi::class)
@TaskAction
fun writeModuleInfo(
    settings: JvmModuleSettings,
    @Input sources: ModuleSources,
    @Output outputDir: Path,
) {
    val roots = sources.sourceDirectories.filter { it.name in settings.sourceDirs && it.isDirectory() }
    check(roots.size == settings.sourceDirs.size) {
        "Expected the source directories ${settings.sourceDirs}, found ${roots.map { it.name }}"
    }
    val packages = roots.asSequence()
        .flatMap { root -> root.walk() }
        .filter { it.isRegularFile() && it.extension in setOf("kt", "java") }
        .mapNotNull { packageLine.find(it.readText())?.groupValues?.get(1) }
        .map { it.replace('.', '/') }
        .toSortedSet()
    check(packages.isNotEmpty()) { "No packages in $roots" }

    val writer = ClassWriter(0)
    writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
    val module = writer.visitModule(settings.name, 0, null)
    module.visitRequire("java.base", Opcodes.ACC_MANDATED, null)
    settings.requires.forEach { module.visitRequire(it, Opcodes.ACC_TRANSITIVE, null) }
    packages.forEach { module.visitPackage(it) }
    packages.forEach { module.visitExport(it, 0) }
    module.visitEnd()
    writer.visitEnd()

    outputDir.deleteRecursively()
    outputDir.createDirectories().resolve("module-info.class").writeBytes(writer.toByteArray())
    println("Wrote module ${settings.name} with ${packages.size} packages")
}
