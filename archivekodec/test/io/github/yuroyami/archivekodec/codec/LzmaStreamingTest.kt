package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** The streaming lzip and `.lzma` decoders against the whole-array ones: same bytes, same failures. */
class LzmaStreamingTest {
    private fun data(size: Int, seed: Int): ByteArray {
        var x = seed
        val words = listOf("range ", "coder ", "lzip ", "member ", "window ").map { it.encodeToByteArray() }
        val out = ByteArrayBuilder(size)
        while (out.size < size) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            if (x and 7 == 0) out.append(byteArrayOf(x.toByte())) else out.append(words[(x ushr 1) % words.size])
        }
        return out.takeByteArray().copyOf(size)
    }

    private fun trickle(bytes: ByteArray, step: Int): ByteSource {
        var position = 0
        return ByteSource { into, offset, length ->
            if (position == bytes.size) -1 else {
                val n = minOf(length, step, bytes.size - position)
                bytes.copyInto(into, offset, position, position + n)
                position += n
                n
            }
        }
    }

    private fun arrayOutcome(codec: Codec, stream: ByteArray, limits: ArchiveLimits): String = try {
        codec.decompress(stream, limits).decodeToString()
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun streamOutcome(codec: Codec, stream: ByteArray, step: Int, limits: ArchiveLimits): String = try {
        val out = ByteArrayBuilder(1024)
        val count = codec.decompress(trickle(stream, step), { b, o, l -> out.append(b, o, l) }, limits)
        val bytes = out.takeByteArray()
        assertEquals(bytes.size.toLong(), count)
        bytes.decodeToString()
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun assertSame(codec: Codec, stream: ByteArray, what: String, limits: ArchiveLimits = ArchiveLimits.DEFAULT, steps: List<Int> = listOf(1, 7, 65_536)) {
        val expected = arrayOutcome(codec, stream, limits)
        for (step in steps) assertEquals(expected, streamOutcome(codec, stream, step, limits), "${codec.id} $what, step $step")
    }

    // xz 5.4.5: `printf 'marker stream %.0s' $(seq 1 30) | xz --format=lzma`, size unknown, with an end marker.
    private val marker = hex("5d00008000ffffffffffffffff0036984aab67db7fe343366f003283fe872341d30f0f37ffffed0c8000")

    @Test
    fun bothFormatsDecodeTheSame() {
        assertEquals("marker stream ".repeat(30), arrayOutcome(Codecs.LZMA, marker, ArchiveLimits.DEFAULT))
        assertSame(Codecs.LZMA, marker, "end marker")
        for (input in listOf(ByteArray(0), data(1, 2), data(200_000, 3))) {
            for (codec in listOf(Codecs.LZMA, Codecs.LZIP)) assertSame(codec, codec.compress(input), "${input.size} bytes", steps = listOf(7, 65_536))
        }
        val a = Codecs.LZIP.compress(data(70_000, 4))
        val b = Codecs.LZIP.compress(data(5000, 5))
        assertSame(Codecs.LZIP, a + b + a, "three members", steps = listOf(13, 65_536))
        assertSame(Codecs.LZMA, Codecs.LZMA.compress(data(5000, 6)) + byteArrayOf(0), "trailing byte")
        assertSame(Codecs.LZIP, b + byteArrayOf(1, 2), "trailing junk")
    }

    @Test
    fun truncationsCorruptionsAndLimitsFailTheSameWay() {
        val input = data(3000, 8)
        for (codec in listOf(Codecs.LZMA, Codecs.LZIP)) {
            val stream = codec.compress(input)
            for (cut in 0 until stream.size step 3) assertSame(codec, stream.copyOf(cut), "cut at $cut", steps = listOf(13))
            for (at in stream.indices step 2) {
                val bad = stream.copyOf().also { it[at] = (it[at].toInt() xor 0x41).toByte() }
                if (codec == Codecs.LZMA && at in 5..12) {
                    // A corrupted declared size: the array decoder knows the input's size and may
                    // refuse the ratio up front, where the streamed one fails later; both fail.
                    assertEquals(arrayOutcome(codec, bad, ArchiveLimits.DEFAULT).endsWith("Exception"),
                        streamOutcome(codec, bad, 13, ArchiveLimits.DEFAULT).endsWith("Exception"), "byte $at")
                } else {
                    assertSame(codec, bad, "byte $at", steps = listOf(13))
                }
            }
            val zeros = codec.compress(ByteArray(500_000))
            assertSame(codec, zeros, "size limit", ArchiveLimits { maxUncompressedBytes = 100_000 }, listOf(4096))
            assertSame(codec, zeros, "ratio limit", ArchiveLimits { maxCompressionRatio = 50 }, listOf(4096))
        }
        for (cut in marker.indices) assertSame(Codecs.LZMA, marker.copyOf(cut), "marker cut at $cut", steps = listOf(1))
    }

    // `seq 1 20000`, 108894 bytes, with 4 KiB dictionaries, so matches reach to the window's edge
    // while it slides: lzip 1.24.1 `lzip -s4KiB` and xz 5.4.5 `xz --format=lzma --lzma1=preset=6,dict=4KiB`.
    private val seqLz = Base64.decode(
        "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"
    )
    private val seqLzma = Base64.decode(
        "XQAQAAD//////////wAYgoKPIk74plX38JmlJQ2QRZFaUbSbyqzcBTLshVKfsUht79zoS7lhuuCcU3+YyKlUDvw9KtMG12ZE" +
        "PVZkps0918cfRPQY3wILP+LGxnvh5395RBx5t0KuZbgbtQ6E4ZqCBiM5Wn9yqkP6pZofksC+RXF5StWTxpAKOa2cZYcqtho4" +
        "x+GTF8rQPgnqsFLwEGAmwF5fEJENuXl4fZdCYNCmOHfhI+i7KE+IUi11WCY/Zv3inOvTr2HZNXr/+2KxPHyTHPJkuE0chSqx" +
        "XAAGNgFK67wFSO9caLU8cYGeLGzkZwbPhSp1CRmMvBdEDc08acY2xJ6c0u47NRI+k1KXnZHhTVYxXsrlloKvQAyFo0lWyJFq" +
        "QDX5FORVLVE1JFKEgBcgLVwDjMHoei11elyExOk3/ocae0XElADgGG2ftyfCj9jMK02AbKgjfca9OO9n1BHiMJjj/0MhEj1l" +
        "o5dhLKfmj5HnGqZLUyNjMFihcD6xkQNpT4o2mG1ErHJEoH7Wt6UG/+08923p3aL8H+x6DCdh8PXb3+Bmv8FQ+2BWZjKoeBjG" +
        "7oFCkcnUnkCSmSp+42fPA6MgwMOQQDnIzRaswKVvHlNMD9Z5SrCH11cjSJVlIU6JsoWELLwQsdl6dmMtrCZVnvmUUY2J8KCv" +
        "sQ601lHtANSxQ7PreLtC8b9XeXAPOnsnr4RGvJ6/AbX7iAx3FfKqxvMs5mb2HGBv02jvrM9pEBrneCDfaH/6vLbfzRjeCQM4" +
        "P1BAdHqpDqJTA6O/LpmSsfx0KkwBy0yK8TQjxgps4a8d8f8p39RTEjCPo8A5VWe4yGn2hI55fpi3vi+D0fj1gWaXGFnbhc5z" +
        "fOOUiOUcu/cgOq1AQ9vOTznT5VpL65eUOVgkC0d0+8QMSuqki1Z96jHZCe0taZbcPWx01JpUOiMWiNdE4b0AZqDVYopt97WU" +
        "be16fu5EaiUWXUUcHcpGJEdwFpPLMF7WBdllpcQk9qVRt4ttONayzAdsLKVNpYTRnOew9hsi9Xwjbz0Kt3mrUyYPnBtzkkqL" +
        "L2hvOu9l3vsxm5kc4BImMTTU6KYV6cVKnFP15HdHuZaW1sYdcw2Y94Y/BDm1F1k6fKb3JRJfr4WgGsg8k/xqnN3Uu5hwex55" +
        "8/jHlsEu4hrQMkNiBcPQJLFzOx82cdEPfBg5jG5a/kHiN1l/nHd/vVGfQIWUYa90VdCHdcCQKSd0KV1rx6EKCpFNoDE3Z5+v" +
        "bz3Dkhegq9p8T5tbBm+cb2HJFwmWEA3tiiDLhkcY7wGA+uuUXrsmwOgsllCvBumMk1b6fEimY3TrHAY/Ng1q5T0842cMiw3O" +
        "JoPQ6WoJdgqIRqJ0hK9D10qtD8vw5IMeuhSe7g40wreGbbtGWxZuNrsAY56RvZzM75u6Scr5GVPX6maALBT7jc24eUYZLYMk" +
        "KjjQmw+du6eieo4jcpboWoBbNR0UYry3uz3EhRFLXqzt6l3uqQzr929BCye7BvvisE36oUZrIb6VK/tcgOc4w5yhW1hYwAFV" +
        "wgIFftLzB9HAK3am8DzR/vF0m3SQJ7QKqDSkb+PaYD9PmmZ9sBO39qModA1WpL5zthOISy5m+ojr/OqIXprqgOcgNOp20y/T" +
        "kf+F7Y6pD6rxOjfmfcIlUetan+uOcMlHz90lMb9GrEIfA9R9m8ZYUobO+Knm1dr83PDjiKNTJNvLJefE+s4SY14v66e9VWLP" +
        "zycEbmEgDKnZdKuKLPqyLEupxgDnNpUiatb3pFfXQxzZy+eonqXdJIi8Bcni4A5ryAMxgT+XHhcSo4DY35Vfs58rAyZyjiBr" +
        "YC/6AxDEf+Qte1J4SvKaQP5r/IT8yr0cjtXSatJypdmIw8xgeQ3cYX54bSve7u7YrVOMaq2L6XQ23olIAiEicJwK1VUkdVoz" +
        "D/oTLtjxg0WSiG9UcJ+b6ZzTLE2A9rmfvf6Cga4sj0QQ125SQVbfWNOD95HMCd2pX19LQXCthyaLzSUf6HMgFf0GescFdAKT" +
        "PSfw5EOWEgNz/lp6NMkDk789K742Er/8DXJNyyItl5SKTHRssnVmcGIFB1zcGMIWMvQ050nmBJJ7FXk9TEFWITmYy4Yv8v/Y" +
        "YTISvIVUyzM7cKYyZnGPzY0Chr3yg/7lljIdN95J2r6qEY+dWwutLv0S2jz7aVuxNcHtPzvRlMgrSvXnsaHoxOgJD3Le3+Bx" +
        "S/P+/n7HFZuo6P0MV+zQqm/VFv8HttK67xNEmfYr36+Ji6TUrQq69QXJSNbUigEDOUJmffMbusWkpy3PjviE5mdI/HlEreNv" +
        "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"
    )

    @Test
    fun referenceStreamsWithSmallDictionariesSlideTheWindow() {
        val expected = (1..20000).joinToString("\n", postfix = "\n")
        for ((codec, stream) in listOf(Codecs.LZIP to seqLz, Codecs.LZMA to seqLzma)) {
            assertEquals(expected, arrayOutcome(codec, stream, ArchiveLimits.DEFAULT))
            assertSame(codec, stream, "seq", steps = listOf(1, 4096, 65_536))
        }
        // Each window holds the dictionary, not the output: well under a 64 KiB memory limit.
        val tight = ArchiveLimits { maxDecoderMemory = 64 * 1024 }
        assertEquals(expected, streamOutcome(Codecs.LZIP, seqLz, 4096, tight))
        assertEquals(expected, streamOutcome(Codecs.LZMA, seqLzma, 4096, tight))
        assertEquals("DecompressionBombException", streamOutcome(Codecs.LZIP, seqLz, 4096, ArchiveLimits { maxDecoderMemory = 2048 }))
    }
}
