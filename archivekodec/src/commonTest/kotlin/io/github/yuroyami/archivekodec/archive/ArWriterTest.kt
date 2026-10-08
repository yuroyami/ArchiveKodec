package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveSizeException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.ar.ArWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArWriterTest {
    private fun read(files: List<ArWriter.FileSpec>): Pair<List<String>, List<ByteArray>> {
        val reader = ArReader(ByteArrayRandomAccessSource(ArWriter.write(files)))
        try {
            val entries = reader.entries()
            return entries.map { it.name } to entries.map { reader.read(it) }
        } finally {
            reader.close()
        }
    }

    @Test
    fun emptyArchiveIsExactlyTheMagic() {
        assertContentEquals("!<arch>\n".encodeToByteArray(), ArWriter.write(emptyList()))
        assertEquals(emptyList(), read(emptyList()).first)
    }

    @Test
    fun shortMembersUseFixedHeadersAndNewlinePadding() {
        val files = listOf(ArWriter.FileSpec("odd", byteArrayOf(1, 2, 3)), ArWriter.FileSpec("empty", byteArrayOf()))
        val archive = ArWriter.write(files)
        assertEquals(132, archive.size)
        assertEquals("odd/            ", archive.decodeToString(8, 24))
        assertEquals("0           ", archive.decodeToString(24, 36))
        assertEquals("0     0     100644  3         `\n", archive.decodeToString(36, 68))
        assertEquals(10, archive[71].toInt())
        assertEquals("empty/          ", archive.decodeToString(72, 88))
        val (names, bodies) = read(files)
        assertEquals(listOf("odd", "empty"), names)
        assertContentEquals(byteArrayOf(1, 2, 3), bodies[0])
        assertContentEquals(byteArrayOf(), bodies[1])
    }

    @Test
    fun longUnicodeAndSpaceNamesRoundTripWithoutNormalization() {
        val names = listOf("123456789012345", "1234567890123456", "12345678901234567", "雪雪雪雪雪", "雪雪雪雪雪雪", " leading", "trailing ", "inner space", "#1")
        val files = names.mapIndexed { index, name -> ArWriter.FileSpec(name, ByteArray(index) { index.toByte() }) }
        val (restored, bodies) = read(files)
        assertEquals(names, restored)
        for (i in files.indices) assertContentEquals(files[i].data, bodies[i])
    }

    @Test
    fun duplicateNamesKeepOrderAndReuseOnlyTheNameTable() {
        val name = "a-long-duplicate-member-name"
        val files = listOf(ArWriter.FileSpec(name, byteArrayOf(1)), ArWriter.FileSpec(name, byteArrayOf(2)))
        val archive = ArWriter.write(files)
        assertEquals("//              ", archive.decodeToString(8, 24))
        val tableSize = archive.decodeToString(56, 66).trim().toInt()
        assertEquals(name.encodeToByteArray().size + 2, tableSize)
        val (names, bodies) = read(files)
        assertEquals(listOf(name, name), names)
        assertContentEquals(byteArrayOf(1), bodies[0])
        assertContentEquals(byteArrayOf(2), bodies[1])
    }

    @Test
    fun everyHeaderStartsEvenForOddNameTablesAndBodies() {
        for (name in listOf("1234567890123456", "12345678901234567")) {
            val archive = ArWriter.write(listOf(ArWriter.FileSpec(name, byteArrayOf(1)), ArWriter.FileSpec("last", byteArrayOf(2, 3))))
            var at = 8
            var records = 0
            while (at < archive.size) {
                assertEquals(0, at % 2)
                assertEquals("`\n", archive.decodeToString(at + 58, at + 60))
                val size = archive.decodeToString(at + 48, at + 58).trim().toInt()
                if (size % 2 == 1) assertEquals(10, archive[at + 60 + size].toInt())
                at += 60 + size + size % 2
                records++
            }
            assertEquals(archive.size, at)
            assertEquals(3, records)
        }
    }

    @Test
    fun metadataUsesDecimalFieldsAndOctalModeIncludingFieldBoundaries() {
        val file = ArWriter.FileSpec("meta", byteArrayOf()) {
            modificationTime = 999_999_999_999L
            uid = 999_999
            gid = 123_456
            mode = 0xFFFFFF
        }
        val archive = ArWriter.write(listOf(file))
        assertEquals("999999999999999999123456777777770         `\n", archive.decodeToString(24, 68))
        assertContentEquals(archive, ArWriter.write(listOf(file)))
    }

    @Test
    fun invalidMetadataCannotCreateAFileDescription() {
        val builder = ArWriter.FileSpec.Builder("meta", byteArrayOf())
        for (value in listOf(-1L, 1_000_000_000_000L, Long.MAX_VALUE)) {
            builder.modificationTime = value
            assertFailsWith<IllegalArgumentException> { builder.build() }
        }
        builder.modificationTime = 0
        for (value in listOf(-1, 1_000_000, Int.MAX_VALUE)) {
            builder.uid = value
            assertFailsWith<IllegalArgumentException> { builder.build() }
        }
        builder.uid = 0
        for (value in listOf(-1, 1_000_000, Int.MAX_VALUE)) {
            builder.gid = value
            assertFailsWith<IllegalArgumentException> { builder.build() }
        }
        builder.gid = 0
        for (value in listOf(-1, 0x1000000, Int.MAX_VALUE)) {
            builder.mode = value
            assertFailsWith<IllegalArgumentException> { builder.build() }
        }
        builder.mode = 0
        assertEquals(0, builder.build().mode)
    }

    @Test
    fun namesRejectPathsReservedIndicesControlsAndMalformedUnicode() {
        val names = listOf("", ".", "..", "a/b", "../x", "a\\b", "C:x", "a\u0000b", "a\nb", "a\rb", "a\tb", "a\u007fb", "\uD800", "\uDC00",
            "__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED", "/", "//", "/SYM64/")
        for (name in names) {
            val error = assertFailsWith<ArchiveFormatException>(name) { ArWriter.write(listOf(ArWriter.FileSpec(name, byteArrayOf()))) }
            assertEquals(name, error.entryName)
        }
    }

    @Test
    fun descriptionsAndCopiesOwnTheirDataIndependentlyOfBuildersAndGetters() {
        val input = byteArrayOf(1, 2, 3)
        val builder = ArWriter.FileSpec.Builder("data", input)
        val file = builder.build()
        input.fill(9)
        builder.name = "changed"
        builder.uid = 123
        file.data.fill(8)
        val copy = file.copy { data[0] = 7; gid = 456 }
        assertContentEquals(byteArrayOf(1, 2, 3), file.data)
        assertContentEquals(byteArrayOf(7, 2, 3), copy.data)
        assertEquals(file, file.copy())
        assertEquals(file.hashCode(), file.copy().hashCode())
        assertNotEquals(file, copy)
        assertTrue(ArWriter.FileSpec("x".repeat(1000), input).toString().length < 160)
    }

    @Test
    fun anImpossibleHeaderCountFailsBeforeTraversingTheList() {
        val files = object : AbstractList<ArWriter.FileSpec>() {
            override val size = 50_000_000
            override fun get(index: Int): ArWriter.FileSpec = error("must reject before reading members")
        }
        assertFailsWith<ArchiveSizeException> { ArWriter.write(files) }
    }
}
