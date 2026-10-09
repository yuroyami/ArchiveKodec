package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.deflate.DeflateStrategy
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.lz4.Lz4BlockSize
import io.github.yuroyami.archivekodec.codec.lz4.Lz4Options
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.GzipHeader
import io.github.yuroyami.archivekodec.wrapper.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Encoder options (#86). */
class EncoderOptionsTest {
    private val words = "the archive stream block huffman window match literal kotlin deflate compression entropy".split(" ")

    private fun text(size: Int, seed: Long = 1): ByteArray {
        var x = seed
        val out = StringBuilder()
        while (out.length < size) {
            x = (x * 6364136223846793005L + 1442695040888963407L)
            out.append(words[((x ushr 33) % words.size).toInt()]).append(if ((x ushr 20) % 9 == 0L) '\n' else ' ')
        }
        return out.substring(0, size).encodeToByteArray()
    }

    private fun random(size: Int) = ByteArray(size).also { var x = 9L; for (i in it.indices) { x = x * 6364136223846793005L + 1442695040888963407L; it[i] = (x ushr 56).toByte() } }

    private val mixed = text(60_000) + random(20_000) + ByteArray(30_000) + text(40_000, 2)

    @Test
    fun everyLevelStrategyAndWindowRoundTrips() {
        for (level in 0..9) for (strategy in DeflateStrategy.entries) {
            val options = DeflateOptions { this.level = level; this.strategy = strategy }
            assertContentEquals(mixed, Inflate.inflate(Deflate.encode(mixed, options)), "$options")
        }
        for (windowBits in 9..15) {
            val options = DeflateOptions { this.windowBits = windowBits }
            assertContentEquals(mixed, Inflate.inflate(Deflate.encode(mixed, options)), "$options")
            val zlib = Zlib.compress(mixed, options)
            assertEquals(0x08 or ((windowBits - 8) shl 4), zlib[0].toInt() and 0xFF)
            assertContentEquals(mixed, Zlib.decompress(zlib))
        }
        for (data in listOf(ByteArray(0), byteArrayOf(1), byteArrayOf(1, 2, 3))) {
            for (level in 0..9) assertContentEquals(data, Inflate.inflate(Deflate.encode(data, DeflateOptions { this.level = level })))
        }
    }

    @Test
    fun levelsTradeSizeAndTheDefaultIsLevelSix() {
        val sizes = (0..9).map { Deflate.encode(mixed, DeflateOptions { level = it }).size }
        assertTrue(sizes[1] > sizes[9], "$sizes")
        assertTrue(sizes[4] >= sizes[9], "$sizes")
        assertContentEquals(Deflate.encode(mixed, DeflateOptions.DEFAULT), Deflate.encode(mixed))
        // Level 0 only stores: five bytes of framing per 65,535-byte block.
        val incompressible = random(200_000)
        val stored = Deflate.encode(incompressible, DeflateOptions { level = 0 })
        assertTrue(stored.size <= incompressible.size + 5 * (incompressible.size / 65_535 + 1), "${stored.size}")
        // Huffman-only and RLE skip ordinary matches, so text compresses less than by default.
        val huffman = Deflate.encode(mixed, DeflateOptions { strategy = DeflateStrategy.HUFFMAN_ONLY }).size
        val rle = Deflate.encode(mixed, DeflateOptions { strategy = DeflateStrategy.RLE }).size
        assertTrue(huffman > sizes[6] && rle > sizes[6], "huffman $huffman rle $rle default ${sizes[6]}")
    }

    @Test
    fun zlibCarriesAPresetDictionary() {
        val dictionary = text(4000, 5)
        val data = text(3000, 5).copyOfRange(1000, 3000)
        val options = DeflateOptions { this.dictionary = dictionary }
        val stream = Zlib.compress(data, options)
        assertEquals(0x20, stream[1].toInt() and 0x20, "FDICT")
        assertTrue(stream.size < Zlib.compress(data).size / 2, "the dictionary must shorten the stream")
        assertContentEquals(data, Zlib.decompress(stream, ArchiveLimits.DEFAULT, dictionary))
        assertFailsWith<ArchiveUnsupportedException> { Zlib.decompress(stream) }
        assertFailsWith<ArchiveFormatException> { Zlib.decompress(stream, ArchiveLimits.DEFAULT, text(4000, 6)) }
        assertContentEquals(data, Inflate.inflate(Deflate.encode(data, options), ArchiveLimits.DEFAULT, dictionary))
        // The dictionary does not count against the output limit.
        assertContentEquals(data, Zlib.decompress(stream, ArchiveLimits { maxUncompressedBytes = data.size.toLong() }, dictionary))
        assertFailsWith<IllegalArgumentException> { Gzip.compress(data, GzipHeader.DEFAULT, options) }
        assertFailsWith<IllegalArgumentException> { ZipWriter.FileSpec("a", data) { deflateOptions = options } }
    }

    @Test
    fun codecsTakeTheirOwnOptionsOnly() {
        val level1 = DeflateOptions { level = 1 }
        assertContentEquals(mixed, Codecs.DEFLATE.decompress(Codecs.DEFLATE.compress(mixed, level1)))
        assertContentEquals(mixed, Codecs.ZLIB.decompress(Codecs.ZLIB.compress(mixed, level1)))
        assertContentEquals(mixed, Codecs.GZIP.decompress(Codecs.GZIP.compress(mixed, level1)))
        assertContentEquals(mixed, Codecs.LZ4.decompress(Codecs.LZ4.compress(mixed, Lz4Options { blockChecksum = true })))
        assertFailsWith<IllegalArgumentException> { Codecs.DEFLATE.compress(mixed, Lz4Options.DEFAULT) }
        assertFailsWith<IllegalArgumentException> { Codecs.LZ4.compress(mixed, level1) }
        assertFailsWith<IllegalArgumentException> { Codecs.BZIP2.compress(mixed, level1) }
        assertFailsWith<IllegalArgumentException> { DeflateOptions { level = 10 } }
        assertFailsWith<IllegalArgumentException> { DeflateOptions { windowBits = 8 } }
        assertEquals(DeflateOptions { dictionary = byteArrayOf(1) }, DeflateOptions { dictionary = byteArrayOf(1) })
        assertNotEquals(DeflateOptions { dictionary = byteArrayOf(1) }, DeflateOptions { dictionary = byteArrayOf(2) })
    }

    @Test
    fun zipEntriesTakeDeflateOptions() {
        val files = listOf(
            ZipWriter.FileSpec("fast.txt", mixed) { deflateOptions = DeflateOptions { level = 1 } },
            ZipWriter.FileSpec("small.txt", mixed) { deflateOptions = DeflateOptions { level = 9 } },
        )
        ZipReader(ByteArrayRandomAccessSource(ZipWriter.write(files))).use { reader ->
            val entries = reader.entries()
            for (entry in entries) assertContentEquals(mixed, reader.read(entry))
            assertTrue(entries[0].compressedSize > entries[1].compressedSize)
        }
    }

    @Test
    fun lz4FramesFollowTheirOptions() {
        val data = mixed + mixed
        for (size in Lz4BlockSize.entries) for (flags in 0 until 16) {
            val options = Lz4Options {
                blockSize = size
                blockChecksum = flags and 1 != 0
                contentChecksum = flags and 2 != 0
                contentSize = flags and 4 != 0
                linkedBlocks = flags and 8 != 0
            }
            val frame = Lz4.frameCompress(data, options)
            val flg = frame[4].toInt() and 0xFF
            assertEquals(0x40 or (if (options.linkedBlocks) 0 else 0x20) or (if (options.blockChecksum) 0x10 else 0) or
                (if (options.contentSize) 0x08 else 0) or (if (options.contentChecksum) 0x04 else 0), flg, "$options")
            assertEquals(size.code shl 4, frame[5].toInt() and 0xFF)
            assertContentEquals(data, Lz4.frameDecompress(frame), "$options")
        }
        assertContentEquals(Lz4.frameCompress(data), Lz4.frameCompress(data, Lz4Options.DEFAULT))
        // Linked 64 KiB blocks reach back into earlier blocks and compress better.
        val linked = Lz4.frameCompress(data, Lz4Options { blockSize = Lz4BlockSize.KB_64; linkedBlocks = true })
        val independent = Lz4.frameCompress(data, Lz4Options { blockSize = Lz4BlockSize.KB_64 })
        assertTrue(linked.size < independent.size, "${linked.size} vs ${independent.size}")
    }
}
