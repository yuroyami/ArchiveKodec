package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveKodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/** Lists names as stored, so tests can check raw-name preservation for PathGuard. */
private val RAW_NAMES = ArchiveLimits { rejectUnsafeNames = false }

class TarDirectoryNameTest {
    private fun header(name: String, type: Char = '5', marker: String = "ustar\u000000", prefix: String = "", size: Int = 0): ByteArray =
        ByteArray(512).also {
            name.encodeToByteArray().copyInto(it)
            "0000755".encodeToByteArray().copyInto(it, 100)
            size.toString(8).padStart(11, '0').encodeToByteArray().copyInto(it, 124)
            it[156] = type.code.toByte()
            marker.encodeToByteArray().copyInto(it, 257)
            prefix.encodeToByteArray().copyInto(it, 345)
            it.fill(32, 148, 156)
            (it.sumOf { b -> b.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ")
                .encodeToByteArray().copyInto(it, 148)
        }

    private fun record(header: ByteArray, data: ByteArray = byteArrayOf()) =
        header + data + ByteArray((512 - data.size % 512) % 512)

    private fun assertDirectories(bytes: ByteArray, names: List<String>) {
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes), RAW_NAMES).use { reader ->
            val entries = reader.entries()
            assertEquals(names, entries.map { it.name })
            assertEquals(entries, reader.entries())
            for (entry in entries) {
                assertTrue(entry.isDirectory)
                assertEquals(0L, entry.size)
                assertContentEquals(byteArrayOf(), reader.read(entry))
            }
        }
    }

    @Test
    fun tarAndZipDirectoryNamesMatchTheirStoredSpellings() {
        val names = listOf("d/", "e//", "../up/", "./", "/", "///", " leading /space //", "é/雪//")
        val tar = names.fold(byteArrayOf()) { bytes, name -> bytes + header(name) } + ByteArray(1024)
        val zip = zipForeignStoredNames(names)
        assertDirectories(tar, names)
        assertDirectories(zip, names)
    }

    @Test
    fun typeFiveNamesWithoutSlashesAndNamesAtFullWidthRemainUnchanged() {
        for (marker in listOf("", "ustar\u000000", "ustar  \u0000")) {
            val names = listOf("directory", "n".repeat(99) + "/", "e//")
            val tar = names.fold(byteArrayOf()) { bytes, name -> bytes + header(name, marker = marker) } + ByteArray(1024)
            assertDirectories(tar, names)
        }
    }

    @Test
    fun slashInferredDirectoryNamesRemainRawForRegularTypeFlags() {
        for (type in listOf('0', '\u0000', '7')) {
            val names = listOf("d/", "e//", "./")
            assertDirectories(names.fold(byteArrayOf()) { bytes, name -> bytes + header(name, type) } + ByteArray(1024), names)
        }
    }

    @Test
    fun posixPrefixAndDirectoryNameKeepEveryStoredSlash() {
        val tar = header("directory//", prefix = "parent/") + header("/", prefix = "root") + ByteArray(1024)
        assertDirectories(tar, listOf("parent//directory//", "root//"))
    }

    @Test
    fun gnuLongDirectoryNamesKeepTheirTrailingSeparators() {
        val name = "long/".repeat(30) + "directory//"
        val data = name.encodeToByteArray() + byteArrayOf(0)
        val longHeader = header("././@LongLink", type = 'L', marker = "ustar  \u0000", size = data.size)
        for (marker in listOf("", "ustar\u000000", "ustar  \u0000")) {
            val tar = record(longHeader, data) + header("fallback", marker = marker) + header("after/") + ByteArray(1024)
            assertDirectories(tar, listOf(name, "after/"))
        }
    }

    @Test
    fun directoriesKeepEmptyReadsAndFollowingFileOffsets() {
        val data = "DATA".encodeToByteArray()
        val bytes = header("e//", size = 1024) + record(header("file", type = '0', size = data.size), data) + ByteArray(1024)
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("e//", "file"), entries.map { it.name })
            assertTrue(entries[0].isDirectory)
            assertEquals(0L, entries[0].size)
            assertContentEquals(byteArrayOf(), reader.read(entries[0]))
            assertEquals(1024L, entries[1].dataOffset)
            assertContentEquals(data, reader.read(entries[1]))
        }
    }
}
