package io.github.yuroyami.archivekodec.codec.deflate

import java.lang.management.ManagementFactory
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Block splitting and the window-sized chain (#74), measured against the JDK's zlib. */
class DeflateBlocksJvmTest {
    /** Words with spaces and newlines, like the issue's corpus. */
    private fun text(size: Int, seed: Long): ByteArray {
        val words = listOf("the", "archive", "stream", "block", "huffman", "window", "match", "literal", "kotlin", "deflate",
            "compression", "entropy", "distance", "length", "symbol", "table", "header", "zip", "gzip", "zlib")
        val random = Random(seed)
        val out = StringBuilder(size + 16)
        while (out.length < size) {
            out.append(words[random.nextInt(words.size)])
            out.append(if (random.nextInt(12) == 0) '\n' else ' ')
        }
        return out.substring(0, size).toByteArray()
    }

    private fun jdkSize(data: ByteArray): Int {
        val deflater = Deflater(6, true)
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (!deflater.finished()) total += deflater.deflate(buffer)
        deflater.end()
        return total
    }

    private fun jdkInflate(stream: ByteArray, size: Int): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(stream)
        val out = ByteArray(size)
        var n = 0
        while (n < size) n += inflater.inflate(out, n, size - n)
        inflater.end()
        return out
    }

    @Test
    fun mixedInputGetsBlocksOfItsOwnAndStaysCloseToZlib() {
        val random = ByteArray(4 shl 20).also { Random(7).nextBytes(it) }
        val mixed = text(4 shl 20, 1) + random + text(4 shl 20, 2)
        val ours = Deflate.encode(mixed)
        assertContentEquals(mixed, jdkInflate(ours, mixed.size))
        assertContentEquals(mixed, Inflate.inflate(ours, io.github.yuroyami.archivekodec.security.ArchiveLimits.UNBOUNDED))
        val jdk = jdkSize(mixed)
        // One block over the whole input was 8.5% larger than zlib level 6; stored blocks
        // for the random part and fresh codes per block close most of that gap.
        assertTrue(ours.size < jdk * 1.04, "ours ${ours.size} vs zlib ${jdk}")
        // Random data costs at most a few bytes per 64 KiB stored block.
        val randomOnly = Deflate.encode(random)
        assertTrue(randomOnly.size < random.size + random.size / 1000, "random ${randomOnly.size}")
    }

    @Test
    fun workingMemoryDoesNotGrowWithTheInput() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        Deflate.encode(text(1 shl 20, 3)) // warm up class loading
        fun allocated(data: ByteArray): Long {
            val before = threads.getThreadAllocatedBytes(id)
            Deflate.encode(data)
            return threads.getThreadAllocatedBytes(id) - before
        }
        // Beyond fixed-size chain and block buffers, only the growing output is allocated.
        // The single-block encoder allocated 6.7 times the input for text.
        val text = text(16 shl 20, 1)
        val textBytes = allocated(text)
        assertTrue(textBytes < text.size, "allocated $textBytes for ${text.size} bytes of text")
        val random = ByteArray(16 shl 20).also { Random(9).nextBytes(it) }
        val randomBytes = allocated(random)
        assertTrue(randomBytes < 4L * random.size, "allocated $randomBytes for ${random.size} random bytes")
    }
}
