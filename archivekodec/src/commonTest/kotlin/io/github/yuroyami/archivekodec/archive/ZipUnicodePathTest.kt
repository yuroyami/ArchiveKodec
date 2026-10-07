package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.PathGuard
import kotlin.test.*

class ZipUnicodePathTest {
    private val stored = "fallback.txt".encodeToByteArray()
    private val payload = byteArrayOf(42, 7, 8)

    private fun le(bytes: ByteArray, offset: Int, value: Long, width: Int) {
        for (i in 0 until width) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
    private fun extra(name: ByteArray, version: Int = 1, crc: Long = Crc32.of(stored)): ByteArray =
        ByteArray(9 + name.size).also {
            le(it, 0, 0x7075, 2); le(it, 2, (5 + name.size).toLong(), 2)
            it[4] = version.toByte(); le(it, 5, crc, 4); name.copyInto(it, 9)
        }

    private fun zip(localExtra: ByteArray = byteArrayOf(), centralExtra: ByteArray = byteArrayOf(), flags: Int = 0): ByteArray {
        val original = ZipWriter.write(listOf(ZipWriter.FileSpec("fallback.txt", payload, ZipMethod.STORED)))
        val central = ZipFormat.u32(original, original.size - 6).toInt()
        val local = original.copyOf(42) + localExtra + original.copyOfRange(42, central)
        le(local, 6, flags.toLong(), 2); le(local, 28, localExtra.size.toLong(), 2)
        val directory = original.copyOfRange(central, original.size - 22) + centralExtra
        le(directory, 8, flags.toLong(), 2); le(directory, 30, centralExtra.size.toLong(), 2)
        val end = original.copyOfRange(original.size - 22, original.size)
        le(end, 12, directory.size.toLong(), 4); le(end, 16, local.size.toLong(), 4)
        return local + directory + end
    }

    private fun check(bytes: ByteArray, expected: String) {
        ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entry = reader.entries().single()
            assertEquals(expected, entry.name)
            assertContentEquals(stored, entry.nameBytes)
            assertContentEquals(payload, reader.read(entry.copy()))
        }
    }

    @Test
    fun independentJavaUnicodePathFixturePreservesStoredFallbackBytes() {
        check(NameEncodingFixtures.unicodeZip, "雪/🚀.txt")
    }

    @Test
    fun localOnlyCentralOnlyAndMatchingBothAreSupported() {
        val field = extra("é-雪-🚀".encodeToByteArray())
        check(zip(localExtra = field), "é-雪-🚀")
        check(zip(centralExtra = field), "é-雪-🚀")
        check(zip(field, field), "é-雪-🚀")
    }

    @Test
    fun unknownVersionsStaleCrcAndInvalidOptionalUtf8FallBack() {
        val invalid = listOf(
            extra("雪".encodeToByteArray(), version = 0), extra("雪".encodeToByteArray(), version = 2),
            extra("雪".encodeToByteArray(), crc = Crc32.of(stored) xor 1),
            extra(byteArrayOf(0xc0.toByte(), 0xaf.toByte())),
            byteArrayOf(0x75, 0x70, 4, 0, 1, 2, 3, 4),
        )
        for (field in invalid) {
            check(zip(field, field), "fallback.txt")
            check(zip(field, extra("雪".encodeToByteArray())), "雪")
            check(zip(extra("雪".encodeToByteArray()), field), "雪")
        }
    }

    @Test
    fun flaggedUtf8TakesPrecedenceOverUnicodeExtraAndLegacyDecoder() {
        val field = extra("other".encodeToByteArray())
        val reader = ZipReader(ByteArrayRandomAccessSource(zip(field, field, 0x800)), ArchiveNameDecoder { error("legacy decoder called") })
        reader.use { assertEquals("fallback.txt", it.entries().single().name) }
    }

    @Test
    fun validUnicodeNameBypassesTheLegacyDecoder() {
        ZipReader(ByteArrayRandomAccessSource(NameEncodingFixtures.unicodeZip), ArchiveNameDecoder { error("legacy decoder called") }).use {
            assertEquals("雪/🚀.txt", it.entries().single().name)
        }
    }

    @Test
    fun repeatedIdenticalFieldsAreAcceptedButConflictingValidNamesFail() {
        val first = extra("first".encodeToByteArray())
        val second = extra("second".encodeToByteArray())
        check(zip(first + first, first + first), "first")
        for (data in listOf(zip(first + second), zip(centralExtra = first + second), zip(first, second))) {
            val error = assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(data)).entries() }
            assertTrue(error.message!!.contains("conflicting"))
            assertNotNull(error.offset)
        }
    }

    @Test
    fun truncatedExtraFramingIsRejectedAtTheSourceField() {
        for (field in listOf(byteArrayOf(1), byteArrayOf(0x75, 0x70, 5, 0, 1))) {
            val local = assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(zip(localExtra = field))).entries() }
            assertEquals(42L, local.offset)
            val data = zip(centralExtra = field)
            val central = ZipFormat.u32(data, data.size - 6)
            val directory = assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(data)).entries() }
            assertEquals(central + 58, directory.offset)
        }
    }

    @Test
    fun unicodeNamesDoNotBypassRawLocalHeaderConsistency() {
        val field = extra("safe".encodeToByteArray())
        val data = zip(field, field)
        data[30] = 'x'.code.toByte()
        val error = assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(data)).entries() }
        assertTrue(error.message!!.contains("local filename"))
        assertEquals(30L, error.offset)
    }

    @Test
    fun directorySlashAndUnsafeUnicodeNamesRemainVisibleToPathGuard() {
        for (name in listOf("雪//", "../雪", "a\u0000b")) {
            val data = zip(centralExtra = extra(name.encodeToByteArray()))
            ZipReader(ByteArrayRandomAccessSource(data)).use { reader ->
                val entry = reader.entries().single()
                assertEquals(name, entry.name)
                assertEquals(name.endsWith('/'), entry.isDirectory)
                if (!entry.isDirectory) assertFalse(PathGuard.isSafe(entry.name))
            }
        }
    }
}
