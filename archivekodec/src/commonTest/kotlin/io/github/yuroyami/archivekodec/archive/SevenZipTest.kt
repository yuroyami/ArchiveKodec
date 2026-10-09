package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.sevenzip.SevenZipReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 7z (#21). Fixtures come from py7zr 1.1.3: a.txt is "aaa" (mode 0755), b.txt is "bbb", all modified at 1700000001. */
@OptIn(ExperimentalEncodingApi::class)
class SevenZipTest {
    private fun b64(text: String) = Base64.decode(text)
    private val solid = b64("N3q8ryccAARj/tjXcAAAAAAAAAAUAAAAAAAAACeHPLkBAAVhYWFiYmIA4ABwAF5dAACBMweuD85vVl756sDh8hK5fKk3GmoNiA19ZZACPhtPvqKWGhrKVew/ZQTMA7WXW6pe9Sm21DPKRX9ee+l5e53cl123mXv/t9e4DE3ER9goyzFvojAv+UHiU4gAAAAAFwYKAQlmAAcLAQABISEBGAxxAAA=")          // default: LZMA2 + BCJ x86, LZMA-compressed header
    private val tree = b64("N3q8ryccAATwz6W4owAAAAAAAAAWAAAAAAAAAKIFbbUBABFhYWFiYmJuZXN0ZWQgZmlsZQoA4ADwAIVdAACBMweuD89dLwwHsMPaKtdYZKyzeM5U3aaNkbZq16/bpCZYacyTbnTmrPkYVUK5ESnmgGw/D0QatDXoms01si6HHCXo3uz29jjKDV+cWr7yURTUgrO3z1uH3CZ1l2Es6i7g64u5nErFQOHFKUpdbOD5wpCHCzLIVmqZVZPtLp5AV3vXAAAAFwYWAQmAjQAHCwEAASEhARgMgPEAAA==")            // adds empty.txt, dir/, dir/sub/ and dir/sub/n.txt
    private val coders = mapOf(
        CodecId.LZMA to b64("N3q8ryccAASk632VcgAAAAAAAAAUAAAAAAAAAKS7Lg8AMOgUtCIH////74AAAOAAbABcXQAAgTMHrg/Ox1yBCSqu1WJk20+km3rWOLR/dGvp3M8G2llEuibAUzFD8tBDEHGFo69NkqkeoZ4oT7cq51xY8/2xE9o/qoZ//CFUmyl5jGpLBx/UNfSz/NdWwAAAAAAXBg4BCWQABwsBAAEhIQEYDG0AAA=="), CodecId.STORE to b64("N3q8ryccAAR9eHDDYgAAAAAAAAAUAAAAAAAAAEYpQhVhYWFiYmLgAGQAVF0AAIEzB64P2rHI9cJHfH+nTXxejiFKR4U0d4IjUfuiM0uCdGHHYBwL/dfks+j6DXFNZfCpD1+iD5F9OJ/6hvFEUjbz2xybbBqE87RyS5Yt4Yu8AAAAABcGBgEJXAAHCwEAASEhARgMZQAA"), CodecId.DEFLATE to b64("N3q8ryccAAQ/OL5HZwAAAAAAAAAUAAAAAAAAANKoCBRLTExMSkoCAOAAaABXXQAAgTMHrg/OP8PniCoxAJAz8J47pZfFcZNnuoWMB5lSwPD6KLClcH1VmsgrsbbelK1apvZHZT5p1M3rCRNUG8nO3Aj+HBuVdvcU0d4KDDWDBvpRPVQAAAAAFwYIAQlfAAcLAQABISEBGAxpAAA="),
        CodecId.BZIP2 to b64("N3q8ryccAARd2RchhwAAAAAAAAAUAAAAAAAAAMbWHidCWmg5MUFZJlNZ5+AsQwAAAAEAMAAgADCASeoScXckU4UJDn4CxDDgAGgAWF0AAIEzB64P0Hif/J8/Rzvn0Xa8e9th56lAGaBf9pnAmTCuM+/44EoGS4UaKNu2XY9BPiW9Cpybi4sfLdHMFmomkgSq/CA9+RntXy+/lViZCm/HlXfioAAAAAAXBicBCWAABwsBAAEhIQEYDGkAAA=="), CodecId.LZMA2 to b64("N3q8ryccAARj/tjXcAAAAAAAAAAUAAAAAAAAACeHPLkBAAVhYQABAQAA4ABwAF5dAACBMweuD85vVl756sDh8hKzihRO7xQgq8/63Wt6cXOk+mrafhKjMdiJUbM9TE9QB5RhdEsmAQTxdDJo6BqSHNgp8tCwHXicEIP2hfrW+QJ9WR50DMUDuvL/dWAAAAAAFwYKAQlmAAcLAQABISEBGAxxAAA="),
    )
    private val aes = b64("N3q8ryccAAQt7LdtjgAAAAAAAAAVAAAAAAAAAGdP9lVPIjcok8Dxf/gnl6dqqaYR4ACEAHZdAACBMweuD87zclAFD5wbMrL665Rvo9Jx1ocJu8FgY825qH4hCOdNVIMZjpiAaCyAqLBZiA/kMv6+GgfTDFQ762fxZ2+Z/DSj8U18Wl+ftdj4f4ynwarDMayl8hgP8/1f0NvH9TDIhIFs9LbeZWrVwTV2cygAAAAAFwYQAQl+AAcLAQABISEBGAyAhQAA")              // password "secret"; the header is not encrypted

    // big1.txt: 40,000 random words; big2.txt: 4,000 bytes dense with E8/E9, so BCJ really converts.
    private val big = Base64.decode(
        "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")

    private fun open(bytes: ByteArray) = SevenZipReader(ByteArrayRandomAccessSource(bytes))

    @Test
    fun solidFolderListsBothFilesAndDecodesOnce() {
        val reader = ArchiveKodec.open(solid)
        assertIs<SevenZipReader>(reader)
        reader.use {
            val (a, b) = reader.entries()
            assertEquals("a.txt", a.name); assertEquals(3L, a.size)
            assertEquals("b.txt", b.name); assertEquals(3L, b.size)
            assertEquals(CodecId.LZMA2, a.codec)
            assertEquals("aaa", reader.read(a).decodeToString())
            assertEquals("bbb", reader.read(b).decodeToString())
            assertEquals("aaa", reader.read(a).decodeToString())
            assertEquals(1, reader.folderDecodes)
        }
        assertEquals(ArchiveFormat.SEVEN_ZIP, ArchiveKodec.sniff(solid))
    }

    @Test
    fun everySupportedCoderReads() {
        for ((codec, bytes) in coders) open(bytes).use { reader ->
            val (a, b) = reader.entries()
            assertEquals(codec, a.codec)
            assertEquals("aaa", reader.read(a).decodeToString(), codec.toString())
            assertEquals("bbb", reader.read(b).decodeToString(), codec.toString())
        }
    }

    @Test
    fun treeMetadata() {
        open(tree).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("a.txt", "b.txt", "empty.txt", "dir", "dir/sub", "dir/sub/n.txt"), entries.map { it.name })
            assertEquals(listOf(false, false, false, true, true, false), entries.map { it.isDirectory })
            assertEquals(listOf(0x81ED, 0x81A4, 0x81A4, 0x41ED, 0x41ED, 0x81A4), entries.map { it.unixMode })
            assertTrue(entries.all { it.lastModifiedEpochSeconds == 1_700_000_001L })
            assertEquals("", reader.read(entries[2]).decodeToString())
            assertEquals("", reader.read(entries[3]).decodeToString())
            assertEquals("nested file\n", reader.read(entries[5]).decodeToString())
            assertEquals(Crc32.of("aaa".encodeToByteArray()), entries[0].crc32)
        }
    }

    @Test
    fun bcjFilteredLargeMembersMatchTheirChecksums() {
        open(big).use { reader ->
            val (first, second, small) = reader.entries()
            assertEquals(230099L, first.size); assertEquals(0xB594B9A5L, Crc32.of(reader.read(first)))
            assertEquals(4000L, second.size); assertEquals(0xFD5BB909L, Crc32.of(reader.read(second)))
            assertEquals("aaa", reader.read(small).decodeToString())
            assertEquals(1, reader.folderDecodes)
        }
    }

    @Test
    fun encryptedFoldersListButRefuseToRead() {
        open(aes).use { reader ->
            val entry = reader.entries().first()
            assertTrue(entry.isEncrypted)
            val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(entry) }
            assertTrue(error.message!!.contains("AES"), error.message)
        }
    }

    @Test
    fun corruptionAndLimits() {
        // The packed data starts after the 32-byte signature header; damage it and the CRC catches it.
        val damaged = solid.copyOf().also { it[33] = (it[33].toInt() xor 0x40).toByte() }
        assertFailsWith<ArchiveFormatException> { open(damaged).use { r -> r.entries().forEach { r.read(it) } } }
        val badHeader = solid.copyOf().also { it[solid.size - 3] = (it[solid.size - 3].toInt() xor 1).toByte() }
        assertFailsWith<ArchiveFormatException> { open(badHeader).use { it.entries() } }
        assertFailsWith<ArchiveFormatException> { open(solid.copyOf(40)).use { it.entries() } }
        open(big).use { reader ->
            assertFailsWith<DecompressionBombException> { reader.read(reader.entries().last(), ArchiveLimits { maxUncompressedBytes = 1000 }) }
        }
        open(solid).use { reader ->
            assertFailsWith<ArchiveFormatException> { reader.read(ArchiveEntry("a.txt", 3, false)) }
            assertNull(reader.entries().first().zipMethod)
            assertFalse(reader.entries().first().isEncrypted)
        }
    }
}
