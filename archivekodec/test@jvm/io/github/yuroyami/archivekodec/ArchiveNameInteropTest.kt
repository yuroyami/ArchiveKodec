package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ArchiveNameInteropTest {
    @Test
    fun javaLegacyCharsetsDecodeThroughThePublicFacade() {
        for ((encoding, name) in listOf("IBM437" to "café-π.txt", "ISO-8859-1" to "café.txt", "GBK" to "目录/雪.txt", "Shift_JIS" to "日本語/雪.txt")) {
            val charset = Charset.forName(encoding)
            val payload = byteArrayOf(42, 7, 8)
            val bytes = ByteArrayOutputStream().also { sink ->
                ZipOutputStream(sink, charset).use { writer ->
                    val entry = ZipEntry(name).also {
                        it.method = ZipEntry.STORED
                        it.size = payload.size.toLong()
                        it.crc = CRC32().also { crc -> crc.update(payload) }.value
                    }
                    writer.putNextEntry(entry); writer.write(payload); writer.closeEntry()
                }
            }.toByteArray()
            val decoder = ArchiveNameDecoder { String(it, charset) }
            ArchiveKodec.open(ByteArrayRandomAccessSource(bytes), decoder).use { reader ->
                val entry = reader.entries().single()
                assertEquals(name, entry.name, encoding)
                assertContentEquals(name.toByteArray(charset), entry.nameBytes, encoding)
                assertContentEquals(payload, reader.read(entry), encoding)
            }
        }
    }
}
