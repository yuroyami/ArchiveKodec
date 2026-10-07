package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchiveLimitsTest {
    @Test
    fun ratioAcceptsEqualityAndRejectsTheNextByte() {
        for (ratio in listOf(1, 2, 10_000)) {
            val limits = ArchiveLimits(maxCompressionRatio = ratio)
            limits.checkRatio(2, 2L * ratio)
            assertFailsWith<DecompressionBombException> { limits.checkRatio(2, 2L * ratio + 1) }
        }
        ArchiveLimits(maxCompressionRatio = 0).checkRatio(1, 0)
        assertFailsWith<DecompressionBombException> { ArchiveLimits(maxCompressionRatio = 0).checkRatio(1, 1) }
    }

    @Test
    fun largeRatiosDoNotOverflowOrLoseTheRemainder() {
        ArchiveLimits(maxCompressionRatio = 1).checkRatio(Long.MAX_VALUE, Long.MAX_VALUE)
        val half = Long.MAX_VALUE / 2
        val limits = ArchiveLimits(maxCompressionRatio = 2)
        limits.checkRatio(half, Long.MAX_VALUE - 1)
        assertFailsWith<DecompressionBombException> { limits.checkRatio(half, Long.MAX_VALUE) }
        assertFailsWith<DecompressionBombException> {
            ArchiveLimits.UNBOUNDED.checkRatio(4_294_967_296L, Long.MAX_VALUE)
        }
    }

    @Test
    fun truthfulZipMetadataEnforcesFractionalExcess() {
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", ByteArray(5) { 65 }, CodecId.DEFLATE)))
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        val entry = reader.entries().single()
        assertEquals(5L, entry.size)
        assertEquals(4L, entry.compressedSize)
        assertFailsWith<DecompressionBombException> { reader.read(entry, ArchiveLimits(maxCompressionRatio = 1)) }
        assertEquals(5, reader.read(entry, ArchiveLimits(maxCompressionRatio = 2)).size)
    }
}
