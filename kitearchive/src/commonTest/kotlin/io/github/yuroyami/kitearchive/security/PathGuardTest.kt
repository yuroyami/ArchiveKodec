package io.github.yuroyami.kitearchive.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
