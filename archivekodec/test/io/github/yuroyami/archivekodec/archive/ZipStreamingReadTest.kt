package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ZIP members read into a sink stream, with their size and CRC-32 checked at the end. */
class ZipStreamingReadTest {
    private class Collector : ByteSink {
        val writes = ArrayList<Int>()
        private val out = ArrayList<Byte>()
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writes += length
            for (i in offset until offset + length) out += bytes[i]
        }
        fun bytes() = out.toByteArray()
    }

    private val text = ByteArray(2_600_000) { "zip streaming\n"[it % 14].code.toByte() }
    private val random = Random(8).nextBytes(2_300_000)
    private val zip = ZipWriter.write(listOf(
        ZipWriter.FileSpec("stored.bin", random) { method = ZipMethod.STORED },
        ZipWriter.FileSpec("deflated.txt", text),
        ZipWriter.FileSpec("empty", ByteArray(0)),
    ))

    @Test
    fun streamedMembersMatchBufferedReads() {
        ArchiveKodec.open(zip).use { reader ->
            for (entry in reader.entries()) {
                val sink = Collector()
                assertEquals(entry.size, reader.read(entry, sink))
                assertContentEquals(reader.read(entry), sink.bytes(), entry.name)
                if (entry.size > 2_000_000) assertTrue(sink.writes.size >= 2, "${entry.name} in ${sink.writes}")
                assertTrue(sink.writes.all { it <= (1 shl 20) + 65536 }, "${entry.name} chunks ${sink.writes}")
            }
        }
    }

    @Test
    fun damageAndLimitsAreCaught() {
        val names = ArchiveKodec.open(zip).use { r -> r.entries().associate { it.name to it.dataOffset } }
        for (name in listOf("stored.bin", "deflated.txt")) {
            // A changed payload byte fails the CRC-32 once the member is written.
            val damaged = zip.copyOf()
            val at = (names.getValue(name) + 30 + name.length + 5000).toInt()
            damaged[at] = (damaged[at] + 1).toByte()
            ArchiveKodec.open(damaged).use { reader ->
                assertFailsWith<ArchiveFormatException> { reader.read(reader.entries().single { it.name == name }, Collector()) }
            }
        }
        ArchiveKodec.open(zip).use { reader ->
            val entry = reader.entries().single { it.name == "deflated.txt" }
            val sink = Collector()
            assertFailsWith<DecompressionBombException> { reader.read(entry, sink, ArchiveLimits { maxUncompressedBytes = 1_000_000 }) }
            assertTrue(sink.writes.sum() <= 1_000_000)
        }
    }
}
