package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipStreamWriter
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.StreamParity
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ZipStreamWriter's archives read by the JDK, and an entry past 4 GiB read by ZipReader. */
class ZipStreamInteropTest {
    private val files = linkedMapOf(
        "a.txt" to StreamParity.text(3_000_000, 1), // more than one encoder chunk
        "empty" to ByteArray(0),
        "dir/é雪.txt" to ByteArray(256) { it.toByte() },
        "b.txt" to "the quick brown fox jumps over the lazy dog ".repeat(20).encodeToByteArray(),
    )

    private fun streamed(method: ZipMethod): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ZipStreamWriter(ByteSink { b, o, n -> out.write(b, o, n) })
        for ((name, data) in files) writer.putFile(name, StreamParity.trickle(data, 10_007), method, 1_700_000_000, 0x81A4, "c $name")
        writer.putDirectory("dir", 1_700_000_000)
        writer.finish("archive")
        return out.toByteArray()
    }

    @Test
    fun theJdkReadsADeflatedStreamedArchive() {
        val zip = streamed(ZipMethod.DEFLATED)
        val path = Files.createTempFile("archivekodec-stream", ".zip")
        try {
            Files.write(path, zip)
            ZipFile(path.toFile()).use { jdk ->
                assertEquals("archive", jdk.comment)
                assertEquals(files.keys.toList() + "dir/", jdk.entries().toList().map { it.name })
                for ((name, data) in files) {
                    val entry = jdk.getEntry(name)
                    assertEquals(data.size.toLong(), entry.size, name)
                    assertEquals(Crc32.of(data), entry.crc, name)
                    assertEquals("c $name", entry.comment, name)
                    assertEquals(1_700_000_000_000L, entry.lastModifiedTime.toMillis(), name)
                    assertContentEquals(data, jdk.getInputStream(entry).readBytes(), name)
                }
                assertTrue(jdk.getEntry("dir/").isDirectory)
            }
        } finally {
            Files.delete(path)
        }
        ZipInputStream(ByteArrayInputStream(zip)).use { jdk ->
            val seen = ArrayList<String>()
            while (true) {
                val entry = jdk.nextEntry ?: break
                seen += entry.name
                assertContentEquals(files[entry.name] ?: ByteArray(0), jdk.readBytes(), entry.name)
            }
            assertEquals(files.keys.toList() + "dir/", seen)
        }
    }

    @Test
    fun storedEntriesReadThroughTheCentralDirectoryButNotForwardOnly() {
        val zip = streamed(ZipMethod.STORED)
        val path = Files.createTempFile("archivekodec-stream", ".zip")
        try {
            Files.write(path, zip)
            ZipFile(path.toFile()).use { jdk ->
                for ((name, data) in files) assertContentEquals(data, jdk.getInputStream(jdk.getEntry(name)).readBytes(), name)
            }
        } finally {
            Files.delete(path)
        }
        // As the KDoc says: ZipInputStream cannot find where stored data with a descriptor ends.
        assertFailsWith<ZipException> { ZipInputStream(ByteArrayInputStream(zip)).use { it.nextEntry } }
    }

    /** Keeps the first [HEAD] bytes written and the last [TAIL], and counts the rest. */
    private class HeadAndTail : ByteSink {
        val head = ByteArray(HEAD)
        val tail = ByteArray(TAIL)
        var size = 0L

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (size < HEAD) {
                val n = minOf(length.toLong(), HEAD - size).toInt()
                bytes.copyInto(head, size.toInt(), offset, offset + n)
            }
            // The tail is a ring indexed by position.
            var i = maxOf(0, length - TAIL)
            while (i < length) {
                val at = ((size + i) % TAIL).toInt()
                val n = minOf(length - i, TAIL - at)
                bytes.copyInto(tail, at, offset + i, offset + i + n)
                i += n
            }
            size += length
        }

        /** The archive, with zeros between the head and the tail: the stored entry's data. */
        fun source(): RandomAccessSource = object : RandomAccessSource {
            override val size: Long = this@HeadAndTail.size
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (length == 0) return 0
                if (offset >= size) return -1
                val tailStart = size - TAIL
                val n = when {
                    offset < HEAD -> minOf(length.toLong(), HEAD - offset).toInt().also { head.copyInto(into, intoOffset, offset.toInt(), offset.toInt() + it) }
                    offset >= tailStart -> {
                        val count = minOf(length.toLong(), size - offset).toInt()
                        for (k in 0 until count) into[intoOffset + k] = tail[((offset + k) % TAIL).toInt()]
                        count
                    }
                    else -> minOf(length.toLong(), tailStart - offset).toInt().also { into.fill(0, intoOffset, intoOffset + it) }
                }
                return n
            }
            override fun close() {}
        }

        companion object {
            const val HEAD = 1 shl 16
            const val TAIL = 1 shl 16
        }
    }

    @Test
    fun anEntryPastFourGibibytesUsesZip64AndReadsBack() {
        val big = (4L shl 30) + 12_345
        val sink = HeadAndTail()
        val writer = ZipStreamWriter(sink)
        writer.putFile("before.txt", ByteSource.of("before".encodeToByteArray()), ZipMethod.STORED)
        var left = big
        writer.putFile("zeros.bin", ByteSource { into, offset, length ->
            if (left == 0L) -1 else minOf(length.toLong(), left).toInt().also { into.fill(0, offset, offset + it); left -= it }
        }, ZipMethod.STORED)
        writer.putFile("after.txt", ByteSource.of("after".encodeToByteArray()), comment = "past 4 GiB")
        writer.putDirectory("last")
        writer.finish()
        // The big entry's descriptor has 8-byte sizes: signature, CRC-32, then two 64-bit sizes.
        val zeroCrc = Crc32().apply { val z = ByteArray(1 shl 20); var n = big; while (n > 0) { val k = minOf(n, z.size.toLong()).toInt(); update(z, 0, k); n -= k } }.value()
        val tail = sink.source()
        ZipReader(tail, ArchiveLimits { maxUncompressedBytes = Long.MAX_VALUE }).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("before.txt", "zeros.bin", "after.txt", "last/"), entries.map { it.name })
            val zeros = entries[1]
            assertEquals(big, zeros.size)
            assertEquals(big, zeros.compressedSize)
            assertEquals(zeroCrc, zeros.crc32)
            assertTrue(entries[2].dataOffset > 0xFFFFFFFFL, "the next local header is past 4 GiB")
            assertEquals("past 4 GiB", entries[2].comment)
            assertEquals("after", reader.read(entries[2]).decodeToString())
            val crc = Crc32()
            var total = 0L
            reader.read(zeros, ByteSink { b, o, n -> crc.update(b, o, n); total += n })
            assertEquals(big, total)
            assertEquals(zeroCrc, crc.value())
        }
        // The descriptor after the big entry, found from the tail: 24 bytes before after.txt's header.
        val bytes = ByteArray(64)
        val afterHeader = 30 + 10 + 6 + 16 + 30 + 9 + big + 24
        tail.read(afterHeader - 24, bytes, 0, 28)
        assertEquals(0x08074b50L, readU32Le(bytes, 0))
        assertEquals(zeroCrc, readU32Le(bytes, 4))
        assertEquals(big, java.nio.ByteBuffer.wrap(bytes, 8, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(0x04034b50L, readU32Le(bytes, 24))
        // The ZIP64 end records are written, as the central directory starts past 4 GiB.
        tail.read(sink.size - 22 - 20, bytes, 0, 42)
        assertEquals(0x07064b50L, readU32Le(bytes, 0))
        assertEquals(0x06054b50L, readU32Le(bytes, 20))
        assertEquals(0xFFFFFFFFL, readU32Le(bytes, 20 + 16))
        assertEquals(4, readU16Le(bytes, 20 + 10))
    }
}
