package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.checksum.XxHash32Streaming
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.lz4.Lz4BlockSize
import io.github.yuroyami.archivekodec.codec.lz4.Lz4Options
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.test.Test
import kotlin.test.assertEquals

/** The streaming LZ4 decoder against the whole-array one: same bytes, same failures. */
class Lz4StreamingTest {
    private fun data(size: Int, seed: Int): ByteArray {
        var x = seed
        val words = listOf("lz4 ", "frame ", "block ", "linked ", "history ").map { it.encodeToByteArray() }
        val out = ByteArrayBuilder(size)
        while (out.size < size) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            if (x and 7 == 0) out.append(byteArrayOf(x.toByte())) else out.append(words[(x ushr 1) % words.size])
        }
        return out.takeByteArray().copyOf(size)
    }

    private fun trickle(bytes: ByteArray, step: Int): ByteSource {
        var position = 0
        return ByteSource { into, offset, length ->
            if (position == bytes.size) -1 else {
                val n = minOf(length, step, bytes.size - position)
                bytes.copyInto(into, offset, position, position + n)
                position += n
                n
            }
        }
    }

    /** The decoded bytes, or the failure's class name. */
    private fun arrayOutcome(stream: ByteArray, limits: ArchiveLimits): String = try {
        Lz4.frameDecompress(stream, limits).decodeToString()
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun streamOutcome(stream: ByteArray, step: Int, limits: ArchiveLimits): String = try {
        val out = ByteArrayBuilder(1024)
        val count = Lz4.frameDecompress(trickle(stream, step), { b, o, l -> out.append(b, o, l) }, limits)
        val bytes = out.takeByteArray()
        assertEquals(bytes.size.toLong(), count)
        bytes.decodeToString()
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun assertSame(stream: ByteArray, what: String, limits: ArchiveLimits = ArchiveLimits.DEFAULT, steps: List<Int> = listOf(1, 7, 65_536)) {
        val expected = arrayOutcome(stream, limits)
        for (step in steps) assertEquals(expected, streamOutcome(stream, step, limits), "$what, step $step")
    }

    private val variants = buildList {
        for (linked in listOf(true, false)) for (blockChecksum in listOf(true, false)) for (contentChecksum in listOf(true, false)) {
            add(Lz4Options { blockSize = Lz4BlockSize.entries.first(); linkedBlocks = linked; this.blockChecksum = blockChecksum
                this.contentChecksum = contentChecksum; contentSize = linked xor blockChecksum })
        }
    }

    @Test
    fun streamedXxHash32MatchesTheOneShotHashAtEverySplit() {
        val input = data(200, 3)
        for (length in listOf(0, 1, 15, 16, 17, 31, 32, 33, 200)) for (split in 0..length) for (second in split..length) {
            val h = XxHash32Streaming()
            h.update(input, 0, split); h.update(input, split, second - split); h.update(input, second, length - second)
            assertEquals(XxHash32.hash(input, 0, length), h.value(), "length $length split $split/$second")
        }
    }

    @Test
    fun everyFrameLayoutDecodesTheSame() {
        // Past several 64 KiB blocks, so linked blocks reach into the history.
        val input = data(300_000, 5)
        for (options in variants) {
            val frame = Codecs.LZ4.compress(input, options)
            assertSame(frame, "$options", steps = listOf(7, 65_536))
            assertSame(frame + frame, "$options twice", steps = listOf(65_536))
        }
        val skippable = hex("5a2a4d18" + "03000000" + "aabbcc")
        val small = Codecs.LZ4.compress(data(5000, 6))
        assertSame(skippable + small + skippable, "skippable around a frame")
        assertSame(skippable, "skippable only")
        val legacyBlock = Lz4.blockCompress(data(4000, 7))
        val size = legacyBlock.size
        val legacy = hex("02214c18") + byteArrayOf(size.toByte(), (size ushr 8).toByte(), 0, 0) + legacyBlock
        assertSame(legacy + small, "legacy then standard")
        assertSame(ByteArray(0), "empty input")
    }

    @Test
    fun truncationsCorruptionsAndLimitsFailTheSameWay() {
        for (options in variants) {
            val frame = Codecs.LZ4.compress(data(3000, 8), options)
            for (cut in 0 until frame.size step 5) assertSame(frame.copyOf(cut), "$options cut at $cut", steps = listOf(13))
            for (at in frame.indices step 3) {
                val bad = frame.copyOf().also { it[at] = (it[at].toInt() xor 0x41).toByte() }
                assertSame(bad, "$options byte $at", steps = listOf(13))
            }
        }
        val frame = Codecs.LZ4.compress(ByteArray(500_000))
        assertSame(frame, "size limit", ArchiveLimits { maxUncompressedBytes = 100_000 }, listOf(4096))
        assertSame(frame, "ratio limit", ArchiveLimits { maxCompressionRatio = 50 }, listOf(4096))
    }
}
