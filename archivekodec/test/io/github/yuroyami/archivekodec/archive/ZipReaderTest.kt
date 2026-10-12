package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ZipReaderTest {

    // A real ZIP produced by CPython zipfile: stored + deflated + a nested dir entry.
    private val zipVector = hex(
        "504b030414000000000000002100308ce92a28000000280000000a00000073746f7265642e74787441414141414141414141414141414141414141414141414141414141414141414141414141414141504b0304140000000800000021004f755efe32000000600100000c0000006465666c617465642e7478742bc94855282ccd4cce56482aca2fcf5348cbaf50c82acd2d2856c82f4b2d5228014ae72456552aa4e4a78339a36a49530b00504b03041400000000005111dc5c98ed293113000000130000000e0000006469722f6e65737465642e7478746e657374656420636f6e74656e742068657265504b0102140314000000000000002100308ce92a28000000280000000a000000000000000000000080010000000073746f7265642e747874504b01021403140000000800000021004f755efe32000000600100000c00000000000000000000008001500000006465666c617465642e747874504b010214031400000000005111dc5c98ed293113000000130000000e00000000000000000000008001ac0000006469722f6e65737465642e747874504b05060000000003000300ae000000eb0000000000",
    )

    @Test
    fun listsAndExtractsRealZip() {
        val reader = ZipReader(ByteArrayRandomAccessSource(zipVector))
        val byName = reader.entries().associateBy { it.name }

        assertEquals(3, byName.size)
        assertContentEquals(ByteArray(40) { 'A'.code.toByte() }, reader.read(byName.getValue("stored.txt")))
        assertContentEquals(
            "the quick brown fox jumps over the lazy dog ".repeat(8).encodeToByteArray(),
            reader.read(byName.getValue("deflated.txt")),
        )
        assertEquals("nested content here", reader.read(byName.getValue("dir/nested.txt")).decodeToString())
    }

    @Test
    fun writerReaderRoundTrip() {
        val big = ("lorem ipsum dolor sit amet ").repeat(200).encodeToByteArray()
        val files = listOf(
            ZipWriter.FileSpec("hello.txt", "Hello, ArchiveKodec!".encodeToByteArray(), ZipMethod.STORED),
            ZipWriter.FileSpec("big.txt", big, ZipMethod.DEFLATED),
        )
        val bytes = ZipWriter.write(files)

        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        val byName = reader.entries().associateBy { it.name }
        assertEquals(2, byName.size)
        assertEquals("Hello, ArchiveKodec!", reader.read(byName.getValue("hello.txt")).decodeToString())
        assertContentEquals(big, reader.read(byName.getValue("big.txt")))
    }

}
