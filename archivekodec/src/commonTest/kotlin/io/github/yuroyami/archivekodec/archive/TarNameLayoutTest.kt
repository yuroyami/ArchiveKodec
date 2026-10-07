package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveKodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TarNameLayoutTest {
    private val payload = "Archive member payload\u0000".encodeToByteArray() + byteArrayOf(0xff.toByte())
    private fun hex(value: String) = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    // Frozen complete records from independently verified reference archives.
    private val producedUstar = hex(
        "7365676d656e742f7365676d656e742f7365676d656e742f7365676d656e742f7365676d656e742f7365676d656e742f" +
            "7365676d656e742f7365676d656e742f7365676d656e742f7365676d656e742f6c6f6e672d66696c652d6e616d652e74" +
            "787400003030303036343400303030303030300030303030303030003030303030303030303330003134353234373730" +
            "343030003034323331340020300000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000075737461720030300000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000007365676d656e742f7365676d656e742f7365676d656e742f7365676d656e742f7365676d656e74" +
            "2f7365676d656e7400000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000"
    )
    private val oldGnu = hex(
        "666f6f2e7478740000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000003030303036343400303030303030300030303030303030003030303030303030303330003134353234373730" +
            "343030003031323336350020300000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000075737461722020000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000313430303030303030303000313430303030303030303100303030303030303031323300000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000"
    )

    private fun checksum(header: ByteArray) {
        header.fill(32, 148, 156)
        (header.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ")
            .encodeToByteArray().copyInto(header, 148)
    }

    private fun header(name: String, marker: ByteArray, tail: ByteArray, data: ByteArray = payload, type: Char = '0'): ByteArray =
        producedUstar.copyOf().also {
            it.fill(0, 0, 100)
            name.encodeToByteArray().copyInto(it)
            it.fill(0, 257, 265)
            marker.copyInto(it, 257)
            it.fill(0, 345, 500)
            tail.copyInto(it, 345)
            it.fill(0, 124, 136)
            data.size.toString(8).padStart(11, '0').encodeToByteArray().copyInto(it, 124)
            it[156] = type.code.toByte()
            checksum(it)
        }

    private fun record(header: ByteArray, data: ByteArray = payload) =
        header + data + ByteArray((512 - data.size % 512) % 512)

    private fun checkArchive(bytes: ByteArray, names: List<String>) {
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { reader ->
            assertEquals(ArchiveFormat.TAR, reader.format)
            val entries = reader.entries()
            assertEquals(names, entries.map { it.name })
            assertEquals(entries, reader.entries())
            for (entry in entries) {
                assertEquals(payload.size.toLong(), entry.size)
                assertEquals(1700000000L, entry.lastModifiedEpochSeconds)
                assertFalse(entry.isDirectory)
                assertContentEquals(payload, reader.read(entry))
            }
        }
    }

    @Test
    fun independentUstarLongPathAndOldGnuTimeRecordsKeepTheirNames() {
        checkArchive(record(producedUstar) + ByteArray(1024), listOf("segment/".repeat(16) + "long-file-name.txt"))
        checkArchive(record(oldGnu) + ByteArray(1024), listOf("foo.txt"))
    }

    @Test
    fun knownUstarLayoutPreservesPrefixesAcrossVersionVariations() {
        for (version in listOf(byteArrayOf(48, 48), byteArrayOf(0, 0), byteArrayOf(32, 32), byteArrayOf(48, 49), byteArrayOf(65, 66))) {
            val h = header("foo.txt", "ustar\u0000".encodeToByteArray() + version, "dir/sub".encodeToByteArray())
            checkArchive(record(h) + ByteArray(1024), listOf("dir/sub/foo.txt"))
        }
    }

    @Test
    fun oldGnuExtensionFieldsAreNeverAPrefix() {
        for (tail in listOf(
            "14000000000\u000014000000001\u000000000000123\u0000".encodeToByteArray(),
            byteArrayOf(0x80.toByte()) + ByteArray(154) { 0xff.toByte() },
            "dir/sub".encodeToByteArray(),
        )) {
            val h = header("foo.txt", "ustar  \u0000".encodeToByteArray(), tail)
            checkArchive(record(h) + ByteArray(1024), listOf("foo.txt"))
        }
    }

    @Test
    fun v7UnknownAndIncompleteUstarMarkersIgnoreUnusedTailBytes() {
        for (marker in listOf(ByteArray(8), "other\u0000\u0000\u0000".encodeToByteArray(), "ustarX00".encodeToByteArray(), "ustar \u0000\u0000".encodeToByteArray(), "usta\u0000000".encodeToByteArray())) {
            val h = header("foo.txt", marker, "ignored/junk".encodeToByteArray())
            checkArchive(record(h) + ByteArray(1024), listOf("foo.txt"))
        }
    }

    @Test
    fun fullWidthAndTerminatedFieldsPreserveSpacesAndSeparators() {
        val exact = header("n".repeat(100), "ustar\u000000".encodeToByteArray(), "p".repeat(155).encodeToByteArray())
        checkArchive(record(exact) + ByteArray(1024), listOf("p".repeat(155) + "/" + "n".repeat(100)))
        val raw = header("file name.txt ", "ustar\u000000".encodeToByteArray(), " leading /sub/".encodeToByteArray())
        checkArchive(record(raw) + ByteArray(1024), listOf(" leading /sub//file name.txt "))
        val terminated = header("actual\u0000ignored", "ustar\u000000".encodeToByteArray(), "prefix\u0000ignored".encodeToByteArray())
        checkArchive(record(terminated) + ByteArray(1024), listOf("prefix/actual"))
    }

    @Test
    fun gnuLongNamesOverrideEveryFollowingHeaderLayout() {
        val name = "long/".repeat(30) + "actual.txt"
        val longData = name.encodeToByteArray() + byteArrayOf(0)
        val longHeader = header("././@LongLink", "ustar  \u0000".encodeToByteArray(), byteArrayOf(), longData, 'L')
        for (marker in listOf("ustar  \u0000".encodeToByteArray(), "ustar\u000000".encodeToByteArray(), ByteArray(8))) {
            val following = header("truncated-base", marker, "ignored-tail".encodeToByteArray())
            val normal = header("next.txt", "ustar\u000000".encodeToByteArray(), "dir".encodeToByteArray())
            checkArchive(record(longHeader, longData) + record(following) + record(normal) + ByteArray(1024), listOf(name, "dir/next.txt"))
        }
    }

    @Test
    fun mixedLayoutsAreSelectedForEachRecord() {
        val gnu = header("first", "ustar  \u0000".encodeToByteArray(), "14000000000".encodeToByteArray())
        val posix = header("second", "ustar\u000000".encodeToByteArray(), "dir".encodeToByteArray())
        val v7 = header("third", ByteArray(8), "ignored".encodeToByteArray())
        checkArchive(record(gnu) + record(posix) + record(v7) + ByteArray(1024), listOf("first", "dir/second", "third"))
    }
}
