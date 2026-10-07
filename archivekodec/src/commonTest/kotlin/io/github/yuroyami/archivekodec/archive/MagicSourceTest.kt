package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveClosedException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MagicSourceTest {
    private val sniffers: List<(RandomAccessSource) -> ArchiveFormat> =
        listOf({ Magic.sniff(it) }, { ArchiveKodec.sniff(it) })
    private val chunkPatterns = listOf(intArrayOf(1), intArrayOf(3, 1, 17, 2, 127, 5))
    private val payload = "short reads must preserve the complete payload.\n".repeat(23).encodeToByteArray()

    private data class ReadCall(val offset: Long, val intoOffset: Int, val length: Int, val capacity: Int)

    /**
     * Successful reads obey the source contract and copy exactly their reported
     * bytes. The optional fault is injected at an absolute source offset so the
     * same double can exercise an initial failure or one after real progress.
     */
    private class ChunkedSource(
        private val bytes: ByteArray,
        private val chunks: IntArray = intArrayOf(1),
        override val size: Long = bytes.size.toLong(),
        private val faultAt: Long? = null,
        private val faultCount: Int? = null,
        private val faultError: ArchiveIOException? = null,
    ) : RandomAccessSource {
        val calls = mutableListOf<ReadCall>()
        var supplied = 0L
            private set
        var lastReadEnd = 0L
            private set
        var closes = 0
        var closeError: ArchiveIOException? = null

        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            assertTrue(offset >= 0)
            assertTrue(intoOffset >= 0 && length >= 0 && intoOffset <= into.size && length <= into.size - intoOffset)
            calls += ReadCall(offset, intoOffset, length, into.size)
            if (faultAt != null && offset >= faultAt) {
                faultError?.let { throw it }
                return checkNotNull(faultCount)
            }
            if (length == 0) return 0
            if (offset >= bytes.size || offset >= size) return -1
            val available = minOf(bytes.size.toLong() - offset, size - offset)
            val beforeFault = faultAt?.let { it - offset } ?: Long.MAX_VALUE
            val count = minOf(minOf(length.toLong(), available), minOf(beforeFault, chunks[(calls.size - 1) % chunks.size].toLong())).toInt()
            assertTrue(count > 0)
            val start = offset.toInt()
            bytes.copyInto(into, intoOffset, start, start + count)
            supplied += count
            lastReadEnd = offset + count
            return count
        }

        override fun close() {
            closes++
            closeError?.let { throw it }
        }
    }

    private fun zip(): ByteArray = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", payload, CodecId.STORE)))

    private fun tarHeader(): ByteArray = ByteArray(512).also {
        "a.txt".encodeToByteArray().copyInto(it)
        payload.size.toString(8).padStart(11, '0').encodeToByteArray().copyInto(it, 124)
        it[156] = '0'.code.toByte()
        "ustar\u000000".encodeToByteArray().copyInto(it, 257)
        it.fill(32, 148, 156)
        (it.sumOf { b -> b.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ")
            .encodeToByteArray().copyInto(it, 148)
    }

    private fun tar(): ByteArray = tarHeader() + payload + ByteArray((512 - payload.size % 512) % 512 + 1024)

    private fun fixtures() = listOf(ArchiveFormat.ZIP to zip(), ArchiveFormat.TAR to tar())

    @Test
    fun oneByteAndIrregularReadsMatchCompleteHeaders() {
        for ((format, bytes) in fixtures()) {
            for (pattern in chunkPatterns) {
                for (sniff in sniffers) {
                    val source = ChunkedSource(bytes, pattern)
                    assertEquals(format, sniff(source))
                    assertEquals(0, source.closes)
                    assertTrue(source.calls.size > 1)
                    assertEquals(512L, source.supplied)
                    assertEquals(512L, source.lastReadEnd)
                    assertEquals(0L, source.calls.first().offset)
                    val last = source.calls.last()
                    assertTrue(last.offset < 512)
                    assertTrue(source.calls.all { it.offset + it.length <= 512L && it.capacity <= 512 })
                }
            }
        }
    }

    @Test
    fun oneByteAndIrregularSourcesOpenListAndReadBothContainers() {
        for ((format, bytes) in fixtures()) {
            for (pattern in chunkPatterns) {
                val source = ChunkedSource(bytes, pattern)
                val reader = ArchiveKodec.open(source)
                try {
                    assertEquals(format, reader.format)
                    assertEquals(0, source.closes)
                    val entry = reader.entries().single()
                    assertEquals("a.txt", entry.name)
                    assertEquals(payload.size.toLong(), entry.size)
                    assertContentEquals(payload, reader.read(entry))
                } finally {
                    reader.close()
                }
                reader.close()
                assertEquals(1, source.closes)
                assertFailsWith<ArchiveClosedException> { reader.entries() }
            }
        }
    }

    @Test
    fun truthfulSmallExtentsNeverReadPastTheirEnd() {
        val zipPrefix = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        for (extent in listOf(0, 1, 3, 4, 511, 512, 513)) {
            val bytes = ByteArray(extent)
            zipPrefix.copyInto(bytes, endIndex = minOf(extent, zipPrefix.size))
            for (sniff in sniffers) {
                val source = ChunkedSource(bytes, intArrayOf(1, 3, 7))
                assertEquals(if (extent >= 4) ArchiveFormat.ZIP else ArchiveFormat.UNKNOWN, sniff(source), "extent $extent")
                val consumed = minOf(extent, 512)
                if (consumed == 0) assertTrue(source.calls.isEmpty())
                else {
                    assertEquals(consumed, source.calls.first().length)
                    assertTrue(source.calls.all { it.offset >= 0 && it.offset + it.length <= consumed.toLong() })
                    val last = source.calls.last()
                    assertEquals(consumed.toLong() - last.offset, last.length.toLong())
                }
                assertEquals(0, source.closes)
            }
        }
    }

    @Test
    fun completeTarRecordIsAssembledOnlyAtThe512ByteBoundary() {
        val header = tarHeader()
        for (extent in listOf(511, 512, 513)) {
            val bytes = header.copyOf(extent)
            for (sniff in sniffers) {
                val source = ChunkedSource(bytes, intArrayOf(3, 1, 17))
                assertEquals(if (extent >= 512) ArchiveFormat.TAR else ArchiveFormat.UNKNOWN, sniff(source), "extent $extent")
                assertTrue(source.calls.all { it.offset + it.length <= minOf(extent, 512).toLong() })
                assertEquals(0, source.closes)
            }
        }
    }

    @Test
    fun hugeSizesStillReadOnlyTheBoundedPrefix() {
        val prefix = ByteArray(512).also { byteArrayOf(0x50, 0x4b, 0x03, 0x04).copyInto(it) }
        for (extent in listOf(Int.MAX_VALUE.toLong() + 1, 1L shl 32, Long.MAX_VALUE)) {
            for (sniff in sniffers) {
                val source = ChunkedSource(prefix, intArrayOf(3, 1, 127), size = extent)
                assertEquals(ArchiveFormat.ZIP, sniff(source), "extent $extent")
                assertEquals(512, source.calls.first().length)
                assertTrue(source.calls.all { it.offset + it.length <= 512L && it.capacity == 512 })
                assertEquals(0, source.closes)
            }
        }
    }

    @Test
    fun negativeSizeIsRejectedBeforeAnyRead() {
        for (size in listOf(-1L, Long.MIN_VALUE)) {
            for (sniff in sniffers) {
                val source = ChunkedSource(byteArrayOf(), size = size)
                val error = assertFailsWith<ArchiveFormatException> { sniff(source) }
                assertEquals(0L, error.offset)
                assertNull(error.entryName)
                assertTrue(source.calls.isEmpty())
                assertEquals(0, source.closes)
                source.close()
                assertEquals(1, source.closes)
            }
        }
    }

    @Test
    fun everyInvalidReadCountFailsAtTheFirstUnfilledOffset() {
        val bytes = ByteArray(512).also { byteArrayOf(0x50, 0x4b, 0x03, 0x04).copyInto(it) }
        for (progress in listOf(0, 3)) {
            val remaining = 512 - progress
            for (count in listOf(0, -1, -2, Int.MIN_VALUE, remaining + 1, Int.MAX_VALUE)) {
                for (sniff in sniffers) {
                    val source = ChunkedSource(bytes, intArrayOf(3), faultAt = progress.toLong(), faultCount = count)
                    val error = assertFailsWith<ArchiveFormatException> { sniff(source) }
                    assertEquals(progress.toLong(), error.offset, "progress $progress, count $count")
                    assertNull(error.entryName)
                    assertEquals(if (progress == 0) 1 else 2, source.calls.size)
                    assertEquals(progress.toLong(), source.calls.last().offset)
                    assertEquals(remaining, source.calls.last().length)
                    assertEquals(0, source.closes)
                }
            }
        }
    }

    @Test
    fun prematureEofDoesNotClassifyTheReadablePrefix() {
        // A complete ZIP signature cannot excuse failure to obtain the rest of
        // the bounded prefix promised by source.size.
        for (available in listOf(0, 1, 3, 4, 511)) {
            for (sniff in sniffers) {
                val bytes = ByteArray(available)
                byteArrayOf(0x50, 0x4b, 0x03, 0x04).copyInto(bytes, endIndex = minOf(available, 4))
                val source = ChunkedSource(bytes, intArrayOf(3, 1, 17), size = 512)
                val error = assertFailsWith<ArchiveFormatException> { sniff(source) }
                assertEquals(available.toLong(), error.offset)
                assertEquals(available.toLong(), source.calls.last().offset)
                assertEquals(0, source.closes)
            }
        }
    }

    @Test
    fun readErrorsKeepTheirIdentityCauseAndOriginalContext() {
        val bytes = ByteArray(512)
        for (progress in listOf(0, 3)) {
            for (sniff in sniffers) {
                val cause = IllegalStateException("source transport failed")
                val failure = ArchiveIOException("read failed", cause, 77, "source.bin")
                val source = ChunkedSource(bytes, intArrayOf(3), faultAt = progress.toLong(), faultError = failure)
                val caught = assertFailsWith<ArchiveIOException> { sniff(source) }
                assertSame(failure, caught)
                assertSame(cause, caught.cause)
                assertEquals(77L, caught.offset)
                assertEquals("source.bin", caught.entryName)
                assertEquals(progress.toLong(), source.calls.last().offset)
                assertEquals(0, source.closes)
            }
        }
    }

    @Test
    fun sourceSizeFailureIsPropagatedWithoutReadingOrClosing() {
        for (sniff in sniffers) {
            val failure = ArchiveIOException("size failed", IllegalStateException("source transport failed"), 91)
            var reads = 0
            var closes = 0
            val source = object : RandomAccessSource {
                override val size: Long get() = throw failure
                override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int { reads++; error("must not read") }
                override fun close() { closes++ }
            }
            assertSame(failure, assertFailsWith<ArchiveIOException> { sniff(source) })
            assertEquals(0, reads)
            assertEquals(0, closes)
            source.close()
            assertEquals(1, closes)
        }
    }

    @Test
    fun failedOpenLeavesTheSourceWithItsCaller() {
        val cause = IllegalStateException("source transport failed")
        val failure = ArchiveIOException("read failed", cause, 81)
        val cases = listOf(
            ChunkedSource("ordinary text".encodeToByteArray()),
            ChunkedSource(byteArrayOf(0x1f, 0x8b.toByte(), 0x08)),
            ChunkedSource(ByteArray(512), intArrayOf(3), faultAt = 3, faultCount = -1),
            ChunkedSource(ByteArray(512), intArrayOf(3), faultAt = 3, faultError = failure),
            ChunkedSource(byteArrayOf(), size = -1),
        )
        for ((index, source) in cases.withIndex()) {
            when (index) {
                1 -> assertFailsWith<ArchiveUnsupportedException> { ArchiveKodec.open(source) }
                3 -> assertSame(failure, assertFailsWith<ArchiveIOException> { ArchiveKodec.open(source) })
                else -> assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(source) }
            }
            assertEquals(0, source.closes)
            source.close()
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun closeFailureAfterShortReadOpenStillReleasesReaderOwnershipOnce() {
        for ((_, bytes) in fixtures()) {
            val source = ChunkedSource(bytes, intArrayOf(1))
            val failure = ArchiveIOException("close failed", IllegalStateException("source close failed"), 92)
            source.closeError = failure
            val reader = ArchiveKodec.open(source)
            assertContentEquals(payload, reader.read(reader.entries().single()))
            assertEquals(0, source.closes)
            assertSame(failure, assertFailsWith<ArchiveIOException> { reader.close() })
            reader.close()
            assertEquals(1, source.closes)
            assertFailsWith<ArchiveClosedException> { reader.entries() }
        }
    }

    @Test
    fun exact22ByteEmptyZipIsDetectedAndOpenedThroughShortReads() {
        val bytes = ZipWriter.write(emptyList())
        assertEquals(22, bytes.size)
        assertContentEquals(byteArrayOf(0x50, 0x4b, 0x05, 0x06), bytes.copyOfRange(0, 4))
        for (pattern in chunkPatterns) {
            for (sniff in sniffers) {
                val source = ChunkedSource(bytes, pattern)
                assertEquals(ArchiveFormat.ZIP, sniff(source))
                assertEquals(0, source.closes)
                val reader = ArchiveKodec.open(source)
                try {
                    assertEquals(ArchiveFormat.ZIP, reader.format)
                    assertTrue(reader.entries().isEmpty())
                } finally {
                    reader.close()
                }
                reader.close()
                assertEquals(1, source.closes)
            }
        }
    }

    @Test
    fun standaloneCodecPrefixesAreDetectedThroughShortReads() {
        // The bzip2 empty/end marker is independently frozen from libbzip2 1.0.8.
        val prefixes = listOf(
            ArchiveFormat.GZIP to byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00),
            ArchiveFormat.BZIP2 to byteArrayOf(0x42, 0x5a, 0x68, 0x39, 0x17, 0x72, 0x45, 0x38, 0x50, 0x90.toByte()),
        )
        for ((format, bytes) in prefixes) {
            for (pattern in chunkPatterns) {
                for (sniff in sniffers) {
                    val source = ChunkedSource(bytes, pattern)
                    assertEquals(format, sniff(source))
                    assertTrue(source.calls.size > 1)
                    assertTrue(source.calls.all { it.offset + it.length <= bytes.size.toLong() })
                    assertEquals(0, source.closes)
                    source.close()
                    assertEquals(1, source.closes)
                }
            }
        }
    }

    @Test
    fun completeZeroTarMarkerIsAssembledBeforeOpeningAnEmptyArchive() {
        for (pattern in chunkPatterns) {
            for (sniff in sniffers) {
                val source = ChunkedSource(ByteArray(512), pattern)
                assertEquals(ArchiveFormat.TAR, sniff(source))
                assertEquals(512L, source.supplied)
                assertEquals(512L, source.lastReadEnd)
                assertEquals(0, source.closes)
                val reader = ArchiveKodec.open(source)
                try {
                    assertEquals(ArchiveFormat.TAR, reader.format)
                    assertTrue(reader.entries().isEmpty())
                } finally {
                    reader.close()
                }
                reader.close()
                assertEquals(1, source.closes)
            }
        }
    }

    @Test
    fun zeroFilledDestinationCannotConcealATruncatedTarMarker() {
        for (pattern in chunkPatterns) {
            for (sniff in sniffers) {
                val source = ChunkedSource(ByteArray(511), pattern, size = 512)
                val error = assertFailsWith<ArchiveFormatException> { sniff(source) }
                assertEquals(511L, error.offset)
                assertEquals(511L, source.supplied)
                assertEquals(511L, source.lastReadEnd)
                assertEquals(511L, source.calls.last().offset)
                assertEquals(0, source.closes)
            }
            val source = ChunkedSource(ByteArray(511), pattern, size = 512)
            val error = assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(source) }
            assertEquals(511L, error.offset)
            assertEquals(0, source.closes)
            source.close()
            assertEquals(1, source.closes)
        }
    }
}
