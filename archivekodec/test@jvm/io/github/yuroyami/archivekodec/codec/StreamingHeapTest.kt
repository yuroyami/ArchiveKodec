package io.github.yuroyami.archivekodec.codec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #24's check: a gzip stream whose output (1 GiB of zeros) is far larger than the heap
 * decompresses into a counting sink in a child JVM limited to 48 MiB.
 */
class StreamingHeapTest {
    @Test
    fun decompressesOutputTwentyTimesTheHeap() {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed, 1 shl 16).use { out -> val chunk = ByteArray(1 shl 20); repeat(1024) { out.write(chunk) } }
        val input = File.createTempFile("archivekodec-bomb", ".gz")
        try {
            input.writeBytes(compressed.toByteArray())
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(java, "-Xmx48m", "-cp", System.getProperty("java.class.path"), StreamingHeapMain::class.java.name, input.path)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            assertEquals("1073741824 bytes, all zero", output.trim())
        } finally {
            input.delete()
        }
    }
}

/** Runs in the child JVM: stream the gzip file's output into a counting sink. */
object StreamingHeapMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var total = 0L
        var nonZero = false
        val written = Codecs.GZIP.decompress(File(args[0]).readBytes(), ByteSink { bytes, offset, length ->
            total += length
            for (i in offset until offset + length) if (bytes[i].toInt() != 0) nonZero = true
        }, io.github.yuroyami.archivekodec.security.ArchiveLimits.DEFAULT)
        check(written == total)
        println("$total bytes, ${if (nonZero) "some nonzero" else "all zero"}")
    }
}
