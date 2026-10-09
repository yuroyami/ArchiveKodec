package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.deflate.DeflateStrategy
import io.github.yuroyami.archivekodec.wrapper.Zlib
import java.util.Random
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Every level and strategy decodes with the JDK's zlib, and a preset dictionary is read the zlib way (#86). */
class EncoderOptionsJvmTest {
    private fun text(size: Int, seed: Long): ByteArray {
        val words = listOf("the", "archive", "stream", "block", "huffman", "window", "match", "literal", "kotlin", "deflate",
            "compression", "entropy", "distance", "length", "symbol", "table")
        val random = Random(seed)
        val out = StringBuilder(size + 16)
        while (out.length < size) out.append(words[random.nextInt(words.size)]).append(if (random.nextInt(12) == 0) '\n' else ' ')
        return out.substring(0, size).toByteArray()
    }

    private fun inflate(stream: ByteArray, size: Int, nowrap: Boolean = true, dictionary: ByteArray? = null): ByteArray {
        val inflater = Inflater(nowrap)
        inflater.setInput(stream)
        val out = ByteArray(size)
        var n = 0
        while (n < size) {
            val read = inflater.inflate(out, n, size - n)
            if (read == 0 && inflater.needsDictionary()) inflater.setDictionary(dictionary!!)
            n += read
        }
        inflater.end()
        return out
    }

    @Test
    fun everySettingDecodesWithTheJdk() {
        val random = ByteArray(100_000).also { Random(3).nextBytes(it) }
        val data = text(300_000, 1) + random + ByteArray(50_000) + text(200_000, 2)
        for (level in 0..9) for (strategy in DeflateStrategy.entries) for (windowBits in listOf(9, 12, 15)) {
            val options = DeflateOptions { this.level = level; this.strategy = strategy; this.windowBits = windowBits }
            assertContentEquals(data, inflate(Deflate.encode(data, options), data.size), "$options")
        }
    }

    @Test
    fun presetDictionaryDecodesWithTheJdk() {
        val dictionary = text(20_000, 4)
        val data = text(5_000, 4)
        val stream = Zlib.compress(data, DeflateOptions { this.dictionary = dictionary; level = 9 })
        assertContentEquals(data, inflate(stream, data.size, nowrap = false, dictionary = dictionary))
    }

    @Test
    fun levelOneIsFasterAndLargerThanLevelNine() {
        val data = text(4 shl 20, 7)
        fun best(level: Int): Pair<Long, Int> {
            var time = Long.MAX_VALUE
            var size = 0
            repeat(3) {
                val start = System.nanoTime()
                size = Deflate.encode(data, DeflateOptions { this.level = level }).size
                time = minOf(time, System.nanoTime() - start)
            }
            return time to size
        }
        val (fastTime, fastSize) = best(1)
        val (slowTime, slowSize) = best(9)
        assertTrue(fastSize > slowSize, "level 1 $fastSize vs level 9 $slowSize")
        assertTrue(fastTime < slowTime, "level 1 ${fastTime / 1_000_000} ms vs level 9 ${slowTime / 1_000_000} ms")
    }
}
