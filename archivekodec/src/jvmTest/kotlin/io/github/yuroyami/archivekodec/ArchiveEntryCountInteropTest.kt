package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveEntryCountInteropTest {
    @Test
    fun defaultPolicyRejectsARealZipAboveTheEntryLimit() {
        val count = ArchiveLimits.DEFAULT.maxEntries + 1
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            repeat(count) { index ->
                zip.putNextEntry(ZipEntry("f$index").apply {
                    method = ZipEntry.STORED
                    size = 0
                    compressedSize = 0
                    crc = 0
                })
                zip.closeEntry()
            }
        }
        ArchiveKodec.open(ByteArrayRandomAccessSource(output.toByteArray())).use { reader ->
            val error = assertFailsWith<DecompressionBombException> { reader.entries() }
            assertTrue(error.message.orEmpty().contains("entry count $count"))
        }
    }
}
