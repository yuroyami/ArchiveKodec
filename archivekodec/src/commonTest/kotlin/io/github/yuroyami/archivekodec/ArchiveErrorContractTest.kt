package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.deflate.InflateError
import io.github.yuroyami.archivekodec.codec.deflate.InflateException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Zlib
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArchiveErrorContractTest {
    @Test
    fun invalidArchiveMagicUsesTheSharedFormatContract() {
        for (operation in listOf<() -> Unit>(
            { ArReader(ByteArrayRandomAccessSource(byteArrayOf())).entries() },
            { ZipReader(ByteArrayRandomAccessSource(byteArrayOf())).entries() },
            { ArchiveKodec.open(ByteArrayRandomAccessSource(byteArrayOf())) },
        )) {
            val error = assertFailsWith<ArchiveFormatException> { operation() }
            assertEquals(0L, error.offset)
        }
    }

    @Test
    fun unsupportedFormatsAndWriterCodecsHaveASeparateTypedCategory() {
        assertFailsWith<ArchiveUnsupportedException> {
            ArchiveKodec.open(ByteArrayRandomAccessSource("BZh9".encodeToByteArray()))
        }
        val error = assertFailsWith<ArchiveUnsupportedException> {
            ZipWriter.write(listOf(ZipWriter.FileSpec("a", byteArrayOf(), CodecId.LZ4)))
        }
        assertEquals("a", error.entryName)
    }

    @Test
    fun invalidGnuNameOffsetsCannotEscapeAsNumberOrSliceErrors() {
        for (name in listOf("/12abc", "/2147483648", "/10")) {
            val error = assertFailsWith<ArchiveFormatException> {
                ArReader(ByteArrayRandomAccessSource(ar(name))).entries()
            }
            assertEquals(8L, error.offset)
            assertEquals(name, error.entryName)
        }
    }

    @Test
    fun corruptDeflatePreservesItsCauseAndRebasesTheOffsetToTheArchive() {
        val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec("bad", byteArrayOf(65), CodecId.DEFLATE)))
        val payload = 33
        bytes[payload] = 7 // final block with reserved BTYPE=3
        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        val error = assertFailsWith<ArchiveFormatException> { reader.read(reader.entries().single()) }
        assertEquals("bad", error.entryName)
        assertEquals((payload + 1).toLong(), error.offset)
        assertEquals(InflateError.INVALID_BLOCK_TYPE, assertIs<InflateException>(error.cause).error)
        assertTrue(error.toString().contains("bad"))
    }

    @Test
    fun unsupportedZipMethodsAndBudgetFailuresRetainEntryContext() {
        val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec("a", byteArrayOf(65), CodecId.STORE)))
        bytes[8] = 99
        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(reader.entries().single()) }
        assertEquals("a", error.entryName)
        assertTrue(error.offset != null)
        val valid = ZipReader(ByteArrayRandomAccessSource(ZipWriter.write(listOf(ZipWriter.FileSpec("b", byteArrayOf(65), CodecId.STORE)))))
        val bomb = assertFailsWith<DecompressionBombException> {
            valid.read(valid.entries().single(), ArchiveLimits(maxUncompressedBytes = 0))
        }
        assertEquals("b", bomb.entryName)
        assertIs<DecompressionBombException>(bomb.cause)
    }

    @Test
    fun requiredZlibDictionariesUseTheUnsupportedCategory() {
        // CPython zlib.compressobj(zdict=b"hello dictionary") over b"hello".
        val input = "78bb3529066bcb00f101062c0215".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val error = assertFailsWith<ArchiveUnsupportedException> { Zlib.decompress(input) }
        assertEquals(2L, error.offset)
        assertTrue(error.message!!.contains("3529066b"))
        for (size in 2..5) assertFailsWith<ArchiveFormatException> { Zlib.decompress(input.copyOf(size)) }
    }

    @Test
    fun readerDescriptionsDoNotTriggerParsing() {
        val reader = ZipReader(ByteArrayRandomAccessSource(byteArrayOf()))
        assertEquals("ZipReader(source=ByteArrayRandomAccessSource(size=0))", reader.toString())
    }

    private fun ar(name: String): ByteArray {
        val header = ByteArray(60) { 32 }
        name.encodeToByteArray().copyInto(header)
        header[48] = 48; header[58] = 0x60; header[59] = 10
        return "!<arch>\n".encodeToByteArray() + header
    }
}
