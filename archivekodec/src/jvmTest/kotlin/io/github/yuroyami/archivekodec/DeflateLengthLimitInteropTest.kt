package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.deflateLengthLimitInput
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.Zlib
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeflateLengthLimitInteropTest {
    @Test
    fun fixtureMatchesTheReportedJavaSequenceExactly() {
        assertEquals("4b5ddb8d805ad427f75a221a599484068d80690d52b12281d2650c4e491179a6",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(deflateLengthLimitInput)))
    }

    private fun decode(bytes: ByteArray, raw: Boolean): ByteArray {
        val inflater = Inflater(raw)
        return try {
            val decoded = InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { it.readBytes() }
            assertTrue(inflater.finished())
            assertEquals(0, inflater.remaining)
            decoded
        } finally { inflater.end() }
    }

    @Test
    fun jdkAcceptsRawDeflate() {
        assertContentEquals(deflateLengthLimitInput, decode(Deflate.encode(deflateLengthLimitInput), true))
    }

    @Test
    fun jdkAcceptsGzip() {
        val compressed = Gzip.compress(deflateLengthLimitInput)
        GZIPInputStream(ByteArrayInputStream(compressed)).use { assertContentEquals(deflateLengthLimitInput, it.readBytes()) }
    }

    @Test
    fun jdkAcceptsZlib() {
        assertContentEquals(deflateLengthLimitInput, decode(Zlib.compress(deflateLengthLimitInput), false))
    }

    @Test
    fun jdkAcceptsDeflatedZip() {
        val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec("regression.bin", deflateLengthLimitInput, CodecId.DEFLATE)))
        ZipInputStream(ByteArrayInputStream(bytes)).use {
            assertEquals("regression.bin", it.nextEntry.name)
            assertContentEquals(deflateLengthLimitInput, it.readBytes())
            assertNull(it.nextEntry)
        }
    }
}
