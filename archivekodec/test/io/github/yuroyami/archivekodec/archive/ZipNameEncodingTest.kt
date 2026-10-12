package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipNameEncodingTest {
    @Test
    fun independentJavaCp437NamesAndAllHighBytesDecodeExactly() {
        ZipReader(ByteArrayRandomAccessSource(NameEncodingFixtures.cp437Zip)).use { reader ->
            val entries = reader.entries()
            assertEquals(NameEncodingFixtures.cp437Names, entries.map { it.name })
            for ((index, entry) in entries.withIndex()) {
                assertContentEquals(byteArrayOf(index.toByte(), 7, 8), reader.read(entry))
            }
        }
    }

    @Test
    fun independentJavaUtf8NamesIncludeSupplementaryCharacters() {
        ZipReader(ByteArrayRandomAccessSource(NameEncodingFixtures.utf8Zip)).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("café.txt", "雪.txt", "🚀.txt"), entries.map { it.name })
            for ((index, entry) in entries.withIndex()) {
                assertContentEquals(byteArrayOf(index.toByte(), 7, 8), reader.read(entry))
            }
        }
    }

    @Test
    fun unflaggedUtf8LookingBytesAreCp437WithoutGuessing() {
        val data = ZipWriter.write(listOf(ZipWriter.FileSpec("é", byteArrayOf(42), ZipMethod.STORED)))
        val central = data.size - 22 - 48
        data[6] = 0; data[7] = 0; data[central + 8] = 0; data[central + 9] = 0
        ZipReader(ByteArrayRandomAccessSource(data)).use { reader ->
            assertEquals("├⌐", reader.entries().single().name)
        }
    }

    @Test
    fun invalidFlaggedUtf8FailsInsteadOfReplacingNameBytes() {
        val data = ZipWriter.write(listOf(ZipWriter.FileSpec("ab", byteArrayOf(42), ZipMethod.STORED)))
        val central = data.size - 22 - 48
        data[30] = 0xc0.toByte(); data[31] = 0xaf.toByte()
        data[central + 46] = 0xc0.toByte(); data[central + 47] = 0xaf.toByte()
        val error = assertFailsWith<ArchiveFormatException> {
            ZipReader(ByteArrayRandomAccessSource(data)).entries()
        }
        assertEquals((central + 46).toLong(), error.offset)
    }
}
