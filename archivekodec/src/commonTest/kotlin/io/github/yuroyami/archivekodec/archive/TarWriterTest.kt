package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveSizeException
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TarWriterTest {
    private fun read(bytes: ByteArray): Pair<List<ArchiveEntry>, List<ByteArray>> {
        val reader = TarReader(ByteArrayRandomAccessSource(bytes))
        try {
            val entries = reader.entries()
            return entries to entries.map { reader.read(it) }
        } finally {
            reader.close()
        }
    }

    private fun string(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.decodeToString(offset, offset + length).trimEnd('\u0000')

    @Test
    fun emptyArchiveContainsExactlyTwoZeroBlocks() {
        val bytes = TarWriter.write(emptyList())
        assertContentEquals(ByteArray(1024), bytes)
        assertTrue(read(bytes).first.isEmpty())
    }

    @Test
    fun ustarHeaderContainsOctalMetadataMagicVersionAndUnsignedChecksum() {
        val file = TarWriter.FileSpec("雪.txt", byteArrayOf(1, 2, 3)) {
            mode = 0xfff
            uid = 0x1fffff
            gid = 123
            modificationTime = 0x1ffffffffL
        }
        val bytes = TarWriter.write(listOf(file))
        assertEquals("0007777", string(bytes, 100, 8))
        assertEquals("7777777", string(bytes, 108, 8))
        assertEquals("0000173", string(bytes, 116, 8))
        assertEquals("00000000003", string(bytes, 124, 12))
        assertEquals("77777777777", string(bytes, 136, 12))
        assertEquals('0'.code.toByte(), bytes[156])
        assertEquals("ustar\u0000" + "00", bytes.decodeToString(257, 265))
        assertEquals(0, bytes[154].toInt())
        assertEquals(32, bytes[155].toInt())
        val checksum = bytes.decodeToString(148, 154).toLong(8)
        val expected = (0 until 512).sumOf { if (it in 148..155) 32L else bytes[it].toLong() and 255L }
        assertEquals(expected, checksum)
        val (entries, bodies) = read(bytes)
        assertEquals(file.modificationTime, entries.single().lastModifiedEpochSeconds)
        assertContentEquals(file.data, bodies.single())
    }

    @Test
    fun eachBodyAndTheArchiveEndUseExactBlockPadding() {
        val files = listOf(0, 1, 511, 512, 513, 1025).map { size ->
            TarWriter.FileSpec("file-$size", ByteArray(size) { (it * 7 + 1).toByte() })
        }
        val bytes = TarWriter.write(files)
        var at = 0
        for (file in files) {
            assertEquals(0, at % 512)
            assertEquals(file.name, string(bytes, at, 100))
            at += 512
            assertContentEquals(file.data, bytes.copyOfRange(at, at + file.data.size))
            at += file.data.size
            while (at % 512 != 0) assertEquals(0, bytes[at++].toInt())
        }
        assertEquals(at + 1024, bytes.size)
        assertContentEquals(ByteArray(1024), bytes.copyOfRange(at, bytes.size))
        val (entries, bodies) = read(bytes)
        assertEquals(files.map { it.name }, entries.map { it.name })
        files.forEachIndexed { i, file -> assertContentEquals(file.data, bodies[i]) }
    }

    @Test
    fun prefixSplitsRespectBothFieldWidthsAndUtf8ByteLengths() {
        for ((prefix, name) in listOf("p".repeat(155) to "n".repeat(100), "雪".repeat(51) to "f")) {
            val full = "$prefix/$name"
            val bytes = TarWriter.write(listOf(TarWriter.FileSpec(full, byteArrayOf(65))))
            assertEquals('0'.code.toByte(), bytes[156])
            assertEquals(name, string(bytes, 0, 100))
            assertEquals(prefix, string(bytes, 345, 155))
            assertEquals(full, read(bytes).first.single().name)
        }
        val exact = "n".repeat(100)
        val bytes = TarWriter.write(listOf(TarWriter.FileSpec(exact, byteArrayOf())))
        assertEquals(exact, string(bytes, 0, 100))
        assertEquals("", string(bytes, 345, 155))
        assertEquals('0'.code.toByte(), bytes[156])
    }

    @Test
    fun unsplittableNamesUseGnuLongRecordsWithAFullTerminatedName() {
        for (name in listOf("n".repeat(101), "雪".repeat(36), "prefix/" + "x".repeat(101), "p".repeat(156) + "/x")) {
            val encoded = name.encodeToByteArray()
            val bytes = TarWriter.write(listOf(TarWriter.FileSpec(name, byteArrayOf(65))))
            assertEquals('L'.code.toByte(), bytes[156])
            assertEquals(encoded.size + 1L, string(bytes, 124, 12).toLong(8))
            assertContentEquals(encoded, bytes.copyOfRange(512, 512 + encoded.size))
            assertEquals(0, bytes[512 + encoded.size].toInt())
            assertEquals('0'.code.toByte(), bytes[1024 + 156])
            val (entries, bodies) = read(bytes)
            assertEquals(listOf(name), entries.map { it.name })
            assertContentEquals(byteArrayOf(65), bodies.single())
        }
    }

    @Test
    fun directoryTypesPreserveNamesAndCannotHideBodies() {
        val names = listOf("dir", "dir/", "雪".repeat(40) + "/")
        val files = names.map { TarWriter.FileSpec(it, byteArrayOf()) { isDirectory = true; mode = 0x1ed } }
        val (entries, bodies) = read(TarWriter.write(files))
        assertEquals(names, entries.map { it.name })
        assertTrue(entries.all { it.isDirectory && it.size == 0L })
        assertTrue(bodies.all { it.isEmpty() })
        assertFailsWith<IllegalArgumentException> { TarWriter.FileSpec("dir/", byteArrayOf(1)) { isDirectory = true } }
        assertFailsWith<ArchiveFormatException> { TarWriter.write(listOf(TarWriter.FileSpec("dir/", byteArrayOf(1)))) }
    }

    @Test
    fun metadataRejectsValuesOutsideTheSelectedOctalWidths() {
        for (mode in listOf(-1, 0x1000, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { TarWriter.FileSpec("x", byteArrayOf()) { this.mode = mode } }
        }
        for (id in listOf(-1, 0x200000, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { TarWriter.FileSpec("x", byteArrayOf()) { uid = id } }
            assertFailsWith<IllegalArgumentException> { TarWriter.FileSpec("x", byteArrayOf()) { gid = id } }
        }
        for (time in listOf(-1L, 0x200000000L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { TarWriter.FileSpec("x", byteArrayOf()) { modificationTime = time } }
        }
    }

    @Test
    fun invalidNamesFailWithoutChangingAcceptedSpellingOrDuplicateOrder() {
        for (name in listOf("", "a\u0000b", "\uD800", "\uDC00")) {
            val failure = assertFailsWith<ArchiveFormatException> { TarWriter.write(listOf(TarWriter.FileSpec(name, byteArrayOf()))) }
            assertEquals(name, failure.entryName)
        }
        val names = listOf("./a", "a//b", "same", "same", " leading", "trailing ")
        val files = names.mapIndexed { index, name -> TarWriter.FileSpec(name, byteArrayOf(index.toByte())) }
        val (entries, bodies) = read(TarWriter.write(files))
        assertEquals(names, entries.map { it.name })
        files.forEachIndexed { i, file -> assertContentEquals(file.data, bodies[i]) }
    }

    @Test
    fun descriptionsAndCopiesOwnBodiesIndependently() {
        val input = byteArrayOf(1, 2, 3)
        val builder = TarWriter.FileSpec.Builder("data", input).apply { uid = 123; gid = 456; modificationTime = 789 }
        val file = builder.build()
        input.fill(9)
        builder.uid = 999
        file.data.fill(8)
        val copy = file.copy { data[0] = 7; mode = 0x1ed }
        assertContentEquals(byteArrayOf(1, 2, 3), file.data)
        assertContentEquals(byteArrayOf(7, 2, 3), copy.data)
        assertEquals(file, file.copy())
        assertEquals(file.hashCode(), file.copy().hashCode())
        assertNotEquals(file, copy)
        assertTrue(TarWriter.FileSpec("x".repeat(1000), input).toString().length < 160)
    }

    @Test
    fun anImpossibleHeaderCountFailsBeforeTraversingTheList() {
        val files = object : AbstractList<TarWriter.FileSpec>() {
            override val size: Int = 50_000_000
            override fun get(index: Int): TarWriter.FileSpec = error("must reject before reading members")
        }
        assertFailsWith<ArchiveSizeException> { TarWriter.write(files) }
    }
}
