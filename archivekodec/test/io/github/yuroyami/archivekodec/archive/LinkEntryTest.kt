package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.security.ArchivePathException
import io.github.yuroyami.archivekodec.security.PathGuard
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #27: link members are identified, and their targets resolved safely. */
class LinkEntryTest {
    companion object {
        /** One ustar record: a header for [name] of [type], then [data] padded to 512 bytes. */
        fun tarRecord(name: String, type: Char, data: ByteArray = ByteArray(0), link: String = ""): ByteArray {
            val header = ByteArray(512)
            fun put(at: Int, text: String) = text.encodeToByteArray().copyInto(header, at)
            put(0, name)
            put(100, "0000644\u0000")
            put(108, "0000000\u0000"); put(116, "0000000\u0000")
            put(124, data.size.toString(8).padStart(11, '0') + "\u0000")
            put(136, "14612345670\u0000")
            header[156] = type.code.toByte()
            put(157, link)
            put(257, "ustar\u0000"); put(263, "00")
            "        ".encodeToByteArray().copyInto(header, 148)
            val sum = header.sumOf { it.toInt() and 0xFF }
            put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
            return header + data + ByteArray((512 - data.size % 512) % 512)
        }

        fun tar(vararg records: ByteArray): ByteArray = records.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(1024)
    }

    @Test
    fun tarListsSymbolicAndHardLinks() {
        val longTarget = "deep/" + "x".repeat(150) + "/target.txt"
        val bytes = tar(
            tarRecord("file.txt", '0', "payload".encodeToByteArray()),
            tarRecord("sym", '2', link = "file.txt"),
            tarRecord("hard", '1', link = "file.txt"),
            tarRecord("././@LongLink", 'K', (longTarget + "\u0000").encodeToByteArray()),
            tarRecord("long-sym", '2', link = "ignored"),
            tarRecord("orphan", '1', link = "missing.txt"),
        )
        ArchiveKodec.open(bytes).use { reader ->
            val byName = reader.entries().associateBy { it.name }
            val sym = byName.getValue("sym")
            assertTrue(sym.isSymbolicLink); assertFalse(sym.isHardLink)
            assertEquals("file.txt", sym.linkTarget)
            assertEquals(0xA000, sym.unixMode!! and 0xF000)
            assertContentEquals("file.txt".encodeToByteArray(), reader.read(sym))
            val hard = byName.getValue("hard")
            assertTrue(hard.isHardLink); assertFalse(hard.isSymbolicLink)
            assertEquals("file.txt", hard.linkTarget)
            assertEquals(7L, hard.size)
            assertContentEquals("payload".encodeToByteArray(), reader.read(hard))
            assertEquals(longTarget, byName.getValue("long-sym").linkTarget)
            val orphan = byName.getValue("orphan")
            assertEquals(0L, orphan.size)
            assertFailsWith<ArchiveFormatException> { reader.read(orphan) }
            assertFalse(byName.getValue("file.txt").isSymbolicLink || byName.getValue("file.txt").isHardLink)
        }
    }

    @Test
    fun zipSymlinkModesMarkLinksWithTargetsInTheirData() {
        val zip = ZipWriter.write(listOf(
            ZipWriter.FileSpec("link", "dir/target".encodeToByteArray()) { unixMode = 0xA1FF },
            ZipWriter.FileSpec("plain.txt", "x".encodeToByteArray()) { unixMode = 0x81A4 },
        ))
        ArchiveKodec.open(zip).use { reader ->
            val byName = reader.entries().associateBy { it.name }
            assertTrue(byName.getValue("link").isSymbolicLink)
            assertNull(byName.getValue("link").linkTarget)
            assertContentEquals("dir/target".encodeToByteArray(), reader.read(byName.getValue("link")))
            assertFalse(byName.getValue("plain.txt").isSymbolicLink)
        }
    }

    @Test
    fun entryEqualityAndCopiesKeepLinks() {
        val link = ArchiveEntry("a", 1, false) { isSymbolicLink = true; linkTarget = "b" }
        assertEquals(link, link.copy())
        assertFalse(link == ArchiveEntry("a", 1, false) { isSymbolicLink = true; linkTarget = "c" })
        assertTrue("symbolicLink=\"b\"" in link.toString(), link.toString())
        assertTrue(ArchiveEntry("m", 0, false) { unixMode = 0xA1ED }.isSymbolicLink)
    }

    @Test
    fun linkTargetsResolveAgainstTheLinkDirectory() {
        assertEquals("a/c", PathGuard.resolveLink("a/b/link", "../c"))
        assertEquals("c", PathGuard.resolveLink("a/b", "../c"))
        assertEquals("a/b/c", PathGuard.resolveLink("a/b/link", "./c"))
        assertEquals("", PathGuard.resolveLink("a/link", ".."))
        assertEquals("x/y", PathGuard.resolveLink("link", "x\\y"))
        for ((name, target) in listOf("link" to "../../..", "a/link" to "../../etc", "link" to "/etc/passwd", "link" to "C:/Windows", "../link" to "x", "link" to "")) {
            assertFailsWith<ArchivePathException>("$name -> $target") { PathGuard.resolveLink(name, target) }
        }
    }
}
