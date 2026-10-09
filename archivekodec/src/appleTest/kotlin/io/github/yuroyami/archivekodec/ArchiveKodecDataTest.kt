package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import platform.Foundation.NSData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveKodecDataTest {
    private val hello = "hello".encodeToByteArray()

    @Test
    fun bytesRoundTripThroughNSData() {
        val data = ArchiveKodec.data(hello)
        assertEquals(5L, data.length.toLong())
        assertContentEquals(hello, ArchiveKodec.bytes(data))
        assertEquals(0L, ArchiveKodec.data(ByteArray(0)).length.toLong())
        assertContentEquals(ByteArray(0), ArchiveKodec.bytes(NSData()))
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, ArchiveKodec.bytes(ArchiveKodec.data(all)))
    }

    @Test
    fun codecsTakeAndReturnNSData() {
        val packed = ArchiveKodec.compress(ArchiveKodec.gzip, ArchiveKodec.data(hello))
        assertContentEquals(hello, ArchiveKodec.bytes(ArchiveKodec.decompress(ArchiveKodec.gzip, packed)))
        assertContentEquals(hello, ArchiveKodec.bytes(ArchiveKodec.decompress(packed)))
        val zeros = ArchiveKodec.compress(ArchiveKodec.zlib, ArchiveKodec.data(ByteArray(5000)))
        val small = ArchiveLimits { maxUncompressedBytes = 100 }
        assertFailsWith<DecompressionBombException> { ArchiveKodec.decompress(ArchiveKodec.zlib, zeros, small) }
        assertFailsWith<DecompressionBombException> { ArchiveKodec.decompress(zeros, small) }
    }

    @Test
    fun archivesOpenFromNSData() {
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", hello), ZipWriter.FileSpec("dir/", ByteArray(0))))
        val reader = ArchiveKodec.open(ArchiveKodec.data(zip))
        val entries = reader.entries()
        assertEquals(listOf("a.txt", "dir/"), entries.map { it.name })
        assertContentEquals(hello, ArchiveKodec.bytes(ArchiveKodec.read(reader, entries[0])))
        reader.close()
        val explicit = ArchiveKodec.open(ArchiveKodec.data(zip), "zip", ArchiveOpenOptions.DEFAULT)
        assertTrue(explicit.entries().isNotEmpty())
        explicit.close()
        assertFailsWith<ArchiveException> { ArchiveKodec.open(ArchiveKodec.data(ByteArray(64)), ArchiveLimits.DEFAULT) }
    }
}
