package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InflateCursorTest {
    // CPython zlib: level 0, Z_FIXED, and the default dynamic coding strategy.
    private val streams = listOf(
        hex("010600f9ff637572736f72"),
        hex("4b2e2d2ace2f524846a600"),
        hex("edca491282401000c1aff4d7045944a501677079bd867fe096c78aca3276b1d64b7b8d66cbe71c7dbe62aaf7e511b9775b94dfbe9d3eef38e7f00f966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559966559963dd27e01"),
    )
    private val outputs = listOf(
        "cursor".encodeToByteArray(), "cursor cursor cursor".encodeToByteArray(),
        "the quick brown fox jumps over the lazy dog ".repeat(1000).encodeToByteArray(),
    )

    @Test
    fun cursorStopsAfterStoredFixedAndDynamicStreams() {
        for (i in streams.indices) {
            assertEquals(i, (streams[i][0].toInt() ushr 1) and 3, "Independent fixture block type")
            val input = byteArrayOf(99, 98) + streams[i] + byteArrayOf(97, 96)
            val output = Inflate.Output(ArchiveLimits.DEFAULT, streams[i].size)
            val next = Inflate.inflateInto(input, 2, input.size, output)
            assertEquals(2 + streams[i].size, next)
            assertContentEquals(outputs[i], output.toByteArray())
            assertContentEquals(outputs[i], Inflate.inflate(streams[i]))
            assertFailsWith<ArchiveFormatException> { Inflate.inflate(streams[i] + byteArrayOf(0)) }
        }
    }

    @Test
    fun boundedCursorCannotReadBytesBeyondItsSlice() {
        for (stream in streams) {
            val output = Inflate.Output(ArchiveLimits.DEFAULT, stream.size)
            assertFailsWith<InflateException> {
                Inflate.inflateInto(stream, 0, stream.size - 1, output)
            }
        }
    }

    @Test
    fun gzipMembersDoNotShareBackReferenceHistory() {
        // A fixed block beginning with length 3, distance 1 has no dictionary.
        val invalidRaw = hex("030200")
        val first = Gzip.compress("preceding history".encodeToByteArray())
        val second = Gzip.compress(byteArrayOf()).copyOf(10) + invalidRaw + ByteArray(8)
        val error = assertFailsWith<InflateException> { Gzip.decompress(first + second) }
        assertEquals(InflateError.DISTANCE_TOO_FAR_BACK, error.error)
    }

}
