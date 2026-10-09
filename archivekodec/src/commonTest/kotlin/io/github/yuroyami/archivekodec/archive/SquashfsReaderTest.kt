package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.squashfs.SquashfsReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SquashFS 4.0 (#32), gzipped, from mksquashfs 4.6.1 with 4 KiB blocks (`-b 4096 -nopad`).
 * Every image holds sub/file.txt ("hello"), text.txt (14931 bytes of words: three blocks and
 * a tail fragment), sparse.bin (8192 zeros stored as holes, then a tail), small0 to small2
 * (sharing a fragment) and link -> sub/file.txt. The gzip image adds noise.bin (4200 random
 * bytes: an uncompressed block), many/ (400 empty files, an indexed extended directory),
 * empty-dir/, empty.txt, a UTF-8 name, the hard link hardlink.txt to small1.txt, an xattr on
 * small0.txt (an extended inode), a FIFO and a character device. All modified
 * 2024-05-06T07:08:10Z. Each image is checked against unsquashfs.
 */
@OptIn(ExperimentalEncodingApi::class)
class SquashfsReaderTest {
    private val gzipGz = Base64.decode(
        "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",
    )

    private val lzoGz = Base64.decode(
        "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",
    )

    private val lz4Gz = Base64.decode(
        "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" +
        "3TKuz77K/tPiEX9d//+7OPo/Ccn0BQkXAAA=",
    )

    private val xzGz = Base64.decode(
        "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" +
        "2LjIxv8byePsipC9irfY2f8AU55L54cKAAA=",
    )

    private val zstdGz = Base64.decode(
        "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",
    )

    private val lzmaGz = Base64.decode(
        "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",
    )

    private fun image(gz: ByteArray) = Gzip.decompress(gz, ArchiveLimits.UNBOUNDED)

    private fun reader(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT) = SquashfsReader(ByteArrayRandomAccessSource(bytes), limits)

    private val all get() = listOf(gzipGz, lzoGz, lz4Gz, xzGz, zstdGz, lzmaGz)

    @Test
    fun sniffsAndOpens() {
        for (gz in all) {
            val bytes = image(gz)
            assertEquals(ArchiveFormat.SQUASHFS, Magic.sniff(bytes))
            ArchiveKodec.open(bytes).use { assertEquals(ArchiveFormat.SQUASHFS, it.format) }
        }
    }

    @Test
    fun everyCompressorReadsTheSameFiles() {
        for (gz in all) {
            reader(image(gz)).use {
                val entries = it.entries().associateBy { e -> e.name }
                assertContentEquals("hello".encodeToByteArray(), it.read(entries.getValue("sub/file.txt")))
                assertEquals(0xcd667765L, Crc32.of(it.read(entries.getValue("text.txt"))))
                assertContentEquals(ByteArray(8192) + "end of sparse\n".encodeToByteArray(), it.read(entries.getValue("sparse.bin")))
                for (i in 0 until 3) assertContentEquals("small file $i\n".encodeToByteArray(), it.read(entries.getValue("small$i.txt")))
                val link = entries.getValue("link")
                assertEquals(0xA000, link.unixMode!! and 0xF000)
                assertContentEquals("sub/file.txt".encodeToByteArray(), it.read(link))
                assertTrue(entries.getValue("sub/").isDirectory)
                for (entry in entries.values) assertEquals(1714979290L, entry.lastModifiedEpochSeconds, entry.name)
            }
        }
    }

    @Test
    fun fullTreeTypesAndBlocks() {
        reader(image(gzipGz)).use {
            val entries = it.entries().associateBy { e -> e.name }
            assertEquals(400, entries.keys.count { n -> n.startsWith("many/") && n != "many/" })
            assertEquals(0x41ED, entries.getValue("empty-dir/").unixMode)
            assertEquals(0x81A4, entries.getValue("text.txt").unixMode)
            assertEquals(0x1000, entries.getValue("fifo").unixMode!! and 0xF000)
            assertEquals(0x2000, entries.getValue("null").unixMode!! and 0xF000)
            assertContentEquals(ByteArray(0), it.read(entries.getValue("fifo")))
            assertEquals(0xaaa0992fL, Crc32.of(it.read(entries.getValue("noise.bin"))))
            assertContentEquals(it.read(entries.getValue("small1.txt")), it.read(entries.getValue("hardlink.txt")))
            assertContentEquals("small file 0\n".encodeToByteArray(), it.read(entries.getValue("small0.txt")))
            assertContentEquals("utf-8 name\n".encodeToByteArray(), it.read(entries.getValue("caf\u00e9.txt")))
            assertContentEquals(ByteArray(0), it.read(entries.getValue("empty.txt")))
            assertContentEquals(ByteArray(0), it.read(entries.getValue("many/entry-with-a-longish-name-399.txt")))
        }
    }

    @Test
    fun refusalsDamageAndLimits() {
        val bytes = image(gzipGz)
        val version = bytes.copyOf().also { it[28] = 3 }
        assertTrue("version" in assertFailsWith<ArchiveUnsupportedException> { reader(version).entries() }.message!!)
        val compression = bytes.copyOf().also { it[20] = 9 }
        assertFailsWith<ArchiveUnsupportedException> { reader(compression).entries() }
        assertFailsWith<ArchiveFormatException> { reader(bytes.copyOf(50)).entries() }
        assertFailsWith<ArchiveFormatException> { reader(bytes.copyOf(bytes.size - 100)).entries() }
        assertFailsWith<DecompressionBombException> { reader(bytes, ArchiveLimits { maxEntries = 10 }).entries() }
        reader(bytes, ArchiveLimits { maxUncompressedBytes = 10_000 }).use {
            assertFailsWith<DecompressionBombException> { it.read(it.entries().single { e -> e.name == "text.txt" }) }
        }
    }

    @Test
    fun damagedImagesFailCleanly() {
        for (gz in listOf(gzipGz, lzoGz, lz4Gz)) {
            val original = image(gz)
            val random = Random(original.size)
            repeat(120) {
                val bytes = original.copyOf()
                repeat(1 + random.nextInt(3)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
                try {
                    reader(bytes).use { r -> for (entry in r.entries()) r.read(entry) }
                } catch (_: ArchiveException) {
                    // Damage must surface as an archive error, never another exception or a hang.
                }
            }
        }
    }
}
