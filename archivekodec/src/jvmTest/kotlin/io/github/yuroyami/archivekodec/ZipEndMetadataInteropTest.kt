package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.zipMetadataBase
import io.github.yuroyami.archivekodec.archive.zipEntryMetadata
import io.github.yuroyami.archivekodec.archive.zipWithComment
import io.github.yuroyami.archivekodec.archive.zip64WithComment
import io.github.yuroyami.archivekodec.archive.putLe
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ZipEndMetadataInteropTest {
    @Test
    fun falseEndRecordInCommentAgreesWithJdk() {
        for (original in listOf(zipMetadataBase(), zip64WithComment(0))) {
            val comment = ByteArray(22)
            putLe(comment, 0, ZipFormat.EOCD, 4); putLe(comment, 20, 7, 2)
            val bytes = zipWithComment(original, comment)
            val path = Files.createTempFile("archivekodec-end-comment-", ".zip")
            try {
                Files.write(path, bytes)
                ZipFile(path.toFile()).use { jdk ->
                    val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
                    assertEquals(jdk.size(), reader.entries().size)
                    assertEquals("a.txt", reader.entries().single().name)
                    jdk.getInputStream(jdk.getEntry("a.txt")).use {
                        assertContentEquals(it.readAllBytes(), reader.read(reader.entries().single()))
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun conditionalZip64EntryPayloadsAgreeWithJdk() {
        // The JDK accepts only 8/16/24/28-byte ZIP64 blocks; common tests cover
        // all 15 conditional layouts, including short blocks with a disk field.
        // Its public entry metadata also misreads compressed-only layouts, so
        // use the fixture's specified values and compare decoded payloads.
        for (mask in (1..7).toList() + 15) {
            val bytes = zipEntryMetadata(mask)
            val path = Files.createTempFile("archivekodec-zip64-fields-", ".zip")
            try {
                Files.write(path, bytes)
                ZipFile(path.toFile()).use { jdk ->
                    val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
                    val entry = reader.entries().single()
                    assertEquals(3L, entry.size)
                    assertEquals(3L, entry.compressedSize)
                    jdk.getInputStream(jdk.getEntry("a.txt")).use {
                        assertContentEquals(it.readAllBytes(), reader.read(entry))
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

}
