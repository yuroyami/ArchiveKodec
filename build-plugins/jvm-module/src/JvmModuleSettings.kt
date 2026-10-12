/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.jvmmodule

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface JvmModuleSettings {
    /** Java module name, such as `io.github.yuroyami.archivekodec`. */
    val name: String

    /** Java modules that the public API uses. Each one becomes `requires transitive`. */
    val requires: List<String> get() = emptyList()

    /** Source directories that the JVM jar compiles, relative to the module directory. Their packages are exported. */
    val sourceDirs: List<String> get() = listOf("src")
}
