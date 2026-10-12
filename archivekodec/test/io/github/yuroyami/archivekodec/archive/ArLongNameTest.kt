package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.security.ArchivePathException
import io.github.yuroyami.archivekodec.security.PathGuard
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/** Lists names as stored, so tests can check raw-name preservation for PathGuard. */
private val RAW_NAMES = ArchiveLimits { rejectUnsafeNames = false }

class ArLongNameTest {
    private val magic = "!<arch>\n".encodeToByteArray()
    private fun member(name: String, payload: ByteArray): ByteArray {
        fun field(value: String, width: Int) = value.padEnd(width).encodeToByteArray()
        val header = field(name, 16) + field("0", 12) + field("0", 6) + field("0", 6) +
            field("100644", 8) + field(payload.size.toString(), 10) + byteArrayOf(0x60, 10)
        return header + payload + if (payload.size % 2 == 1) byteArrayOf(10) else byteArrayOf()
    }

    private val names = listOf("first-long-member-name.obj", "second-long-member-name.obj")
    private fun assertRealArchive(bytes: ByteArray, offsets: List<Long>) {
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { reader ->
            assertEquals(ArchiveFormat.AR, reader.format)
            val entries = reader.entries()
            assertEquals(names, entries.map { it.name })
            assertEquals(offsets, entries.map { it.dataOffset })
            for ((entry, expected) in entries.zip(listOf(firstObject, secondObject))) {
                assertFalse(entry.isDirectory)
                assertEquals(expected.size.toLong(), entry.size)
                assertFalse(entry.name.contains('\u0000'))
                assertContentEquals(expected, reader.read(entry))
            }
            assertEquals(entries, reader.entries())
        }
    }

    @Test
    fun realCoffLibraryReturnsSeparateNamesAndExactObjectBytes() =
        assertRealArchive(coffArchive, listOf(388, 1056))

    @Test
    fun realGnuArchiveKeepsItsNamesOffsetsAndObjectBytes() =
        assertRealArchive(gnuArchive, listOf(284, 952))

    @Test
    fun nulNamesStopBeforeFollowingNamesAndTablePadding() {
        val first = " first long member name.o "
        val second = "second-long-member-name.o"
        val firstBytes = (first + '\u0000').encodeToByteArray()
        for (padding in listOf(byteArrayOf(), byteArrayOf(10), byteArrayOf(10, 10, 0))) {
            val table = firstBytes + (second + '\u0000').encodeToByteArray() + padding
            val bytes = magic + member("//", table) + member("/0", byteArrayOf(1)) +
                member("/${firstBytes.size}", byteArrayOf(2, 3))
            ArReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                val entries = reader.entries()
                assertEquals(listOf(first, second), entries.map { it.name })
                assertContentEquals(byteArrayOf(1), reader.read(entries[0]))
                assertContentEquals(byteArrayOf(2, 3), reader.read(entries[1]))
            }
        }
    }

    @Test
    fun nulDelimitedNamesPreserveControlsForPathValidation() {
        for (first in listOf("first\nlong-member-name.o", "folder/\nmember-name.o", "line\r\nmember-name.o")) {
            val second = "second-long-member-name.o"
            val firstBytes = (first + '\u0000').encodeToByteArray()
            val table = firstBytes + (second + '\u0000').encodeToByteArray() + byteArrayOf(10)
            val bytes = magic + member("//", table) + member("/0", byteArrayOf(1)) +
                member("/${firstBytes.size}", byteArrayOf(2))
            ArReader(ByteArrayRandomAccessSource(bytes), RAW_NAMES).use { reader ->
                val entries = reader.entries()
                assertEquals(listOf(first, second), entries.map { it.name })
                assertContentEquals(byteArrayOf(1), reader.read(entries[0]))
                assertContentEquals(byteArrayOf(2), reader.read(entries[1]))
                assertFalse(PathGuard.isSafe(entries[0].name))
                val error = assertFailsWith<ArchivePathException> { PathGuard.sanitize(entries[0].name) }
                assertEquals(first, error.entryName)
            }
        }
    }

    @Test
    fun delimiterDeterminesWhetherTheLastSlashIsPartOfTheName() {
        for ((stored, expected) in listOf(
            "dir/file//\u0000" to "dir/file//", "dir/file///\n" to "dir/file//",
            "name with spaces /\u0000" to "name with spaces /", "trailing / \u0000" to "trailing / ",
            "simple-long-name\n" to "simple-long-name", "folder/file/\n" to "folder/file",
        )) {
            val bytes = magic + member("//", stored.encodeToByteArray()) + member("/0", byteArrayOf(42))
            ArReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                val entry = reader.entries().single()
                assertEquals(expected, entry.name)
                assertContentEquals(byteArrayOf(42), reader.read(entry))
            }
        }
    }

    @Test
    fun gnuLongNameReferencesUseByteOffsetsAndPreserveUnicode() {
        val first = "é-long-first-member.o"
        val second = "雪-long-second-member.o"
        val firstBytes = (first + "/\n").encodeToByteArray()
        val table = firstBytes + (second + "/\n").encodeToByteArray()
        val bytes = magic + member("//", table) + member("/0", byteArrayOf(11)) +
            member("/${firstBytes.size}", byteArrayOf(22))
        ArReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf(first, second), entries.map { it.name })
            assertContentEquals(byteArrayOf(11), reader.read(entries[0]))
            assertContentEquals(byteArrayOf(22), reader.read(entries[1]))
        }
    }

    @Test
    fun referencedNamesMustEndInsideTheirTable() {
        for (prefix in listOf("", "valid-long-name.o\u0000", "valid-long-name.o/\n")) {
            val offset = prefix.encodeToByteArray().size
            val table = (prefix + "dangling-long-name.o").encodeToByteArray()
            val reference = "/$offset"
            val record = member("//", table)
            ArReader(ByteArrayRandomAccessSource(magic + record + member(reference, byteArrayOf(42)))).use { reader ->
                val error = assertFailsWith<ArchiveFormatException> { reader.entries() }
                assertEquals(8L + record.size, error.offset)
                assertEquals(reference, error.entryName)
                assertTrue(error.message!!.contains("unterminated"), error.toString())
            }
        }
    }

    @Test
    fun invalidAndOutsideTableReferencesKeepTheirContext() {
        for (ending in listOf("\u0000", "/\n")) {
            val table = ("long-member-name.o" + ending).encodeToByteArray()
            val record = member("//", table)
            for (reference in listOf("/${table.size}", "/${table.size + 1}", "/2147483647", "/2147483648", "/0x", "/1/")) {
                ArReader(ByteArrayRandomAccessSource(magic + record + member(reference, byteArrayOf(42)))).use { reader ->
                    val error = assertFailsWith<ArchiveFormatException>(reference) { reader.entries() }
                    assertEquals(8L + record.size, error.offset)
                    assertEquals(reference, error.entryName)
                }
            }
        }
    }

    @Test
    fun missingAndEmptyTablesCannotSatisfyAReference() {
        val emptyTable = member("//", byteArrayOf())
        ArReader(ByteArrayRandomAccessSource(magic + emptyTable)).use { reader -> assertTrue(reader.entries().isEmpty()) }
        for (table in listOf(byteArrayOf(), emptyTable)) {
            ArReader(ByteArrayRandomAccessSource(magic + table + member("/0", byteArrayOf(42)))).use { reader ->
                val error = assertFailsWith<ArchiveFormatException> { reader.entries() }
                assertEquals(8L + table.size, error.offset)
                assertEquals("/0", error.entryName)
            }
        }
    }

    // Frozen LLVM 24 archives and separately compiled Windows COFF objects.
    private val coffArchive: ByteArray get() = Base64.decode(
        "ITxhcmNoPgovICAgICAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICAzOCAgICAgICAgYAoAAAACAAAB" +
        "SAAAA+RmaXJzdF9wcm9iZQBzZWNvbmRfcHJvYmUAAC8gICAgICAgICAgICAgICAwICAgICAgICAgICAwICAgICAwICAgICAwICAg" +
        "ICAgIDQ2ICAgICAgICBgCgIAAABIAQAA5AMAAAIAAAABAAIAZmlyc3RfcHJvYmUAc2Vjb25kX3Byb2JlAAAvLyAgICAgICAgICAg" +
        "ICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICA1NiAgICAgICAgYApmaXJzdC1sb25nLW1lbWJlci1uYW1lLm9iagBz" +
        "ZWNvbmQtbG9uZy1tZW1iZXItbmFtZS5vYmoACi8wICAgICAgICAgICAgICAwICAgICAgICAgICAwICAgICAwICAgICA2NDQgICAg" +
        "IDYwOCAgICAgICBgCmSGBQDv2sVqRgEAAA4AAAAAAAAALnRleHQAAAAAAAAAAAAAAAYAAADcAAAAAAAAAAAAAAAAAAAAIABQYC5k" +
        "YXRhAAAAAAAAAAAAAAAAAAAA4gAAAAAAAAAAAAAAAAAAAEAAMMAuYnNzAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACA" +
        "ADDALmRlYnVnJFMAAAAAAAAAAGQAAADiAAAAAAAAAAAAAAAAAAAAQAAwQi80AAAAAAAAAAAAAAAAAAAAAAAARgEAAAAAAAAAAAAA" +
        "AAAAAAAIEAC4AQAAAMMEAAAA8QAAAFgAAAAKAAERAAAAAAAAAABKADwRAAAAANAAFQAAAAAAAAD//wAAAAAAAEFwcGxlIGNsYW5n" +
        "IHZlcnNpb24gMjEuMC4wIChjbGFuZy0yMTAwLjEuMS4xMDEpAAAALnRleHQAAAAAAAAAAQAAAAMBBgAAAAAAAACiK5TJAQAAAAAA" +
        "LmRhdGEAAAAAAAAAAgAAAAMBAAAAAAAAAAAAAAAAAgAAAAAALmJzcwAAAAAAAAAAAwAAAAMBAAAAAAAAAAAAAAAAAwAAAAAALmRl" +
        "YnVnJFMAAAAABAAAAAMBZAAAAAAAAABtee9lBAAAAAAAAAAAAAQAAAAAAAAABQAAAAMBAAAAAAAAAAAAAAAABQAAAAAAQGZlYXQu" +
        "MDAAAAAA//8AAAMAAAAAABIAAAAAAAAAAQAgAAIALmZpbGUAAAAAAAAA/v8AAGcBZmlyc3QuYwAAAAAAAAAAAAAAHgAAAC5sbHZt" +
        "X2FkZHJzaWcAZmlyc3RfcHJvYmUALzI3ICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDY0NCAgICAgNjA5ICAg" +
        "ICAgIGAKZIYFAO/axWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAA" +
        "AAAAAAAAAAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVi" +
        "dWckUwAAAAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQ" +
        "ALgCAAAAwwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lv" +
        "biAyMS4wLjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAHJRNI4BAAAAAAAuZGF0YQAA" +
        "AAAAAAACAAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAA" +
        "AAAEAAAAAwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD/" +
        "/wAAAwAAAAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFzZWNvbmQuYwAAAAAAAAAAAAAfAAAALmxsdm1fYWRkcnNp" +
        "ZwBzZWNvbmRfcHJvYmUACg=="
    )
    private val gnuArchive: ByteArray get() = Base64.decode(
        "ITxhcmNoPgovICAgICAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICAzOCAgICAgICAgYAoAAAACAAAA" +
        "4AAAA3xmaXJzdF9wcm9iZQBzZWNvbmRfcHJvYmUAAC8vICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg" +
        "ICAgIDU4ICAgICAgICBgCmZpcnN0LWxvbmctbWVtYmVyLW5hbWUub2JqLwpzZWNvbmQtbG9uZy1tZW1iZXItbmFtZS5vYmovCgov" +
        "MCAgICAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgNjQ0ICAgICA2MDggICAgICAgYApkhgUA79rFakYBAAAOAAAA" +
        "AAAAAC50ZXh0AAAAAAAAAAAAAAAGAAAA3AAAAAAAAAAAAAAAAAAAACAAUGAuZGF0YQAAAAAAAAAAAAAAAAAAAOIAAAAAAAAAAAAA" +
        "AAAAAABAADDALmJzcwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAwwC5kZWJ1ZyRTAAAAAAAAAABkAAAA4gAAAAAA" +
        "AAAAAAAAAAAAAEAAMEIvNAAAAAAAAAAAAAAAAAAAAAAAAEYBAAAAAAAAAAAAAAAAAAAACBAAuAEAAADDBAAAAPEAAABYAAAACgAB" +
        "EQAAAAAAAAAASgA8EQAAAADQABUAAAAAAAAA//8AAAAAAABBcHBsZSBjbGFuZyB2ZXJzaW9uIDIxLjAuMCAoY2xhbmctMjEwMC4x" +
        "LjEuMTAxKQAAAC50ZXh0AAAAAAAAAAEAAAADAQYAAAAAAAAAoiuUyQEAAAAAAC5kYXRhAAAAAAAAAAIAAAADAQAAAAAAAAAAAAAA" +
        "AAIAAAAAAC5ic3MAAAAAAAAAAAMAAAADAQAAAAAAAAAAAAAAAAMAAAAAAC5kZWJ1ZyRTAAAAAAQAAAADAWQAAAAAAAAAbXnvZQQA" +
        "AAAAAAAAAAAEAAAAAAAAAAUAAAADAQAAAAAAAAAAAAAAAAUAAAAAAEBmZWF0LjAwAAAAAP//AAADAAAAAAASAAAAAAAAAAEAIAAC" +
        "AC5maWxlAAAAAAAAAP7/AABnAWZpcnN0LmMAAAAAAAAAAAAAAB4AAAAubGx2bV9hZGRyc2lnAGZpcnN0X3Byb2JlAC8yOCAgICAg" +
        "ICAgICAgICAwICAgICAgICAgICAwICAgICAwICAgICA2NDQgICAgIDYwOSAgICAgICBgCmSGBQDv2sVqRgEAAA4AAAAAAAAALnRl" +
        "eHQAAAAAAAAAAAAAAAYAAADcAAAAAAAAAAAAAAAAAAAAIABQYC5kYXRhAAAAAAAAAAAAAAAAAAAA4gAAAAAAAAAAAAAAAAAAAEAA" +
        "MMAuYnNzAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAADDALmRlYnVnJFMAAAAAAAAAAGQAAADiAAAAAAAAAAAAAAAA" +
        "AAAAQAAwQi80AAAAAAAAAAAAAAAAAAAAAAAARgEAAAAAAAAAAAAAAAAAAAAIEAC4AgAAAMMEAAAA8QAAAFgAAAAKAAERAAAAAAAA" +
        "AABKADwRAAAAANAAFQAAAAAAAAD//wAAAAAAAEFwcGxlIGNsYW5nIHZlcnNpb24gMjEuMC4wIChjbGFuZy0yMTAwLjEuMS4xMDEp" +
        "AAAALnRleHQAAAAAAAAAAQAAAAMBBgAAAAAAAAByUTSOAQAAAAAALmRhdGEAAAAAAAAAAgAAAAMBAAAAAAAAAAAAAAAAAgAAAAAA" +
        "LmJzcwAAAAAAAAAAAwAAAAMBAAAAAAAAAAAAAAAAAwAAAAAALmRlYnVnJFMAAAAABAAAAAMBZAAAAAAAAABtee9lBAAAAAAAAAAA" +
        "AAQAAAAAAAAABQAAAAMBAAAAAAAAAAAAAAAABQAAAAAAQGZlYXQuMDAAAAAA//8AAAMAAAAAABIAAAAAAAAAAQAgAAIALmZpbGUA" +
        "AAAAAAAA/v8AAGcBc2Vjb25kLmMAAAAAAAAAAAAAHwAAAC5sbHZtX2FkZHJzaWcAc2Vjb25kX3Byb2JlAAo="
    )
    private val firstObject: ByteArray get() = Base64.decode(
        "ZIYFAO/axWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAAAAAAAAAA" +
        "AAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVidWckUwAA" +
        "AAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQALgBAAAA" +
        "wwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lvbiAyMS4w" +
        "LjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAKIrlMkBAAAAAAAuZGF0YQAAAAAAAAAC" +
        "AAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAAAAAEAAAA" +
        "AwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD//wAAAwAA" +
        "AAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFmaXJzdC5jAAAAAAAAAAAAAAAeAAAALmxsdm1fYWRkcnNpZwBmaXJz" +
        "dF9wcm9iZQA="
    )
    private val secondObject: ByteArray get() = Base64.decode(
        "ZIYFAO/axWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAAAAAAAAAA" +
        "AAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVidWckUwAA" +
        "AAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQALgCAAAA" +
        "wwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lvbiAyMS4w" +
        "LjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAHJRNI4BAAAAAAAuZGF0YQAAAAAAAAAC" +
        "AAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAAAAAEAAAA" +
        "AwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD//wAAAwAA" +
        "AAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFzZWNvbmQuYwAAAAAAAAAAAAAfAAAALmxsdm1fYWRkcnNpZwBzZWNv" +
        "bmRfcHJvYmUA"
    )
}
