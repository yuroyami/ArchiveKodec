package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.cab.CabReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.readU32Le
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Microsoft cabinets (#33), gzipped. `stored` and `mszip` come from gcab 1.6 (`-c` and `-cz`)
 * over hello.txt, sub/notes.txt and big.bin (2000 random bytes repeated to 40000), all one
 * folder, modified 2024-05-06T07:08:10Z. `multi` and `span` are built to the cabinet
 * specification and checked with cabextract 1.11 and 7-Zip: `multi` has reserve areas, an
 * MSZIP folder whose blocks refer back into earlier blocks, a stored folder, an empty file and
 * a UTF-8 name; `span` sits in the middle of a cabinet set. `lzx` is one LZX folder with a
 * 2^15 window over 53308 bytes of text, x86-like code with E8 calls, zeros and random bytes:
 * an aligned block spanning the first frame, a verbatim block, an odd-length uncompressed
 * block and a final aligned block, with E8 translation and no block checksums. It was made by
 * a test encoder written for this purpose; cabextract and 7-Zip decode it to the same bytes.
 */
@OptIn(ExperimentalEncodingApi::class)
class CabReaderTest {
    private val storedGz = Base64.decode(
        "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",
    )

    private val mszipGz = Base64.decode(
        "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",
    )

    private val multiGz = Base64.decode(
        "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",
    )

    private val spanGz = Base64.decode(
        "H4sIAAAAAAACA/MNdnZjAIK3DBAQB6WZGZkZQNBEiJGhILGoxFAvOTGJwSWzOFvBECxgjBAwZtgO1MEIxCeh9F0ozQU17e//vPBVGxUY0oryc3ULilLLMvNL" +
        "i/WSMvMYeKAqGBkgKjLzijNTUvVKKkoYRKBS/6CaS/J181IrSsDaiiq4GECwAg5SirVteIDGQQxQKAGqVORggMBKKAAA4F1trO0AAAA=",
    )

    private val lzxGz = Base64.decode(
        "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",
    )

    private fun cab(gz: ByteArray) = Gzip.decompress(gz, ArchiveLimits.UNBOUNDED)

    private fun reader(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT) = CabReader(ByteArrayRandomAccessSource(bytes), limits)

    @Test
    fun sniffsAndOpens() {
        for (gz in listOf(storedGz, mszipGz, multiGz, spanGz, lzxGz)) {
            val bytes = cab(gz)
            assertEquals(ArchiveFormat.CAB, Magic.sniff(bytes))
            assertEquals(ArchiveFormat.CAB, Magic.sniff(ByteArrayRandomAccessSource(bytes)))
            ArchiveKodec.open(bytes).use { assertEquals(ArchiveFormat.CAB, it.format) }
        }
    }

    @Test
    fun gcabStoredAndMszipFolders() {
        for ((gz, codec) in listOf(storedGz to CodecId.STORE, mszipGz to CodecId.DEFLATE)) {
            val reader = reader(cab(gz))
            reader.use {
                val entries = it.entries()
                assertEquals(listOf("hello.txt", "sub/notes.txt", "big.bin"), entries.map { e -> e.name })
                assertEquals(listOf(16L, 1500L, 40000L), entries.map { e -> e.size })
                for (entry in entries) {
                    assertEquals(1714979290L, entry.lastModifiedEpochSeconds)
                    assertEquals(codec, entry.codec)
                }
                assertContentEquals("Hello, cabinet!\n".encodeToByteArray(), it.read(entries[0]))
                assertContentEquals("line\n".repeat(300).encodeToByteArray(), it.read(entries[1]))
                assertEquals(0x8856e78bL, Crc32.of(it.read(entries[2])))
                // Three files of one folder, one decode.
                assertEquals(1, reader.folderDecodes)
            }
        }
    }

    @Test
    fun foldersReservesHistoryAndNames() {
        val reader = reader(cab(multiGz))
        reader.use {
            val entries = it.entries().associateBy { e -> e.name }
            assertEquals(setOf("data/big.bin", "data/count.txt", "caf\u00e9/na\u00efve.txt", "empty.txt"), entries.keys)
            assertEquals(1700000000L, entries.getValue("empty.txt").lastModifiedEpochSeconds)
            assertEquals(0xe4212cedL, Crc32.of(it.read(entries.getValue("data/big.bin"))))
            assertContentEquals("stored folder bytes\n".repeat(10).encodeToByteArray(), it.read(entries.getValue("caf\u00e9/na\u00efve.txt")))
            val count = it.read(entries.getValue("data/count.txt"))
            assertEquals(0x8c55999fL, Crc32.of(count))
            assertTrue(count.decodeToString().startsWith("00000 the quick brown fox\n"))
            assertContentEquals(ByteArray(0), it.read(entries.getValue("empty.txt")))
            assertEquals(3, reader.folderDecodes)
            assertTrue(entries.getValue("data/big.bin").compressedSize < 9054)
        }
    }

    @Test
    fun cabinetSetMembers() {
        reader(cab(spanGz)).use {
            val entries = it.entries()
            assertEquals(listOf("from-previous.bin", "inside.txt", "to-next.bin"), entries.map { e -> e.name })
            assertContentEquals("inside text!".encodeToByteArray(), it.read(entries[1]))
            assertFailsWith<ArchiveUnsupportedException> { it.read(entries[0]) }
            assertFailsWith<ArchiveUnsupportedException> { it.read(entries[2]) }
        }
    }

    @Test
    fun lzxFolder() {
        val reader = reader(cab(lzxGz))
        reader.use {
            val entries = it.entries()
            assertEquals(listOf("code/first.bin", "code/rest.bin"), entries.map { e -> e.name })
            assertEquals(listOf(30000L, 23308L), entries.map { e -> e.size })
            assertEquals(CodecId("lzx"), entries[0].codec)
            assertEquals(0x1961b3fbL, Crc32.of(it.read(entries[1])))
            assertEquals(0xf4ce29b6L, Crc32.of(it.read(entries[0])))
            assertEquals(1, reader.folderDecodes)
        }
        // A fresh reader reads the first file alone, decoding only the frames it covers.
        reader(cab(lzxGz)).use { assertEquals(0xf4ce29b6L, Crc32.of(it.read(it.entries()[0]))) }
    }

    @Test
    fun damagedLzxDataFailsCleanly() {
        val original = cab(lzxGz)
        val random = kotlin.random.Random(33)
        repeat(300) {
            val bytes = original.copyOf()
            repeat(1 + random.nextInt(3)) { bytes[100 + random.nextInt(bytes.size - 100)] = random.nextInt(256).toByte() }
            val damaged = if (random.nextInt(6) == 0) bytes.copyOf(100 + random.nextInt(bytes.size - 100)) else bytes
            try {
                reader(damaged).use { r -> for (entry in r.entries()) r.read(entry) }
            } catch (_: ArchiveException) {
                // Damage must surface as an archive error, never another exception or a hang.
            }
        }
    }

    @Test
    fun unsupportedMethods() {
        val quantum = cab(storedGz).also { it[42] = 2; it[43] = 15 }
        reader(quantum).use {
            val error = assertFailsWith<ArchiveUnsupportedException> { it.read(it.entries().last()) }
            assertTrue("Quantum" in error.message!!, error.message)
        }
        val unknown = cab(storedGz).also { it[42] = 7 }
        assertFailsWith<ArchiveUnsupportedException> { reader(unknown).entries() }
    }

    @Test
    fun malformedCabinetsAndLimits() {
        val multi = cab(multiGz)
        // A flipped byte in the first MSZIP block fails its checksum.
        val corrupt = multi.copyOf().also { it[readU32Le(it, 60).toInt() + 8 + 8 + 10] = 0x55 }
        reader(corrupt).use { assertFailsWith<ArchiveFormatException> { it.read(it.entries().first()) } }

        val mszip = cab(mszipGz)
        val noSignature = mszip.copyOf().also { it[readU32Le(it, 36).toInt() + 8] = 'X'.code.toByte() }
        reader(noSignature).use { assertFailsWith<ArchiveFormatException> { it.read(it.entries().first()) } }

        val badFolder = mszip.copyOf().also { it[readU32Le(it, 16).toInt() + 8] = 5 }
        assertFailsWith<ArchiveFormatException> { reader(badFolder).entries() }
        assertFailsWith<ArchiveFormatException> { reader(mszip.copyOf(mszip.size - 100)).entries() }
        assertFailsWith<ArchiveFormatException> { reader(mszip.copyOf(30)).entries() }

        assertFailsWith<DecompressionBombException> { reader(mszip, ArchiveLimits { maxEntries = 2 }).entries() }
        reader(mszip, ArchiveLimits { maxUncompressedBytes = 10_000 }).use {
            assertFailsWith<DecompressionBombException> { it.read(it.entries().last()) }
        }
    }
}
