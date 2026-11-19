package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.tar.TarStreamSink
import io.github.yuroyami.archivekodec.archive.tar.TarStreamVisitor
import io.github.yuroyami.archivekodec.archive.tar.TarStreamWriter
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first complete streaming workflow of #154 (Stage 2): a `.tar.gz` of more data than the
 * heap holds is written and read back in a JVM limited to 32 MiB, with every entry's CRC-32
 * checked. The work runs in a child process, so the limit is real.
 */
class SmallHeapWorkflowTest {
    @Test
    fun aTarGzLargerThanTheHeapIsWrittenAndReadInThirtyTwoMegabytes() {
        val dir = kotlin.io.path.createTempDirectory("archivekodec-small-heap").toFile()
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(java, "-Xmx32m", "-cp", System.getProperty("java.class.path"),
                SmallHeapWorkflow::class.java.name, dir.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            assertTrue(output.contains("OK"), output)
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** The child process: write the archive to a file, read it back, compare checksums. */
object SmallHeapWorkflow {
    private const val ENTRIES = 3
    private const val ENTRY_SIZE = 32L shl 20

    /** Deterministic, partly compressible bytes: words, then noise, in turns. */
    private fun generator(seed: Int, size: Long, crc: Crc32): ByteSource {
        var produced = 0L
        var x = seed
        return ByteSource { into, offset, length ->
            if (produced == size) -1 else {
                val n = minOf(length.toLong(), size - produced).toInt()
                for (i in 0 until n) {
                    x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
                    val p = produced + i
                    into[offset + i] = if ((p / 4096) % 3 == 0L) x.toByte() else ('a' + ((p / 7) % 26).toInt()).code.toByte()
                }
                crc.update(into, offset, n)
                produced += n
                n
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val file = File(args[0], "big.tar.gz")
        val written = LongArray(ENTRIES)
        FileOutputStream(file).buffered().use { out ->
            val gzip = Codecs.GZIP.encoder(ByteSink { b, o, n -> out.write(b, o, n) })
            val tar = TarStreamWriter(ByteSink { b, o, n -> gzip.write(b, o, n) })
            for (i in 0 until ENTRIES) {
                val crc = Crc32()
                tar.putFile("entry-$i.bin", ENTRY_SIZE, generator(i + 1, ENTRY_SIZE, crc))
                written[i] = crc.value()
            }
            tar.finish()
            gzip.finish()
        }
        val read = ArrayList<Long>()
        val sizes = ArrayList<Long>()
        var crc = Crc32()
        var size = 0L
        var started = false
        val sink = TarStreamSink(object : TarStreamVisitor {
            override fun onEntry(entry: ArchiveEntry) {
                if (started) { read += crc.value(); sizes += size }
                started = true
                crc = Crc32(); size = 0
            }
            override fun onData(entry: ArchiveEntry, bytes: ByteArray, offset: Int, length: Int) {
                crc.update(bytes, offset, length); size += length
            }
        })
        FileInputStream(file).buffered().use { input ->
            Codecs.GZIP.decompress(ByteSource { b, o, n -> input.read(b, o, n) }, sink, ArchiveLimits.UNBOUNDED)
        }
        sink.finish()
        read += crc.value(); sizes += size
        check(read == written.toList()) { "CRC-32s differ: wrote ${written.toList()}, read $read" }
        check(sizes.all { it == ENTRY_SIZE }) { "sizes $sizes" }
        val heap = Runtime.getRuntime().maxMemory()
        println("OK ${ENTRIES * ENTRY_SIZE} bytes through a ${heap shr 20} MiB heap, archive ${file.length()} bytes")
    }
}
