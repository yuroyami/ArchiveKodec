package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Zlib
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InflateOptionsTest {
    private val text = "the quick brown fox jumps over the lazy dog ".repeat(1000).encodeToByteArray()
    private val raw = Deflate.encode(text)
    private val zlib = Zlib.compress(text)
    private val before = byteArrayOf(9, 8, 7)
    private val after = byteArrayOf(1, 2, 3, 4, 5, 6)

    private fun isPrefix(part: ByteArray, whole: ByteArray) = part.size <= whole.size && part.contentEquals(whole.copyOf(part.size))

    @Test
    fun defaultsDecodeAsTheStrictOverloads() {
        for (result in listOf(Inflate.inflate(raw, InflateOptions.DEFAULT), Zlib.decompress(zlib, InflateOptions.DEFAULT))) {
            assertContentEquals(text, result.output)
            assertNull(result.error)
            assertTrue(result.isComplete)
        }
        assertEquals(raw.size, Inflate.inflate(raw, InflateOptions.DEFAULT).inputConsumed)
        assertEquals(zlib.size, Zlib.decompress(zlib, InflateOptions.DEFAULT).inputConsumed)
        assertEquals(InflateOptions.DEFAULT, InflateOptions())
        assertEquals(InflateOptions.DEFAULT.hashCode(), InflateOptions().hashCode())
        assertNotEquals(InflateOptions.DEFAULT, InflateOptions { allowTrailingData = true })
        assertEquals(InflateOptions { dictionary = byteArrayOf(1) }, InflateOptions { dictionary = byteArrayOf(1) })
        assertFailsWith<IllegalArgumentException> { InflateOptions { sizeHint = -1 } }
    }

    @Test
    fun aRangeIsReadInPlaceAndReportsWhereTheStreamEnds() {
        val lenient = InflateOptions { allowTrailingData = true }
        for ((stream, decode) in listOf<Pair<ByteArray, (ByteArray, Int, Int, InflateOptions) -> InflateResult>>(
            raw to Inflate::inflate, zlib to Zlib::decompress,
        )) {
            val input = before + stream + after
            val result = decode(input, before.size, input.size - before.size, lenient)
            assertContentEquals(text, result.output)
            assertEquals(stream.size, result.inputConsumed)
            assertTrue(result.isComplete)
            // The exact range needs no leniency; a longer one is an error at the first byte after the stream.
            assertEquals(stream.size, decode(input, before.size, stream.size, InflateOptions.DEFAULT).inputConsumed)
            val error = assertFailsWith<ArchiveFormatException> { decode(input, before.size, stream.size + 1, InflateOptions.DEFAULT) }
            assertEquals((before.size + stream.size).toLong(), error.offset)
            for ((offset, length) in listOf(-1 to 1, 0 to -1, 1 to input.size, input.size + 1 to 0)) {
                assertFailsWith<IllegalArgumentException> { decode(input, offset, length, lenient) }
            }
        }
    }

    @Test
    fun aCutShortStreamKeepsWhatWasDecoded() {
        val keep = InflateOptions { keepPartialOutput = true }
        for ((stream, decode) in listOf<Pair<ByteArray, (ByteArray, InflateOptions) -> InflateResult>>(
            raw to Inflate::inflate, zlib to Zlib::decompress,
        )) {
            val cut = stream.copyOf(stream.size / 2)
            assertFailsWith<InflateException> { decode(cut, InflateOptions.DEFAULT) }
            val result = decode(cut, keep)
            assertFalse(result.isComplete)
            assertEquals(InflateError.DATA_DID_NOT_TERMINATE, assertIs<InflateException>(result.error).error)
            assertTrue(result.output.isNotEmpty() && result.output.size < text.size, "kept ${result.output.size}")
            assertTrue(isPrefix(result.output, text))
            assertEquals(cut.size, result.inputConsumed)
        }
    }

    @Test
    fun aDamagedBlockKeepsTheBlocksBeforeIt() {
        // A stored block "abc", then a block of the reserved type 3.
        val stream = hex("000300fcff61626307")
        assertFailsWith<InflateException> { Inflate.inflate(stream, InflateOptions.DEFAULT) }
        val result = Inflate.inflate(stream, InflateOptions { keepPartialOutput = true })
        assertContentEquals("abc".encodeToByteArray(), result.output)
        assertEquals(InflateError.INVALID_BLOCK_TYPE, assertIs<InflateException>(result.error).error)
    }

    @Test
    fun trailingBytesAreAnErrorThatKeepsTheWholeOutput() {
        val result = Inflate.inflate(raw + after, InflateOptions { keepPartialOutput = true })
        assertContentEquals(text, result.output)
        assertEquals(raw.size, result.inputConsumed)
        assertIs<ArchiveFormatException>(result.error)
    }

    @Test
    fun limitsThrowEvenWhenPartialOutputIsKept() {
        val tight = InflateOptions {
            keepPartialOutput = true
            limits = ArchiveLimits { maxUncompressedBytes = 100 }
        }
        assertFailsWith<DecompressionBombException> { Inflate.inflate(raw, tight) }
        assertFailsWith<DecompressionBombException> { Zlib.decompress(zlib, tight) }
    }

    @Test
    fun aSizeHintNeverChangesTheOutput() {
        for (hint in longArrayOf(1, text.size - 1L, text.size.toLong(), text.size + 1L, 10L * text.size)) {
            assertContentEquals(text, Inflate.inflate(raw, InflateOptions { sizeHint = hint }).output, "hint $hint")
            assertContentEquals(text, Zlib.decompress(zlib, InflateOptions { sizeHint = hint }).output, "hint $hint")
        }
    }

    @Test
    fun aPresetDictionaryIsHistoryNotOutput() {
        val dictionary = "the quick brown fox jumps over the lazy dog ".encodeToByteArray()
        val primed = DeflateOptions { this.dictionary = dictionary }
        val options = InflateOptions { this.dictionary = dictionary }
        assertContentEquals(text, Inflate.inflate(Deflate.encode(text, primed), options).output)
        val stream = Zlib.compress(text, primed)
        assertContentEquals(text, Zlib.decompress(stream, options).output)
        // A missing dictionary is the caller's to supply, not damage to recover from.
        assertFailsWith<ArchiveUnsupportedException> { Zlib.decompress(stream, InflateOptions { keepPartialOutput = true }) }
        val wrong = assertFailsWith<ArchiveFormatException> { Zlib.decompress(stream, InflateOptions { this.dictionary = byteArrayOf(1) }) }
        assertEquals(2L, wrong.offset)
    }

    @Test
    fun theListenerRunsBeforeEachBlock() {
        // Level 0 stores 100,000 bytes as two blocks of 65,535 and 34,465 bytes, each with a 5-byte header.
        val data = Random(8).nextBytes(100_000)
        val calls = mutableListOf<Pair<Int, Long>>()
        val options = InflateOptions { onBlock = InflateBlockListener { consumed, produced -> calls += consumed to produced } }
        assertContentEquals(data, Inflate.inflate(Deflate.encode(data, DeflateOptions { level = 0 }), options).output)
        assertEquals(listOf(0 to 0L, 65_540 to 65_535L), calls)
        calls.clear()
        // The zlib header counts as consumed input, wherever the range starts.
        val input = before + Zlib.compress(data, DeflateOptions { level = 0 })
        assertContentEquals(data, Zlib.decompress(input, before.size, input.size - before.size, options).output)
        assertEquals(listOf(2 to 0L, 65_542 to 65_535L), calls)
    }

    @Test
    fun whatTheListenerThrowsPassesThroughUnchanged() {
        val failures = listOf(IllegalStateException("stop"), ArchiveFormatException("stop"), InflateException(InflateError.UNKNOWN))
        for (failure in failures) {
            for (keep in listOf(false, true)) {
                val options = InflateOptions {
                    keepPartialOutput = keep
                    onBlock = InflateBlockListener { _, _ -> throw failure }
                }
                assertSame(failure, assertFailsWith<Exception> { Inflate.inflate(raw, options) })
                assertSame(failure, assertFailsWith<Exception> { Zlib.decompress(zlib, options) })
            }
        }
    }

    @Test
    fun theZlibChecksumCanBeLeftUnread() {
        val unchecked = InflateOptions { verifyChecksum = false }
        val wrong = zlib.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<ArchiveFormatException> { Zlib.decompress(wrong, InflateOptions.DEFAULT) }
        // A wrong, cut-short or missing trailer is accepted, and counts as part of the stream.
        for (kept in 4 downTo 0) {
            val stream = wrong.copyOf(wrong.size - 4 + kept)
            val result = Zlib.decompress(stream, unchecked)
            assertContentEquals(text, result.output, "trailer of $kept bytes")
            assertEquals(stream.size, result.inputConsumed)
            if (kept < 4) assertFailsWith<ArchiveFormatException> { Zlib.decompress(stream, InflateOptions.DEFAULT) }
        }
        // More than four bytes after the last block are trailing data.
        assertFailsWith<ArchiveFormatException> { Zlib.decompress(wrong + after, unchecked) }
        val result = Zlib.decompress(wrong + after, unchecked.copy { allowTrailingData = true })
        assertContentEquals(text, result.output)
        assertEquals(wrong.size, result.inputConsumed)
    }

    @Test
    fun zlibDamageKeepsTheOutputAndNamesTheFailure() {
        val keep = InflateOptions { keepPartialOutput = true }
        val wrong = zlib.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val mismatch = Zlib.decompress(wrong, keep)
        assertContentEquals(text, mismatch.output)
        assertEquals((zlib.size - 4).toLong(), assertIs<ArchiveFormatException>(mismatch.error).offset)
        // The header is always checked: without one, nothing is decoded.
        val headerless = Zlib.decompress(raw, keep)
        assertEquals(0, headerless.output.size)
        assertEquals(0, headerless.inputConsumed)
        assertIs<ArchiveFormatException>(headerless.error)
        assertContentEquals(text, Inflate.inflate(raw, keep).output)
    }
}
