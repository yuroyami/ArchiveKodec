package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.wim.WimReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
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
 * Windows images (#34), gzipped, from wimlib-imagex 1.14.4 `capture` with `--compress=none`,
 * `XPRESS` and `LZX` (32 KiB chunks), and `two` (the LZX image with a second image appended).
 * The tree: big.bin (1500 random bytes repeated to 40000, so two chunks), zeros.bin (40000
 * zeros), text.txt (9987 bytes of words) and its hard link hardlink.txt, docs/readme.txt and
 * an identical docs/copy-of-readme.txt, empty.txt, empty-dir/ and a name with non-ASCII
 * letters; everything modified 2024-05-06T07:08:10Z. Image 2 holds only-in-two.txt.
 */
@OptIn(ExperimentalEncodingApi::class)
class WimReaderTest {
    private val noneGz = Base64.decode(
        "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",
    )

    private val xpressGz = Base64.decode(
        "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",
    )

    private val lzxGz = Base64.decode(
        "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",
    )

    private val twoGz = Base64.decode(
        "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",
    )

    private fun wim(gz: ByteArray) = Gzip.decompress(gz, ArchiveLimits.UNBOUNDED)

    private fun reader(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT) = WimReader(ByteArrayRandomAccessSource(bytes), limits)

    private val readme = "WIM test image\n".repeat(40).encodeToByteArray()
    private val names = setOf(
        "big.bin", "zeros.bin", "text.txt", "hardlink.txt", "docs/", "docs/readme.txt", "docs/copy-of-readme.txt",
        "empty.txt", "empty-dir/", "caf\u00e9 \u65e5\u672c.txt",
    )

    @Test
    fun sniffsAndOpens() {
        for (gz in listOf(noneGz, xpressGz, lzxGz, twoGz)) {
            val bytes = wim(gz)
            assertEquals(ArchiveFormat.WIM, Magic.sniff(bytes))
            ArchiveKodec.open(bytes).use { assertEquals(ArchiveFormat.WIM, it.format) }
        }
    }

    @Test
    fun readsEachCompression() {
        for ((gz, codec) in listOf(noneGz to CodecId.STORE, xpressGz to CodecId("xpress"), lzxGz to CodecId("lzx"))) {
            reader(wim(gz)).use {
                val entries = it.entries().associateBy { e -> e.name }
                assertEquals(names, entries.keys)
                for (entry in entries.values) assertEquals(1714979290L, entry.lastModifiedEpochSeconds, entry.name)
                assertTrue(entries.getValue("docs/").isDirectory && entries.getValue("empty-dir/").isDirectory)
                assertEquals(codec, entries.getValue("big.bin").codec)
                assertEquals(40000L, entries.getValue("big.bin").size)
                assertEquals(0x5d1e37cdL, Crc32.of(it.read(entries.getValue("big.bin"))))
                assertContentEquals(ByteArray(40000), it.read(entries.getValue("zeros.bin")))
                val text = it.read(entries.getValue("text.txt"))
                assertEquals(0x0da73fb9L, Crc32.of(text))
                assertContentEquals(text, it.read(entries.getValue("hardlink.txt")))
                assertContentEquals(readme, it.read(entries.getValue("docs/readme.txt")))
                assertContentEquals(ByteArray(0), it.read(entries.getValue("empty.txt")))
                assertContentEquals("unicode name\n".encodeToByteArray(), it.read(entries.getValue("caf\u00e9 \u65e5\u672c.txt")))
            }
        }
    }

    @Test
    fun identicalFilesShareOneResource() {
        reader(wim(xpressGz)).use {
            val entries = it.entries().associateBy { e -> e.name }
            val original = entries.getValue("docs/readme.txt")
            val copy = entries.getValue("docs/copy-of-readme.txt")
            assertEquals(original.dataOffset, copy.dataOffset)
            assertContentEquals(readme, it.read(copy))
            assertContentEquals(readme, it.read(original))
        }
    }

    @Test
    fun imagesAreNumberedDirectories() {
        reader(wim(twoGz)).use {
            val entries = it.entries().associateBy { e -> e.name }
            assertEquals(names.map { n -> "1/$n" }.toSet() + setOf("1/", "2/", "2/only-in-two.txt"), entries.keys)
            assertContentEquals("second image\n".encodeToByteArray(), it.read(entries.getValue("2/only-in-two.txt")))
            assertEquals(0x5d1e37cdL, Crc32.of(it.read(entries.getValue("1/big.bin"))))
        }
    }

    @Test
    fun refusalsDamageAndLimits() {
        // LZMS (solid .esd images) is refused by name.
        val lzms = wim(xpressGz).also { it[18] = (it[18].toInt() and 0x02.inv() or 0x08).toByte() }
        val error = assertFailsWith<ArchiveUnsupportedException> { reader(lzms).entries() }
        assertTrue("LZMS" in error.message!!, error.message)

        // A changed byte of stored data fails its SHA-1.
        val none = wim(noneGz)
        val offset = reader(none).use { it.entries().single { e -> e.name == "big.bin" }.dataOffset }
        val damaged = none.copyOf().also { it[(offset + 1000).toInt()] = (it[(offset + 1000).toInt()] + 1).toByte() }
        reader(damaged).use { r ->
            val error2 = assertFailsWith<ArchiveFormatException> { r.read(r.entries().single { e -> e.name == "big.bin" }) }
            assertTrue("SHA-1" in error2.message!!, error2.message)
        }

        assertFailsWith<ArchiveFormatException> { reader(none.copyOf(100)).entries() }
        assertFailsWith<ArchiveFormatException> { reader(none.copyOf(none.size - 2000)).entries() }
        assertFailsWith<DecompressionBombException> { reader(none, ArchiveLimits { maxEntries = 3 }).entries() }
        reader(wim(lzxGz), ArchiveLimits { maxUncompressedBytes = 20_000 }).use {
            assertFailsWith<DecompressionBombException> { it.read(it.entries().single { e -> e.name == "big.bin" }) }
        }
    }

    @Test
    fun damagedChunksFailCleanly() {
        for (gz in listOf(xpressGz, lzxGz)) {
            val original = wim(gz)
            val random = Random(gz.size)
            repeat(150) {
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
