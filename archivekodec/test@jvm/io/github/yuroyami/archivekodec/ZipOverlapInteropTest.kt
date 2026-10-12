package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zipOverlapIssueFixture
import io.github.yuroyami.archivekodec.archive.zipOverlappingPair
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipOverlapInteropTest {
    @Test
    fun jdkDecodesBothOverlappingMembersWithValidChecksumsButReaderRejectsTheirStructure() {
        val fixtures = mutableListOf(zipOverlapIssueFixture() to 31L)
        for (method in listOf(ZipMethod.STORED, ZipMethod.DEFLATED)) {
            for (extra in listOf(false, true)) for (reversed in listOf(false, true)) {
                val boundary = if (extra) 35L else if (method == ZipMethod.STORED) 31L else 36L
                fixtures.add(zipOverlappingPair(method, extra, reversed) to boundary)
            }
        }
        for ((bytes, boundary) in fixtures) {
            val path = Files.createTempFile("archivekodec-overlap-", ".zip")
            try {
                Files.write(path, bytes)
                ZipFile(path.toFile()).use { zip ->
                    assertEquals(2, zip.size())
                    for (entry in zip.entries()) zip.getInputStream(entry).use { input ->
                        val data = input.readAllBytes()
                        assertEquals(entry.size, data.size.toLong())
                        assertEquals(entry.crc, CRC32().also { it.update(data) }.value)
                        if (entry.name == "b") assertContentEquals("SHARED-PAYLOAD".encodeToByteArray(), data)
                    }
                }
            } finally { Files.deleteIfExists(path) }
            ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                val error = assertFailsWith<ArchiveFormatException> { reader.entries() }
                assertEquals("a", error.entryName)
                assertEquals(boundary, error.offset)
            }
        }
    }
}
