package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.Zlib
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-world data (#41): the Canterbury Corpus through every encoder, and the DEFLATE
 * family against `java.util.zip` both ways. Set ARCHIVEKODEC_SILESIA to a directory of
 * Silesia Corpus files to run the same checks over them (see README, "Corpus tests").
 */
class CorpusRoundTripTest {
    private val corpus: List<Pair<String, ByteArray>> by lazy {
        val dir = javaClass.classLoader.getResource("canterbury/SHA256SUMS")!!.let { File(it.toURI()).parentFile }
        File(dir, "SHA256SUMS").readLines().filter { it.isNotBlank() }.map { line ->
            val (hash, name) = line.split(Regex("\\s+"), limit = 2)
            val bytes = File(dir, name.trim()).readBytes()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(hash, actual, "corpus file $name")
            name.trim() to bytes
        }
    }

    private val unbounded = ArchiveLimits.UNBOUNDED

    @OptIn(ExperimentalArchiveImplementation::class)
    private fun roundTrips(files: List<Pair<String, ByteArray>>) {
        for (id in Codecs.implemented) {
            val codec = Codecs.byId(id)!!
            for ((name, data) in files) {
                val encoded = codec.compress(data)
                assertContentEquals(data, codec.decompress(encoded, unbounded), "$id $name")
                // Raw Snappy and STORE do not try to beat their input on every file.
                if (id != CodecId.STORE && id != CodecId.SNAPPY) {
                    assertTrue(encoded.size < data.size, "$id did not compress $name: ${encoded.size} >= ${data.size}")
                }
            }
        }
    }

    private fun jdkInflate(stream: ByteArray, size: Int, nowrap: Boolean): ByteArray {
        val inflater = Inflater(nowrap)
        inflater.setInput(stream)
        val out = ByteArray(size)
        var n = 0
        while (n < size) n += inflater.inflate(out, n, size - n)
        check(inflater.finished())
        inflater.end()
        return out
    }

    private fun jdkDeflate(data: ByteArray, level: Int, nowrap: Boolean): ByteArray {
        val deflater = Deflater(level, nowrap)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun againstJdk(files: List<Pair<String, ByteArray>>) {
        for ((name, data) in files) {
            for (level in listOf(1, 6, 9)) {
                val ours = Deflate.encode(data, DeflateOptions { this.level = level })
                assertContentEquals(data, jdkInflate(ours, data.size, nowrap = true), "our level $level $name")
                val theirs = jdkDeflate(data, level, nowrap = true)
                assertContentEquals(data, Inflate.inflate(theirs, unbounded), "zlib level $level $name")
                // Same algorithm and limits as zlib's, so within half a percent of its size.
                assertTrue(ours.size <= theirs.size * 1.005 + 16, "level $level $name: ${ours.size} vs zlib ${theirs.size}")
            }
            assertContentEquals(data, jdkInflate(Zlib.compress(data), data.size, nowrap = false), "zlib framing $name")
            assertContentEquals(data, Zlib.decompress(jdkDeflate(data, 6, nowrap = false), unbounded), "JDK zlib $name")
            assertContentEquals(data, GZIPInputStream(ByteArrayInputStream(Gzip.compress(data))).readBytes(), "gzip $name")
            val gzip = ByteArrayOutputStream().also { GZIPOutputStream(it).use { out -> out.write(data) } }.toByteArray()
            assertContentEquals(data, Gzip.decompress(gzip, unbounded), "JDK gzip $name")
        }
    }

    @Test
    fun canterburyRoundTripsThroughEveryEncoder() {
        assertEquals(11, corpus.size)
        assertEquals(2_810_784, corpus.sumOf { it.second.size })
        roundTrips(corpus)
    }

    @Test
    fun canterburyDeflateMatchesTheJdk() = againstJdk(corpus)

    @Test
    fun silesiaWhenProvided() {
        val dir = System.getenv("ARCHIVEKODEC_SILESIA")?.let(::File) ?: return
        val files = dir.listFiles()!!.filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }.map { it.name to it.readBytes() }
        assertTrue(files.isNotEmpty(), "no files in $dir")
        againstJdk(files)
        roundTrips(files)
    }
}
