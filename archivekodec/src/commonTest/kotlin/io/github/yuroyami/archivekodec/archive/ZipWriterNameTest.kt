package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.text.CharacterCodingException

class ZipWriterNameTest {
    private val codecs = listOf(CodecId.STORE, CodecId.DEFLATE)

    private fun reject(names: List<String>) {
        for (codec in codecs) for (name in names) {
            val error = assertFailsWith<ArchiveFormatException>("$codec: $name") {
                ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf(65), codec)))
            }
            assertEquals(name, error.entryName)
        }
    }

    @Test
    fun backslashesAreRejectedAnywhere() =
        reject(listOf("a\\b.txt", "\\absolute", "\\\\server\\share", "dir/leaf\\", ".\\..\\/", "\\"))

    @Test
    fun leadingSlashesAreRejected() =
        reject(listOf("/abs.txt", "//server/share", "/", "/./", "/é/😀"))

    @Test
    fun driveAndDevicePrefixesAreRejectedAfterRelativeComponents() =
        reject(('A'..'Z').map { "$it:relative.txt" } + ('a'..'z').map { "$it:/dir/file" } +
            listOf("./C:/file", ".//./c:relative", "RAM:file", "./SYS:/file", ".//.///volume:dir/leaf", "C:"))

    @Test
    fun parentComponentsAreRejectedWithoutNormalizing() =
        reject(listOf("..", "../up.txt", "a/../b.txt", "./..", "a//..//", "é/./../😀", "a/b/../../c"))

    @Test
    fun asciiControlsAndDelAreRejectedAnywhere() =
        reject(((0..31).toList() + 127).flatMap { listOf("${it.toChar()}first", "a/${it.toChar()}/b", "last${it.toChar()}") })

    @Test
    fun malformedUtf16IsRejectedWithItsEncodingCause() {
        for (codec in codecs) for (name in listOf("\uD800", "\uDC00", "a\uDFFF", "\uD800b", "\uDC00\uD800", "\uD800\uD800")) {
            val error = assertFailsWith<ArchiveFormatException> {
                ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf(), codec)))
            }
            assertEquals(name, error.entryName)
            assertIs<CharacterCodingException>(error.cause)
        }
    }

    @Test
    fun acceptedNamesKeepTheirExactBytesInBothHeaders() {
        val names = listOf("", ".", "./", "././/", "a//./b", "dir/", "é/文件😀", "e\u0301.txt", "é.txt",
            " leading /space //", ".../leaf", "dir/report:today.txt", ":colon.txt", "dir/C:leaf.txt")
        val data = byteArrayOf(65, 66, 67)
        for (codec in codecs) for (name in names) {
            val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec(name, data, codec)))
            val encoded = name.encodeToByteArray()
            val central = ZipFormat.u32(bytes, bytes.size - 6).toInt()
            assertContentEquals(encoded, bytes.copyOfRange(30, 30 + ZipFormat.u16(bytes, 26)), name)
            assertContentEquals(encoded, bytes.copyOfRange(central + 46, central + 46 + ZipFormat.u16(bytes, central + 28)), name)
            ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                val entry = reader.entries().single()
                assertEquals(name, entry.name)
                assertContentEquals(encoded, entry.nameBytes)
                assertContentEquals(data, reader.read(entry))
            }
        }
    }

    @Test
    fun aSecondCallerListTraversalCannotSubstituteUncheckedNames() {
        var visits = 0
        val files = object : AbstractList<ZipWriter.FileSpec>() {
            override val size: Int get() = 1
            override fun get(index: Int): ZipWriter.FileSpec {
                check(index == 0)
                visits++
                return ZipWriter.FileSpec(if (visits == 1) "safe/file.txt" else "../unsafe.txt", byteArrayOf(65), CodecId.STORE)
            }
        }
        val bytes = ZipWriter.write(files)
        ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entry = reader.entries().single()
            assertEquals("safe/file.txt", entry.name)
            assertContentEquals(byteArrayOf(65), reader.read(entry))
        }
        assertEquals(1, visits)
    }

    @Test
    fun readersRetainForeignNamesForCallerPathDecisions() {
        for (name in listOf("a\\b.txt", "/abs.txt", "./C:/file", "../up.txt", "a\u0000b", "a\u007fb", "RAM:file")) {
            val data = byteArrayOf(65, 66, 67)
            ZipReader(ByteArrayRandomAccessSource(zipForeignStoredName(name, data))).use { reader ->
                val entry = reader.entries().single()
                assertEquals(name, entry.name)
                assertContentEquals(name.encodeToByteArray(), entry.nameBytes)
                assertContentEquals(data, reader.read(entry))
            }
        }
    }
}
