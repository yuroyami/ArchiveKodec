package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.codec.StreamParity
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ZIP members compressed with bzip2 (12), zstd (93) and xz (95) stream through
 * `read(entry, sink)`, reading their input from the archive as they decode, and give the same
 * bytes and the same failures as `read(entry)`.
 */
class ZipStreamedMethodsTest {
    private val methods = listOf<Pair<Int, Codec>>(12 to Codecs.BZIP2, 93 to Codecs.ZSTD, 95 to Codecs.XZ)

    /** One member with [method], [compressed] data and the declared [size] and [crc]. */
    private fun zip(method: Int, compressed: ByteArray, size: Long, crc: Long): ByteArray {
        val out = ByteArrayBuilder(compressed.size + 200)
        fun u16(v: Int) { out.append(v.toByte()); out.append((v ushr 8).toByte()) }
        fun u32(v: Long) { u16(v.toInt() and 0xFFFF); u16((v ushr 16).toInt() and 0xFFFF) }
        val name = "member.bin".encodeToByteArray()
        fun common() { u16(63); u16(0); u16(method); u16(0); u16(0x21); u32(crc); u32(compressed.size.toLong()); u32(size); u16(name.size); u16(0) }
        u32(0x04034b50); common(); out.append(name); out.append(compressed)
        val directory = out.size.toLong()
        u32(0x02014b50); u16(63); common(); u16(0); u16(0); u16(0); u32(0); u32(0); out.append(name)
        val directorySize = out.size - directory
        u32(0x06054b50); u16(0); u16(0); u16(1); u16(1); u32(directorySize); u32(directory); u16(0)
        return out.takeByteArray()
    }

    private fun outcome(zip: ByteArray, streamed: Boolean, limits: ArchiveLimits = ArchiveLimits.DEFAULT): String = try {
        ZipReader(ByteArrayRandomAccessSource(zip)).use { reader ->
            val entry = reader.entries().single()
            if (streamed) {
                val out = ByteArrayBuilder(1024)
                val count = reader.read(entry, { b, o, n -> out.append(b, o, n) }, limits)
                assertEquals(out.size.toLong(), count)
                out.takeByteArray().decodeToString()
            } else {
                reader.read(entry, limits).decodeToString()
            }
        }
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun assertSame(zip: ByteArray, what: String, limits: ArchiveLimits = ArchiveLimits.DEFAULT) =
        assertEquals(outcome(zip, false, limits), outcome(zip, true, limits), what)

    @Test
    fun membersStreamWithTheSameBytesAndFailures() {
        val data = StreamParity.text(300_000, 11)
        val crc = Crc32.of(data)
        for ((method, codec) in methods) {
            val compressed = codec.compress(data)
            val good = zip(method, compressed, data.size.toLong(), crc)
            assertEquals(data.decodeToString(), outcome(good, true), "$method")
            assertSame(good, "$method")
            assertSame(zip(method, compressed, data.size - 1L, crc), "$method declared one byte short")
            assertSame(zip(method, compressed, data.size + 1L, crc), "$method declared one byte long")
            assertSame(zip(method, compressed, data.size.toLong(), crc xor 1), "$method CRC")
            assertSame(zip(method, compressed + byteArrayOf(0x55), data.size.toLong(), crc), "$method trailing byte")
            assertSame(zip(method, compressed.copyOf(compressed.size - 3), data.size.toLong(), crc), "$method truncated")
            for (at in listOf(20, compressed.size / 2, compressed.size - 2)) {
                val bad = compressed.copyOf().also { it[at] = (it[at].toInt() xor 0x41).toByte() }
                assertSame(zip(method, bad, data.size.toLong(), crc), "$method byte $at")
            }
            assertSame(good, "$method size limit", ArchiveLimits { maxUncompressedBytes = 100_000 })
            assertSame(good, "$method ratio limit", ArchiveLimits { maxCompressionRatio = 2 })
        }
    }
}
