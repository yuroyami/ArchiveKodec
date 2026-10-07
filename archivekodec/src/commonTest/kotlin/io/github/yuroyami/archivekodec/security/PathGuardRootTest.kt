package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PathGuardRootTest {
    private fun documentedLoop(bytes: ByteArray, rawNames: List<String>) {
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entries = reader.entries()
            assertEquals(rawNames, entries.map { it.name })
            assertTrue(entries.first().isDirectory)
            val contents = mutableMapOf<String, ByteArray>()
            for (entry in entries) {
                if (PathGuard.isRootDirectory(entry)) continue
                val safeName = PathGuard.sanitize(entry.name)
                contents[safeName] = reader.read(entry)
            }
            assertEquals(setOf("sub", "sub/b.txt", "a.txt"), contents.keys)
            assertContentEquals(byteArrayOf(), contents["sub"])
            assertContentEquals("x\n".encodeToByteArray(), contents["sub/b.txt"])
            assertContentEquals("hello\n".encodeToByteArray(), contents["a.txt"])
        }
    }

    @Test
    fun documentedLoopWorksOnTarOfCurrentDirectory() =
        documentedLoop(RootArchiveFixtures.tar, listOf("./", "./sub/", "./a.txt", "./sub/b.txt"))

    @Test
    fun documentedLoopWorksOnFindNewcArchive() =
        documentedLoop(RootArchiveFixtures.newc, listOf(".", "./sub", "./sub/b.txt", "./a.txt"))

    @Test
    fun documentedLoopWorksOnFindOdcArchive() =
        documentedLoop(RootArchiveFixtures.odc, listOf(".", "./sub", "./sub/b.txt", "./a.txt"))

    private val rootNames = listOf(".", "./", "./.", "././", ".//.///", ".\\", ".\\.\\", "./.\\.//")

    @Test
    fun rootDirectoriesAreRecognizedWithoutWeakeningTheStrictSanitizer() {
        for (name in rootNames) {
            val entry = ArchiveEntry(name, 0, isDirectory = true)
            assertTrue(PathGuard.isRootDirectory(entry), name)
            assertFalse(PathGuard.isSafe(name), name)
            assertEquals(name, assertFailsWith<ArchivePathException>(name) { PathGuard.sanitize(name) }.entryName)
            assertEquals(name, entry.name)
        }
    }

    @Test
    fun rootFilesAreNeverSkipped() {
        for (name in rootNames) {
            val entry = ArchiveEntry(name, 0, isDirectory = false)
            assertFalse(PathGuard.isRootDirectory(entry), name)
            assertEquals(name, assertFailsWith<ArchivePathException>(name) { PathGuard.sanitize(entry.name) }.entryName)
        }
    }

    @Test
    fun invalidDirectoryNamesAreNeverSkipped() {
        val names = listOf("", "/", "/./", "\\", "\\\\server\\share", "..", "../.", "./../", "a/../.",
            "C:/", "./C:/", ".\\c:.", ".\\..\\.") + (0..31).map { ".${it.toChar()}/" } + ".\u007f/"
        for (name in names) {
            assertFalse(PathGuard.isRootDirectory(ArchiveEntry(name, 0, isDirectory = true)), name)
            assertFalse(PathGuard.isSafe(name), name)
            assertEquals(name, assertFailsWith<ArchivePathException>(name) { PathGuard.sanitize(name) }.entryName)
        }
    }

    @Test
    fun ordinaryDirectoriesAndFilesRemainNormalPaths() {
        val names = mapOf("./sub/" to "sub", "sub\\.\\file.txt" to "sub/file.txt", ".../" to "...",
            " leading /space //" to " leading /space ", "é/雪//" to "é/雪")
        for ((name, expected) in names) for (directory in listOf(false, true)) {
            val entry = ArchiveEntry(name, 0, isDirectory = directory)
            assertFalse(PathGuard.isRootDirectory(entry), name)
            assertEquals(expected, PathGuard.sanitize(entry.name), name)
            assertEquals(name, entry.name)
        }
    }

    @Test
    fun documentedLoopStillRejectsUnsafeArchivedDirectories() {
        for (name in listOf("../", "/", "./C:/", ".\\..\\/")) {
            val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf())))
            ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { reader ->
                for (entry in reader.entries()) {
                    assertTrue(entry.isDirectory)
                    assertFalse(PathGuard.isRootDirectory(entry), name)
                    assertEquals(name, assertFailsWith<ArchivePathException>(name) { PathGuard.sanitize(entry.name) }.entryName)
                }
            }
        }
    }
}
