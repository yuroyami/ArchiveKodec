package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArSymbolIndexTest {
    private val magic = "!<arch>\n".encodeToByteArray()

    private fun header(name: String, size: Long): ByteArray {
        fun field(value: String, width: Int) = value.padEnd(width).encodeToByteArray()
        return field(name, 16) + field("0", 12) + field("0", 6) + field("0", 6) +
            field("100644", 8) + field(size.toString(), 10) + byteArrayOf(0x60, 0x0a)
    }

    private fun member(name: String, data: ByteArray) =
        header(name, data.size.toLong()) + data + if (data.size % 2 == 1) byteArrayOf(10) else byteArrayOf()

    private fun be64(value: Long) = ByteArray(8) { (value ushr (56 - it * 8)).toByte() }

    @Test
    fun realLlvmGnu64ArchiveListsOnlyTheObjectAndReadsItsExactBytes() {
        ArchiveKodec.open(ByteArrayRandomAccessSource(llvmArchive)).use { reader ->
            assertEquals(ArchiveFormat.AR, reader.format)
            val entry = reader.entries().single()
            assertEquals("first-long-member-name.obj", entry.name)
            assertFalse(entry.isDirectory)
            assertEquals(llvmObject.size.toLong(), entry.size)
            assertEquals(246L, entry.dataOffset)
            assertContentEquals(llvmObject, reader.read(entry))
            assertEquals(listOf(entry), reader.entries())
        }
    }

    @Test
    fun emptyGnu32AndGnu64IndicesDoNotBecomeEntries() {
        for ((name, payload) in listOf("/" to ByteArray(4), "/SYM64/" to ByteArray(8))) {
            ArReader(ByteArrayRandomAccessSource(magic + member(name, payload))).use { reader ->
                assertTrue(reader.entries().isEmpty(), name)
                assertTrue(reader.entries().isEmpty(), name)
            }
        }
    }

    @Test
    fun oddIndexPayloadAndFollowingMembersKeepTheirAlignment() {
        // One big-endian 64-bit symbol offset plus its NUL-terminated name: 19 bytes.
        val index = be64(1) + be64(88) + "aa\u0000".encodeToByteArray()
        val bytes = magic + member("/SYM64/", index) + member("a.o/", byteArrayOf(1, 2, 3)) + member("b.o/", byteArrayOf(4, 5))
        ArReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("a.o", "b.o"), entries.map { it.name })
            assertEquals(listOf(148L, 212L), entries.map { it.dataOffset })
            assertContentEquals(byteArrayOf(1, 2, 3), reader.read(entries[0]))
            assertContentEquals(byteArrayOf(4, 5), reader.read(entries[1]))
        }
    }

    @Test
    fun bothIndexWidthsKeepGnuLongNameReferencesWorking() {
        val names = listOf("first-long-member-name.o", "second-long-member-name.o")
        val firstName = (names[0] + "/\n").encodeToByteArray()
        val table = firstName + (names[1] + "/\n").encodeToByteArray()
        for ((name, index) in listOf("/" to ByteArray(4), "/SYM64/" to ByteArray(8))) {
            val bytes = magic + member(name, index) + member("//", table) +
                member("/0", byteArrayOf(11)) + member("/${firstName.size}", byteArrayOf(22, 23))
            ArReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                val entries = reader.entries()
                assertEquals(names, entries.map { it.name }, name)
                assertContentEquals(byteArrayOf(11), reader.read(entries[0]))
                assertContentEquals(byteArrayOf(22, 23), reader.read(entries[1]))
            }
        }
    }

    @Test
    fun similarOrdinaryNamesAndLongTableNamesAreNotFiltered() {
        val raw = listOf("SYM64/", "/SYM64", "/SYM64//", "/SYM64x/", "x/SYM64/")
        val table = "/SYM64/\n".encodeToByteArray()
        val bytes = magic + raw.fold(byteArrayOf()) { acc, name -> acc + member(name, byteArrayOf(42)) } +
            member("//", table) + member("/0", byteArrayOf(43))
        ArReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entries = reader.entries()
            assertEquals(raw.map { it.removeSuffix("/") } + "/SYM64", entries.map { it.name })
            for (entry in entries.dropLast(1)) assertContentEquals(byteArrayOf(42), reader.read(entry))
            assertContentEquals(byteArrayOf(43), reader.read(entries.last()))
        }
    }

    @Test
    fun skippedIndicesStillRequireTheirDeclaredRangeAndAlignmentByte() {
        for (name in listOf("/", "/SYM64/")) {
            for ((size, payload, failureOffset) in listOf(
                Triple(16L, ByteArray(8), 68L), Triple(19L, ByteArray(19), 87L),
            )) {
                ArReader(ByteArrayRandomAccessSource(magic + header(name, size) + payload)).use { reader ->
                    val error = assertFailsWith<ArchiveFormatException>(name) { reader.entries() }
                    assertEquals(failureOffset, error.offset)
                }
            }
        }
    }

    @Test
    fun sparseGnu64MemberBeyondFourGiBHasAnExactLongOffset() {
        val paddingSize = 0x1_00000000L
        val lastHeaderOffset = 150L + paddingSize
        val index = be64(1) + be64(lastHeaderOffset) + "probe\u0000".encodeToByteArray()
        val payload = byteArrayOf(7, 8, 9)
        val parts = listOf(0L to magic, 8L to header("/SYM64/", index.size.toLong()),
            68L to index, 90L to header("padding/", paddingSize),
            lastHeaderOffset to member("last.o/", payload))
        var unusedPayloadReads = 0
        var closes = 0
        val source = object : RandomAccessSource {
            override val size = lastHeaderOffset + 64
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (length == 0) return 0
                if (offset >= size) return -1
                val count = minOf(length.toLong(), size - offset, 3).toInt()
                if ((offset < 90 && offset + count > 68) || (offset < lastHeaderOffset && offset + count > 150)) unusedPayloadReads++
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
            assertEquals(listOf("padding", "last.o"), entries.map { it.name })
            assertEquals(paddingSize, entries[0].size)
            assertEquals(lastHeaderOffset + 60, entries[1].dataOffset)
            assertContentEquals(payload, reader.read(entries[1]))
            assertEquals(0, unusedPayloadReads)
        }
        assertEquals(1, closes)
    }

    // Frozen LLVM 24 GNU64 output and its separately compiled COFF object.
    private val llvmArchive: ByteArray get() = Base64.decode(
        "ITxhcmNoPgovU1lNNjQvICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICAzMCAgICAgICAgYAoAAAAAAAAA" +
        "AQAAAAAAAAC6YXJjaGl2ZV9wcm9iZQAvLyAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAyOCAg" +
        "ICAgICAgYApmaXJzdC1sb25nLW1lbWJlci1uYW1lLm9iai8KLzAgICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAg" +
        "IDY0NCAgICAgNjEwICAgICAgIGAKZIYFAJnXxWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAA" +
        "AAAgAFBgLmRhdGEAAAAAAAAAAAAAAAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAIAAMMAuZGVidWckUwAAAAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAA" +
        "AAAAAAAAAAAAAAAAAAgQALgBAAAAwwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBw" +
        "bGUgY2xhbmcgdmVyc2lvbiAyMS4wLjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAKIr" +
        "lMkBAAAAAAAuZGF0YQAAAAAAAAACAAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAAD" +
        "AAAAAAAuZGVidWckUwAAAAAEAAAAAwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAA" +
        "AABAZmVhdC4wMAAAAAD//wAAAwAAAAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFwcm9iZS5jAAAAAAAAAAAAAAAg" +
        "AAAALmxsdm1fYWRkcnNpZwBhcmNoaXZlX3Byb2JlAA=="
    )
    private val llvmObject: ByteArray get() = Base64.decode(
        "ZIYFAJnXxWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAAAAAAAAAA" +
        "AAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVidWckUwAA" +
        "AAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQALgBAAAA" +
        "wwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lvbiAyMS4w" +
        "LjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAKIrlMkBAAAAAAAuZGF0YQAAAAAAAAAC" +
        "AAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAAAAAEAAAA" +
        "AwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD//wAAAwAA" +
        "AAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFwcm9iZS5jAAAAAAAAAAAAAAAgAAAALmxsdm1fYWRkcnNpZwBhcmNo" +
        "aXZlX3Byb2JlAA=="
    )
}
