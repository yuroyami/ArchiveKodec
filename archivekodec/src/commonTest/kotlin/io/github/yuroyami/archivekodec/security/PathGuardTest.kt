package io.github.yuroyami.archivekodec.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PathGuardTest {

    @Test
    fun acceptsNormalPath() = assertEquals("a/b/c.txt", PathGuard.sanitize("a/./b/c.txt"))

    @Test
    fun acceptsSpacesInNames() =
        assertEquals("My Folder/my file.txt", PathGuard.sanitize("My Folder/my file.txt"))

    @Test
    fun rejectsLeadingTraversal() = assertFalse(PathGuard.isSafe("../etc/passwd"))

    @Test
    fun rejectsNestedTraversal() = assertFalse(PathGuard.isSafe("a/../../b"))

    @Test
    fun rejectsAbsolutePath() = assertFalse(PathGuard.isSafe("/etc/passwd"))

    @Test
    fun rejectsBackslashTraversal() = assertFalse(PathGuard.isSafe("..\\windows\\system32"))

    @Test
    fun rejectsDriveLetter() = assertFalse(PathGuard.isSafe("C:/Windows"))

    @Test
    fun rejectsDrivePathsAfterRemovingDotSegments() {
        for (name in listOf("./C:/outside.txt", "././c:outside.txt", ".//C:/outside.txt", ".\\C:\\outside.txt", "./.\\C:/outside.txt")) {
            assertFailsWith<ArchivePathException>(name) { PathGuard.sanitize(name) }
            assertFalse(PathGuard.isSafe(name), name)
        }
    }

    @Test
    fun normalizedNamesRemainSafeAndRelative() {
        for (name in listOf("./folder/file.txt", "folder\\.\\file.txt", "./a//b/", "./:colon.txt", "./my file.txt")) {
            val safe = PathGuard.sanitize(name)
            assertTrue(PathGuard.isSafe(safe), name)
            assertEquals(safe, PathGuard.sanitize(safe), name)
            assertFalse(safe.startsWith("/"), name)
            assertFalse(safe.length >= 2 && safe[1] == ':', name)
        }
    }

    @Test
    fun rejectsNul() {
        val name = "a" + 0.toChar() + "b" // embedded NUL
        assertFalse(PathGuard.isSafe(name))
    }

    @Test
    fun rejectsControlChar() {
        val name = "a" + 1.toChar() + "b" // embedded SOH control char
        assertFalse(PathGuard.isSafe(name))
    }

    @Test
    fun rejectsEmpty() = assertFalse(PathGuard.isSafe(""))
}
