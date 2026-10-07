@file:Suppress("DEPRECATION")

package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WrapperLimitsTest {
    private val codecs: List<Codec> = listOf(Codecs.DEFLATE, Codecs.GZIP, Codecs.ZLIB)

    @Test
    fun acceptsExactlyTheBudgetAndRejectsOneByteLess() {
        for (data in listOf("literal".encodeToByteArray(), ByteArray(16_384))) {
            for (codec in codecs) {
                val input = codec.compress(data)
                assertContentEquals(data, codec.decompress(input, ArchiveLimits { maxUncompressedBytes = data.size.toLong() }))
                assertFailsWith<DecompressionBombException> {
                    codec.decompress(input, ArchiveLimits { maxUncompressedBytes = data.size.toLong() - 1 })
                }
            }
        }
    }

    @Test
    fun storedBlocksAreBoundedBeforeCopying() {
        val input = byteArrayOf(1, 3, 0, -4, -1, 65, 66, 67)
        assertContentEquals("ABC".encodeToByteArray(), Inflate.inflate(input, ArchiveLimits { maxUncompressedBytes = 3 }))
        assertFailsWith<DecompressionBombException> { Inflate.inflate(input, ArchiveLimits { maxUncompressedBytes = 2 }) }
    }

    @Test
    fun zeroBudgetsAcceptOnlyEmptyOutput() {
        val limits = ArchiveLimits {
            maxUncompressedBytes = 0
            maxCompressionRatio = 0
        }
        for (codec in codecs) {
            assertContentEquals(byteArrayOf(), codec.decompress(codec.compress(byteArrayOf()), limits))
            assertFailsWith<DecompressionBombException> { codec.decompress(codec.compress(byteArrayOf(1)), limits) }
        }
        assertContentEquals(byteArrayOf(), Gzip.decompress(Gzip.compress(byteArrayOf()), maximumSize = 0L))
        assertContentEquals(byteArrayOf(), Zlib.decompress(Zlib.compress(byteArrayOf()), maximumSize = 0L))
    }

    @Test
    fun compatibilityOverloadsRejectNegativeConfiguration() {
        for (invalid in listOf(-1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { Gzip.decompress(byteArrayOf(), maximumSize = invalid) }
            assertFailsWith<IllegalArgumentException> { Zlib.decompress(byteArrayOf(), maximumSize = invalid) }
        }
    }

    @Test
    fun directAndCodecCallsHaveTheSameDefaultAboveFourMebibytes() {
        val data = ByteArray(5 * 1024 * 1024)
        val gz = Gzip.compress(data)
        assertEquals(data.size, Gzip.decompress(gz).size)
        assertEquals(data.size, Codecs.GZIP.decompress(gz).size)
        val zl = Zlib.compress(data)
        assertEquals(data.size, Zlib.decompress(zl).size)
        assertEquals(data.size, Codecs.ZLIB.decompress(zl).size)
        assertFailsWith<DecompressionBombException> { Gzip.decompress(gz, maximumSize = Gzip.DEFAULT_MAXIMUM_SIZE) }
        assertFailsWith<DecompressionBombException> { Zlib.decompress(zl, maximumSize = 4L * 1024 * 1024) }
    }

    @Test
    fun gzipBudgetIsSharedAcrossMembersAndFallbackUsesTheSamePolicy() {
        val data = "hello".encodeToByteArray()
        val concatenated = Gzip.compress(data) + Gzip.compress(data)
        assertContentEquals(data + data, Gzip.decompress(concatenated, ArchiveLimits { maxUncompressedBytes = 10 }))
        assertFailsWith<DecompressionBombException> {
            Gzip.decompress(concatenated, ArchiveLimits { maxUncompressedBytes = 9 })
        }
        assertFailsWith<DecompressionBombException> {
            Gzip.decompress(Zlib.compress(data), ArchiveLimits { maxUncompressedBytes = 4 })
        }
    }

    @Test
    fun completeRatioPolicyReachesEveryDeflateEntryPoint() {
        val data = ByteArray(16_384)
        val limits = ArchiveLimits { maxCompressionRatio = 1 }
        for (codec in codecs) assertFailsWith<DecompressionBombException> { codec.decompress(codec.compress(data), limits) }
        assertFailsWith<DecompressionBombException> { Gzip.decompress(Gzip.compress(data), limits) }
        assertFailsWith<DecompressionBombException> { Zlib.decompress(Zlib.compress(data), limits) }
    }
}
