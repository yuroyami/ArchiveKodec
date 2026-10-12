package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.security.ArchivePathException
import io.github.yuroyami.archivekodec.security.PathGuard
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/** Lists names as stored, so tests can check raw-name preservation for PathGuard. */
private val RAW_NAMES = ArchiveLimits { rejectUnsafeNames = false }

class ArBsdTest {
    private val magic = "!<arch>\n".encodeToByteArray()
    private val symbols = listOf("__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED")

    private fun header(name: String, size: Long): ByteArray {
        fun field(value: String, width: Int): ByteArray {
            val bytes = value.encodeToByteArray()
            require(bytes.size <= width)
            return ByteArray(width) { 32 }.also { bytes.copyInto(it) }
        }
        return field(name, 16) + field("0", 12) + field("0", 6) + field("0", 6) +
            field("100644", 8) + field(size.toString(), 10) + byteArrayOf(0x60, 10)
    }

    private fun member(name: String, data: ByteArray) = header(name, data.size.toLong()) + data +
        if (data.size % 2 == 1) byteArrayOf(10) else byteArrayOf()

    private fun inline(name: String, data: ByteArray, padding: Int = 0): ByteArray {
        val storedName = name.encodeToByteArray() + ByteArray(padding)
        return member("#1/${storedName.size}", storedName + data)
    }

    private fun realArchive(bytes: ByteArray, offsets: List<Long>) {
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { reader ->
            assertEquals(ArchiveFormat.AR, reader.format)
            val entries = reader.entries()
            assertEquals(listOf("first-long-member-name.o", "second-long-member-name.o"), entries.map { it.name })
            assertEquals(offsets, entries.map { it.dataOffset })
            for ((entry, expected) in entries.zip(listOf(ArBsdFixtures.firstObject, ArBsdFixtures.secondObject))) {
                assertFalse(entry.isDirectory)
                assertEquals(expected.size.toLong(), entry.size)
                assertContentEquals(expected, reader.read(entry))
            }
            assertEquals(entries, reader.entries())
        }
    }

    @Test fun realAppleArchiveSkipsTheSortedIndexAndReadsExactObjects() =
        realArchive(ArBsdFixtures.nativeArchive, listOf(232, 848))

    @Test fun realLlvmArchiveSkipsTheUnsortedIndexAndReadsExactObjects() =
        realArchive(ArBsdFixtures.llvmArchive, listOf(224, 832))

    @Test fun realLlvm64ArchiveSkipsTheWideIndexAndReadsExactObjects() =
        realArchive(ArBsdFixtures.llvm64Archive, listOf(248, 856))

    @Test
    fun realEdgeArchivesPreserveUnicodeSpacesEmptyBodiesAndAlignment() {
        val names = listOf("odd-long-member-name.o", "é long member.o", "雪-long-name-member.o", "trailing name ", "empty-long-member-name.o")
        val payloads = listOf("x", "yz", "abc", "q", "").map { it.encodeToByteArray() }
        for ((bytes, offsets) in listOf(
            ArBsdFixtures.nativeEdges to listOf(92L, 170L, 256L, 336L, 422L),
            ArBsdFixtures.llvmEdges to listOf(96L, 176L, 264L, 344L, 432L),
        )) {
            ArReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                val entries = reader.entries()
                assertEquals(names, entries.map { it.name })
                assertEquals(offsets, entries.map { it.dataOffset })
                for ((entry, expected) in entries.zip(payloads)) {
                    assertEquals(expected.size.toLong(), entry.size)
                    assertContentEquals(expected, reader.read(entry))
                }
            }
        }
    }

    @Test
    fun nameLengthIncludesAllPaddingAndControlsMemberAlignment() {
        for (name in listOf("a", "ab", "é", "雪", " folder/file// ", "#1/13")) {
            for (padding in listOf(0, 1, 4)) {
                for (payload in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(1, 2))) {
                    val stored = inline(name, payload, padding)
                    ArReader(ByteArrayRandomAccessSource(magic + stored + member("next/", byteArrayOf(3)))).use { reader ->
                        val entries = reader.entries()
                        assertEquals(listOf(name, "next"), entries.map { it.name })
                        assertEquals(68L + name.encodeToByteArray().size + padding, entries[0].dataOffset)
                        assertEquals(payload.size.toLong(), entries[0].size)
                        assertEquals(8L + stored.size + 60, entries[1].dataOffset)
                        assertContentEquals(payload, reader.read(entries[0]))
                        assertContentEquals(byteArrayOf(3), reader.read(entries[1]))
                    }
                }
            }
        }
    }

    @Test
    fun inlineNamesKeepEmbeddedControlsForPathValidation() {
        for (name in listOf("line\nname", "nul\u0000name", "safe/\u0000../escape", "tail\n")) {
            ArReader(ByteArrayRandomAccessSource(magic + inline(name, byteArrayOf(42), 4)), RAW_NAMES).use { reader ->
                val entry = reader.entries().single()
                assertEquals(name, entry.name)
                assertContentEquals(byteArrayOf(42), reader.read(entry))
                assertFalse(PathGuard.isSafe(entry.name))
                assertEquals(name, assertFailsWith<ArchivePathException> { PathGuard.sanitize(entry.name) }.entryName)
            }
        }
    }

    @Test
    fun shortNameFieldsTrimOnlyAsciiSpacePadding() {
        for (name in listOf("a\n", "a\t", "a\r", "a\u0000", "safe/\n")) {
            ArReader(ByteArrayRandomAccessSource(magic + member(name, byteArrayOf(42))), RAW_NAMES).use { reader ->
                val entry = reader.entries().single()
                assertEquals(name, entry.name)
                assertFalse(PathGuard.isSafe(entry.name))
                assertContentEquals(byteArrayOf(42), reader.read(entry))
            }
        }
    }

    @Test
    fun invalidInlineLengthsFailWithTheHeaderAndMarkerContext() {
        for (marker in listOf("#1/", "#1/-1", "#1/+1", "#1/1x", "#1/1/", "#1/1 0", "#1/١", "#1/1\n", "#1/1\t", "#1/9999999999999")) {
            ArReader(ByteArrayRandomAccessSource(magic + member(marker, byteArrayOf(42)))).use { reader ->
                val error = assertFailsWith<ArchiveFormatException>(marker) { reader.entries() }
                assertEquals(8L, error.offset)
                assertEquals(marker, error.entryName)
            }
        }
        ArReader(ByteArrayRandomAccessSource(magic + member("#1/2", byteArrayOf(42)))).use { reader ->
            val error = assertFailsWith<ArchiveFormatException> { reader.entries() }
            assertEquals(8L, error.offset)
            assertEquals("#1/2", error.entryName)
        }
    }

    @Test
    fun inlineMembersStillRequireTheirDeclaredRangeAndAlignmentByte() {
        for ((declared, body, offset) in listOf(
            Triple(5L, ByteArray(4), 68L), Triple(5L, ByteArray(5), 73L),
        )) {
            ArReader(ByteArrayRandomAccessSource(magic + header("#1/4", declared) + body)).use { reader ->
                assertEquals(offset, assertFailsWith<ArchiveFormatException> { reader.entries() }.offset)
            }
        }
    }

    @Test
    fun everyLeadingIndexIdentityIsSkippedInDirectAndInlineForms() {
        for (name in symbols) {
            val variants = listOf(inline(name, byteArrayOf(1, 2, 3)), inline(name, byteArrayOf(4), 4)) +
                if (name.length < 16) listOf(member(name, byteArrayOf(5)), member("$name/", byteArrayOf(6))) else emptyList()
            for (index in variants) {
                ArReader(ByteArrayRandomAccessSource(magic + index + member("a.o/", byteArrayOf(7, 8)))).use { reader ->
                    val entry = reader.entries().single()
                    assertEquals("a.o", entry.name)
                    assertEquals(8L + index.size + 60, entry.dataOffset)
                    assertContentEquals(byteArrayOf(7, 8), reader.read(entry))
                }
            }
        }
    }

    @Test
    fun laterReservedNamesAndLeadingLookalikesRemainUserFiles() {
        for (name in symbols) {
            val first = member("a.o/", byteArrayOf(1))
            for (stored in listOf(inline(name, byteArrayOf(2), 4)) +
                if (name.length <= 16) listOf(member(name, byteArrayOf(2))) else emptyList()) {
                ArReader(ByteArrayRandomAccessSource(magic + first + stored)).use { reader ->
                    val entries = reader.entries()
                    assertEquals(listOf("a.o", name), entries.map { it.name })
                    assertContentEquals(byteArrayOf(2), reader.read(entries[1]))
                }
            }
        }
        for (name in listOf("__.SYMDEFx", "__.SYMDEF_64x", "__.SYMDEF SORTEDx", "x/__.SYMDEF", "__.SYMDEF ", "__.SYMDEF/")) {
            ArReader(ByteArrayRandomAccessSource(magic + inline(name, byteArrayOf(3), 4))).use { reader ->
                val entry = reader.entries().single()
                assertEquals(name, entry.name)
                assertContentEquals(byteArrayOf(3), reader.read(entry))
            }
        }
    }

    @Test
    fun gnuAndCoffTableReferencesDoNotBecomeBsdIndices() {
        for (nul in listOf(false, true)) {
            val names = symbols.map { (it + if (nul) "\u0000" else "/\n").encodeToByteArray() }
            val table = names.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
            var bytes = magic + member("/", ByteArray(4)) + if (nul) member("/", ByteArray(8)) else byteArrayOf()
            bytes += member("//", table)
            var offset = 0
            for ((index, name) in names.withIndex()) {
                bytes += member("/$offset", byteArrayOf(index.toByte()))
                offset += name.size
            }
            ArReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                val entries = reader.entries()
                assertEquals(symbols, entries.map { it.name })
                for ((index, entry) in entries.withIndex()) assertContentEquals(byteArrayOf(index.toByte()), reader.read(entry))
            }
        }
    }

    @Test
    fun skippedIndicesValidateBoundsWithoutReadingIndexContents() {
        for (inlineName in listOf(false, true)) {
            val name = "__.SYMDEF"
            val nameBytes = if (inlineName) name.encodeToByteArray() + ByteArray(3) else byteArrayOf()
            val marker = if (inlineName) "#1/${nameBytes.size}" else name
            val index = member(marker, nameBytes + ByteArray(17))
            val bytes = magic + index + member("a.o/", byteArrayOf(42))
            val backing = ByteArrayRandomAccessSource(bytes)
            var indexReads = 0
            val source = object : RandomAccessSource {
                override val size = backing.size
                override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                    val count = minOf(length, 3)
                    if (offset < 68 + nameBytes.size + 17 && offset + count > 68 + nameBytes.size) indexReads++
                    return backing.read(offset, into, intoOffset, count)
                }
            }
            ArReader(source).use { reader ->
                val entry = reader.entries().single()
                assertEquals("a.o", entry.name)
                assertContentEquals(byteArrayOf(42), reader.read(entry))
            }
            assertEquals(0, indexReads)
            ArReader(ByteArrayRandomAccessSource(magic + header(marker, 20L + nameBytes.size) + nameBytes + ByteArray(10))).use { reader ->
                assertEquals(68L, assertFailsWith<ArchiveFormatException> { reader.entries() }.offset)
            }
        }
    }

    @Test
    fun emptyInlineNamesRemainInvalidPathsWithoutConsumingPayloadBytes() {
        for ((stored, data, offset) in listOf(
            Triple(member("#1/0", byteArrayOf(42)), byteArrayOf(42), 68L),
            Triple(member("#1/3", ByteArray(3)), byteArrayOf(), 71L),
        )) {
            ArReader(ByteArrayRandomAccessSource(magic + stored), RAW_NAMES).use { reader ->
                val entry = reader.entries().single()
                assertEquals("", entry.name)
                assertEquals(offset, entry.dataOffset)
                assertContentEquals(data, reader.read(entry))
                assertFalse(PathGuard.isSafe(entry.name))
            }
        }
    }

    @Test
    fun unrepresentableInlineNameFailsBeforeAnyNameReadOrAllocation() {
        val marker = "#1/2147483648"
        val prefix = magic + header(marker, 2147483650)
        val source = object : RandomAccessSource {
            override val size = prefix.size.toLong() + 2147483650
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                assertTrue(offset < prefix.size, "name body must not be read")
                val count = minOf(length, 3, prefix.size - offset.toInt())
                prefix.copyInto(into, intoOffset, offset.toInt(), offset.toInt() + count)
                return count
            }
        }
        ArReader(source).use { reader ->
            val error = assertFailsWith<ArchiveFormatException> { reader.entries() }
            assertEquals(8L, error.offset)
            assertEquals(marker, error.entryName)
        }
    }

    @Test
    fun sparseInlinePayloadBeyondFourGiBKeepsItsExactLongOffset() {
        val paddingSize = 0x1_00000000L
        val lastHeader = 68L + paddingSize
        val name = "last-long-member-name.o"
        val nameBytes = name.encodeToByteArray() + ByteArray(3)
        val payload = byteArrayOf(7, 8, 9)
        val last = member("#1/${nameBytes.size}", nameBytes + payload)
        val parts = listOf(0L to magic, 8L to header("padding/", paddingSize), lastHeader to last)
        var unusedPayloadReads = 0
        var closes = 0
        val source = object : RandomAccessSource {
            override val size = lastHeader + last.size
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (length == 0) return 0
                if (offset >= size) return -1
                val count = minOf(length.toLong(), size - offset, 3).toInt()
                if (offset < lastHeader && offset + count > 68) unusedPayloadReads++
                into.fill(0, intoOffset, intoOffset + count)
                for ((start, bytes) in parts) {
                    val first = maxOf(offset, start)
                    val end = minOf(offset + count, start + bytes.size)
                    if (first < end) bytes.copyInto(into, intoOffset + (first - offset).toInt(), (first - start).toInt(), (end - start).toInt())
                }
                return count
            }
            override fun close() { closes++ }
        }
        ArReader(source).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("padding", name), entries.map { it.name })
            assertEquals(paddingSize, entries[0].size)
            assertEquals(lastHeader + 60 + nameBytes.size, entries[1].dataOffset)
            assertContentEquals(payload, reader.read(entries[1]))
            assertEquals(0, unusedPayloadReads)
        }
        assertEquals(1, closes)
    }

    @Test
    fun incompleteInlineNameReadsRetainContextAndOnceOnlyCleanup() {
        val name = "long-member-name.o"
        val marker = "#1/${name.encodeToByteArray().size}"
        val bytes = magic + inline(name, byteArrayOf(42))
        val backing = ByteArrayRandomAccessSource(bytes)
        var closes = 0
        val source = object : RandomAccessSource {
            override val size = backing.size
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int =
                if (offset >= 74) -1 else backing.read(offset, into, intoOffset, minOf(length, 3))
            override fun close() { closes++ }
        }
        val reader = ArReader(source)
        val error = assertFailsWith<ArchiveFormatException> { reader.entries() }
        assertEquals(74L, error.offset)
        assertEquals(marker, error.entryName)
        assertEquals(0, closes)
        reader.close()
        reader.close()
        assertEquals(1, closes)
    }
}
