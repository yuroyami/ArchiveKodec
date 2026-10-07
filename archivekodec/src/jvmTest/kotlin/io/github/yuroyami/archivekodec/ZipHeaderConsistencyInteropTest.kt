package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ZipHeaderConsistencyInteropTest {
    @Test
    fun jdkStoredAndDescriptorDeflateEntriesKeepTheirMetadataAndPayloads() {
        val payload = ByteArray(1024) { it.toByte() }
        for (method in listOf(ZipEntry.STORED, ZipEntry.DEFLATED)) {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                val entry = ZipEntry("é/文件😀.bin")
                entry.method = method
                entry.extra = byteArrayOf(0x34, 0x12, 3, 0, 1, 2, 3)
                entry.comment = "directory-only comment"
                if (method == ZipEntry.STORED) {
                    entry.size = payload.size.toLong()
                    entry.compressedSize = payload.size.toLong()
                    entry.crc = CRC32().also { it.update(payload) }.value
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
                zip.setComment("archive comment")
            }
            ZipReader(ByteArrayRandomAccessSource(output.toByteArray())).use { reader ->
                val entry = reader.entries().single()
                assertEquals("é/文件😀.bin", entry.name)
                assertEquals(payload.size.toLong(), entry.size)
                assertFalse(entry.isEncrypted)
                assertContentEquals(payload, reader.read(entry.copy()))
            }
        }
    }
}
