package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** GNU thin ar archives (#125): `ar rcT thin.a member.o sub/data.txt` from GNU binutils. */
@OptIn(ExperimentalEncodingApi::class)
class ArThinTest {
    private val thin = Base64.decode("ITx0aGluPgovICAgICAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICAyMiAgICAgICAgYAoAAAABAAAArmFyY2hpdmVfcHJvYmUALy8gICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgMjQgICAgICAgIGAKbWVtYmVyLm8vCnN1Yi9kYXRhLnR4dC8KLzAgICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDY0NCAgICAgMTIzMiAgICAgIGAKLzEwICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDY0NCAgICAgMTkgICAgICAgIGAK")
    private val objectFile = ByteArray(1232) { it.toByte() }
    private val data = "second member data\n".encodeToByteArray()
    private val files = mapOf("member.o" to objectFile, "sub/data.txt" to data)
    private val requested = ArrayList<String>()
    private val resolver = ExternalMemberResolver { path -> requested.add(path); files[path] }

    @Test
    fun listsWithoutTheMemberFiles() {
        assertEquals(ArchiveFormat.AR, ArchiveKodec.sniff(thin))
        val reader = ArchiveKodec.open(thin)
        assertIs<ArReader>(reader)
        reader.use {
            val entries = reader.entries()
            assertEquals(listOf("member.o", "sub/data.txt"), entries.map { it.name })
            assertEquals(listOf(1232L, 19L), entries.map { it.size })
            assertEquals(listOf(0L, 0L), entries.map { it.compressedSize })
            assertEquals(listOf(0x81A4, 0x81A4), entries.map { it.unixMode })
            val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(entries[0]) }
            assertTrue(error.message!!.contains("ExternalMemberResolver"), error.message)
        }
    }

    @Test
    fun readsThroughTheResolver() {
        ArReader(ByteArrayRandomAccessSource(thin), ArchiveNameDecoder.UTF8, ArchiveLimits.DEFAULT, resolver).use { reader ->
            val (obj, text) = reader.entries()
            assertContentEquals(objectFile, reader.read(obj))
            assertContentEquals(data, reader.read(text))
        }
        assertEquals(listOf("member.o", "sub/data.txt"), requested)
        ArchiveKodec.open(thin, FormatRegistry.DEFAULT, options = ArchiveOpenOptions { externalMembers = resolver }).use { reader ->
            assertContentEquals(data, reader.read(reader.entries()[1]))
        }
    }

    @Test
    fun missingOrWrongSizedMembersFail() {
        val missing = ExternalMemberResolver { null }
        val wrongSize = ExternalMemberResolver { ByteArray(3) }
        for (bad in listOf(missing, wrongSize)) {
            ArReader(ByteArrayRandomAccessSource(thin), ArchiveNameDecoder.UTF8, ArchiveLimits.DEFAULT, bad).use { reader ->
                val error = assertFailsWith<ArchiveFormatException> { reader.read(reader.entries()[1]) }
                assertEquals("sub/data.txt", error.entryName)
            }
        }
        ArReader(ByteArrayRandomAccessSource(thin), ArchiveNameDecoder.UTF8, ArchiveLimits.DEFAULT, resolver).use { reader ->
            assertFailsWith<DecompressionBombException> { reader.read(reader.entries()[0], ArchiveLimits { maxUncompressedBytes = 100 }) }
        }
    }

    @Test
    fun thinMembersMustUseTheNameTable() {
        val inlineName = "!<thin>\n".encodeToByteArray() +
            ("short.o/".padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "644".padEnd(8) + "5".padEnd(10) + "`\n").encodeToByteArray()
        assertFailsWith<ArchiveFormatException> { ArReader(ByteArrayRandomAccessSource(inlineName)).use { it.entries() } }
    }
}
