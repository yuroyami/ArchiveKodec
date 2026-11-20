package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipStreamWriter
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
    fun aTarGzLargerThanTheHeapIsWrittenAndReadInThirtyTwoMegabytes() = run("gzip", 3, 32L shl 20)

    @Test
    fun aTarBz2LargerThanTheHeapIsWrittenAndReadInThirtyTwoMegabytes() = run("bzip2", 2, 20L shl 20)

    @Test
    fun aTarXzLargerThanTheHeapIsWrittenAndReadInThirtyTwoMegabytes() = run("xz", 2, 20L shl 20)

    /** A ZIP entry whose compressed data alone is larger than the heap streams through it. */
    @Test
    fun aZipEntryLargerThanTheHeapIsReadInThirtyTwoMegabytes() = run("zip", 1, 112L shl 20)

    /** ZipStreamWriter writes DEFLATE entries each larger than the heap; ZipReader reads them back. */
    @Test
    fun aZipLargerThanTheHeapIsWrittenAndReadInThirtyTwoMegabytes() = run("zipwrite", 2, 40L shl 20)

    private fun run(codec: String, entries: Int, entrySize: Long) {
        val dir = kotlin.io.path.createTempDirectory("archivekodec-small-heap").toFile()
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(java, "-Xmx32m", "-cp", System.getProperty("java.class.path"),
                SmallHeapWorkflow::class.java.name, dir.path, codec, entries.toString(), entrySize.toString())
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
        if (args[1] == "zip") return zip(File(args[0], "big.zip"), args[3].toLong())
        if (args[1] == "zipwrite") return zipWrite(File(args[0], "big.zip"), args[2].toInt(), args[3].toLong())
        val codec = Codecs.byName(args[1])!!
        val ENTRIES = args[2].toInt()
        val ENTRY_SIZE = args[3].toLong()
        val file = File(args[0], "big.tar.${args[1]}")
        val written = LongArray(ENTRIES)
        FileOutputStream(file).buffered().use { out ->
            val gzip = codec.encoder(ByteSink { b, o, n -> out.write(b, o, n) })
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
            codec.decompress(ByteSource { b, o, n -> input.read(b, o, n) }, sink, ArchiveLimits.UNBOUNDED)
        }
        sink.finish()
        read += crc.value(); sizes += size
        check(read == written.toList()) { "CRC-32s differ: wrote ${written.toList()}, read $read" }
        check(sizes.all { it == ENTRY_SIZE }) { "sizes $sizes" }
        val heap = Runtime.getRuntime().maxMemory()
        println("OK ${args[1]}: ${ENTRIES * ENTRY_SIZE} bytes through a ${heap shr 20} MiB heap, archive ${file.length()} bytes")
    }

    /** Write one DEFLATE entry with java.util.zip, then read it through ZipReader from the file. */
    private fun zip(file: File, size: Long) {
        val written = Crc32()
        java.util.zip.ZipOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.setLevel(1)
            out.putNextEntry(java.util.zip.ZipEntry("big.bin"))
            val source = generator(7, size, written)
            val chunk = ByteArray(1 shl 16)
            while (true) {
                val n = source.read(chunk, 0, chunk.size)
                if (n < 0) break
                out.write(chunk, 0, n)
            }
            out.closeEntry()
        }
        val read = Crc32()
        var total = 0L
        val reader = ZipReader(fileSource(file))
        try {
            val entry = reader.entries().single()
            check(entry.compressedSize > Runtime.getRuntime().maxMemory()) { "compressed ${entry.compressedSize} fits the heap" }
            reader.read(entry, ByteSink { b, o, n -> read.update(b, o, n); total += n })
        } finally {
            reader.close()
        }
        check(read.value() == written.value() && total == size) { "CRC-32 or size differs: $total bytes" }
        println("OK zip: $size bytes from ${file.length()} compressed through a ${Runtime.getRuntime().maxMemory() shr 20} MiB heap")
    }

    /** Write [entries] DEFLATE entries of [size] bytes with ZipStreamWriter, then read each back through ZipReader. */
    private fun zipWrite(file: File, entries: Int, size: Long) {
        val written = LongArray(entries)
        FileOutputStream(file).buffered().use { out ->
            val writer = ZipStreamWriter(ByteSink { b, o, n -> out.write(b, o, n) })
            for (i in 0 until entries) {
                val crc = Crc32()
                writer.putFile("entry-$i.bin", generator(i + 1, size, crc))
                written[i] = crc.value()
            }
            writer.finish()
        }
        val reader = ZipReader(fileSource(file))
        try {
            val listed = reader.entries()
            check(listed.map { it.name } == List(entries) { "entry-$it.bin" }) { "names ${listed.map { it.name }}" }
            for ((i, entry) in listed.withIndex()) {
                check(entry.size == size && entry.crc32 == written[i]) { "entry $i lists ${entry.size} bytes, CRC-32 ${entry.crc32}" }
                val crc = Crc32()
                var total = 0L
                reader.read(entry, ByteSink { b, o, n -> crc.update(b, o, n); total += n })
                check(crc.value() == written[i] && total == size) { "entry $i reads $total bytes, CRC-32 ${crc.value()}" }
            }
        } finally {
            reader.close()
        }
        val heap = Runtime.getRuntime().maxMemory()
        check(entries * size > heap) { "${entries * size} bytes fit the $heap-byte heap" }
        println("OK zipwrite: ${entries * size} bytes in ${file.length()} through a ${heap shr 20} MiB heap")
    }

    /** A file read by offset. */
    private fun fileSource(file: File): RandomAccessSource {
        val raf = java.io.RandomAccessFile(file, "r")
        return object : RandomAccessSource {
            override val size: Long = raf.length()
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (length == 0) return 0
                raf.seek(offset)
                return raf.read(into, intoOffset, length)
            }
            override fun close() = raf.close()
        }
    }
}
