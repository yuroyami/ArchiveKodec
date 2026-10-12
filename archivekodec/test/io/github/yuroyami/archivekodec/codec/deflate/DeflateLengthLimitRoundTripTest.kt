package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DeflateLengthLimitRoundTripTest {
    @Test
    fun matchesRecordedJavaRandomFixture() {
        assertEquals(1_000_000, deflateLengthLimitInput.size)
        assertEquals(0x93D95898L, Crc32.of(deflateLengthLimitInput))
    }

    @Test
    fun rawDeflateRoundTripsTheOversubscribedTreeRegression() {
        val compressed = Deflate.encode(deflateLengthLimitInput)
        // The encoder now cuts blocks of 16,384 symbols; the first is dynamic and not final.
        assertEquals(4, compressed[0].toInt() and 7, "expected a non-final dynamic first block")
        assertContentEquals(deflateLengthLimitInput, Inflate.inflate(compressed))
        assertContentEquals(compressed, Deflate.encode(deflateLengthLimitInput), "deterministic encoding")
    }

    @Test
    fun gzipRoundTripsTheOversubscribedTreeRegression() {
        assertContentEquals(deflateLengthLimitInput, Gzip.decompress(Gzip.compress(deflateLengthLimitInput)))
    }

    @Test
    fun zlibRoundTripsTheOversubscribedTreeRegression() {
        assertContentEquals(deflateLengthLimitInput, Zlib.decompress(Zlib.compress(deflateLengthLimitInput)))
    }

    @Test
    fun zipRoundTripsTheOversubscribedTreeRegression() {
        val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec("regression.bin", deflateLengthLimitInput, ZipMethod.DEFLATED)))
        ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
            assertContentEquals(deflateLengthLimitInput, reader.read(reader.entries().single()))
        }
    }
}
