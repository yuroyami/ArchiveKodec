package io.github.yuroyami.archivekodec

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun versionIsTheGradleVersion() {
        val expected = assertNotNull(System.getProperty("archivekodec.expectedVersion"), "set by the Gradle test task")
        assertEquals(expected, ArchiveKodec.VERSION)
    }

    @Test
    fun javaCallersReadTheVersionAtRunTime() {
        val type = ArchiveKodec::class.java
        // No constant field exists, so javac cannot copy the value into a caller.
        assertTrue(type.declaredFields.none { it.name == "VERSION" }, "VERSION must not be a field")
        val getter = type.getMethod("getVERSION")
        assertTrue(Modifier.isStatic(getter.modifiers), "Java calls ArchiveKodec.getVERSION() statically")
        assertEquals(ArchiveKodec.VERSION, getter.invoke(null))
    }
}
