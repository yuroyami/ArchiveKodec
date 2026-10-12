package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.use
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #23's check: a ZIP far larger than the heap lists and reads through
 * [FileRandomAccessSource]. The archive (four 40 MB stored members of zeros and a small
 * last member, 160 MB in all) is written straight to disk, then listed by a child JVM
 * limited to a 32 MB heap, so holding the archive in memory would fail.
 */
class LargeFileTest {
    @Test
    fun listsAndReadsAnArchiveFiveTimesTheHeap() {
        val file = File.createTempFile("archivekodec-large", ".zip")
        try {
            writeLargeZip(file)
            assertTrue(file.length() > 160_000_000L)
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(
                java, "-Xmx32m", "-cp", System.getProperty("java.class.path"),
                LargeFileMain::class.java.name, file.path,
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            assertEquals("5 entries; tail.txt=last member", output.trim())
        } finally {
            file.delete()
        }
    }

    private fun writeLargeZip(file: File) {
        val chunk = ByteArray(1 shl 20)
        val members = (0 until 4).map { "zeros-$it.bin" to 40L * chunk.size } + ("tail.txt" to 11L)
        val tail = "last member".encodeToByteArray()
        val zeroCrc = CRC32().apply { repeat(40) { update(chunk) } }.value
        val tailCrc = CRC32().apply { update(tail) }.value
        val central = ArrayList<ByteArray>()
        var offset = 0L
        BufferedOutputStream(FileOutputStream(file), 1 shl 20).use { out ->
            for ((name, size) in members) {
                val crc = if (name == "tail.txt") tailCrc else zeroCrc
                val nameBytes = name.encodeToByteArray()
                val local = ByteBuffer.allocate(30 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x04034b50).putShort(10).putShort(0).putShort(0).putInt(0)
                    .putInt(crc.toInt()).putInt(size.toInt()).putInt(size.toInt()).putShort(nameBytes.size.toShort()).putShort(0)
                    .put(nameBytes).array()
                out.write(local)
                if (name == "tail.txt") out.write(tail) else repeat(40) { out.write(chunk) }
                central += ByteBuffer.allocate(46 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x02014b50).putShort(10).putShort(10).putShort(0).putShort(0).putInt(0)
                    .putInt(crc.toInt()).putInt(size.toInt()).putInt(size.toInt()).putShort(nameBytes.size.toShort())
                    .putShort(0).putShort(0).putShort(0).putShort(0).putInt(0).putInt(offset.toInt()).put(nameBytes).array()
                offset += local.size + size
            }
            val centralSize = central.sumOf { it.size }
            central.forEach { out.write(it) }
            out.write(ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x06054b50).putShort(0).putShort(0).putShort(members.size.toShort()).putShort(members.size.toShort())
                .putInt(centralSize).putInt(offset.toInt()).putShort(0).array())
        }
    }
}

/** Runs in the child JVM: list the archive and read its small member. */
object LargeFileMain {
    @JvmStatic
    fun main(args: Array<String>) {
        ArchiveKodec.open(FileRandomAccessSource(args[0])).use { reader ->
            val entries = reader.entries()
            val tail = reader.read(entries.single { it.name == "tail.txt" }).decodeToString()
            println("${entries.size} entries; tail.txt=$tail")
        }
    }
}
