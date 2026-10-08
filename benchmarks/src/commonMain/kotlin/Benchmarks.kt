package io.github.yuroyami.archivekodec.benchmarks

import io.github.yuroyami.archivekodec.checksum.Adler32
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.checksum.Crc32c
import io.github.yuroyami.archivekodec.checksum.Crc64
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import kotlin.math.ceil

internal expect fun cpuNanos(): Double
internal expect fun consume(value: Any)
internal expect fun profile(): String
internal expect fun runtime(): String
internal expect fun referenceCases(input: ByteArray, deflated: ByteArray, stored: Boolean): List<Case>

internal class Case(val operation: String, val invoke: () -> Any)

// All corpus generation and compression of decoder inputs happen outside timing.
internal fun runBenchmarks(platform: String) {
    val profile = profile()
    require(profile in listOf("smoke", "full")) { "BENCHMARK_PROFILE must be smoke or full" }
    val sizes = if (profile == "smoke") listOf(100, 1024, 65536) else listOf(100, 1024, 65536, 4194304)
    val warmupNs = if (profile == "smoke") 100_000_000.0 else 250_000_000.0
    val sampleNs = if (profile == "smoke") 50_000_000.0 else 100_000_000.0
    val samples = if (profile == "smoke") 3 else 5
    println("BENCHMARK_META {\"platform\":${quote(platform)},\"runtime\":${quote(runtime())}," +
        "\"profile\":${quote(profile)},\"corpus_version\":1,\"samples\":$samples}")
    for (size in sizes) for (kind in listOf("text", "random", "runs", "stored")) {
        // Time alone can expire during class loading or interpretation, leaving
        // tiny cases below JIT invocation thresholds. Exercise them repeatedly.
        val minimumWarmupCalls = (4_194_304 / size).coerceIn(8, 20_000)
        val input = corpus(kind, size)
        val isStored = kind == "stored"
        val deflated = if (isStored) storedBlocks(input) else Deflate.encode(input)
        check(Inflate.inflate(deflated).contentEquals(input))
        val cases = mutableListOf(Case("inflate") { Inflate.inflate(deflated) })
        if (!isStored) {
            val lz4 = Lz4.frameCompress(input)
            val snappy = Snappy.compress(input)
            check(Lz4.frameDecompress(lz4).contentEquals(input))
            check(Snappy.decompress(snappy).contentEquals(input))
            cases += listOf(
                Case("crc32") { Crc32.of(input) },
                Case("crc32c") { Crc32c.of(input) },
                Case("adler32") { Adler32.of(input) },
                Case("crc64") { Crc64.of(input) },
                Case("xxhash32") { XxHash32.hash(input) },
                Case("deflate") { Deflate.encode(input) },
                Case("lz4.encode") { Lz4.frameCompress(input) },
                Case("lz4.decode") { Lz4.frameDecompress(lz4) },
                Case("snappy.encode") { Snappy.compress(input) },
                Case("snappy.decode") { Snappy.decompress(snappy) },
            )
        }
        cases += referenceCases(input, deflated, isStored)
        val digest = Crc32.of(input)
        for (case in cases) {
            fun measure(iterations: Int): Double {
                val start = cpuNanos()
                repeat(iterations) { consume(case.invoke()) }
                return cpuNanos() - start
            }
            var iterations = 1
            var warmup = 0.0
            var warmupCalls = 0
            var elapsed: Double
            do {
                elapsed = measure(iterations)
                warmup += elapsed
                warmupCalls += iterations
                if (warmup < warmupNs || warmupCalls < minimumWarmupCalls) {
                    iterations = minOf(iterations * 2, 1_048_576)
                }
            } while (warmup < warmupNs || warmupCalls < minimumWarmupCalls)
            iterations = ceil(sampleNs * iterations / elapsed.coerceAtLeast(1.0))
                .coerceIn(1.0, 1_048_576.0).toInt()
            val timings = List(samples) { measure(iterations) / iterations }
            val best = timings.min()
            check(best.isFinite() && best > 0) { "Invalid CPU timer result for ${case.operation}" }
            val mbps = size * 1000.0 / best
            println("BENCHMARK_RESULT {\"platform\":${quote(platform)},\"operation\":${quote(case.operation)}," +
                "\"corpus\":${quote(kind)},\"bytes\":$size,\"crc32\":$digest," +
                "\"warmup_calls\":$warmupCalls,\"warmup_cpu_ns\":$warmup," +
                "\"iterations\":$iterations,\"cpu_ns_per_call\":${timings.joinToString(prefix = "[", postfix = "]")}," +
                "\"mb_per_second\":$mbps}")
        }
    }
}

private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\")
    .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

private fun corpus(kind: String, size: Int): ByteArray {
    var state = 42
    fun random(): Int {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        return state and Int.MAX_VALUE
    }
    if (kind == "runs") return ByteArray(size) { 65 }
    if (kind != "text") return ByteArray(size) { random().toByte() }
    val words = ("the archive contains files with names and data every entry has a size " +
        "read write stream buffer checksum block compressed bytes kotlin portable format").split(' ')
    val result = ByteArray(size)
    var at = 0
    while (at < size) {
        val word = words[random() % words.size]
        for (char in word) if (at < size) result[at++] = char.code.toByte()
        if (at < size) result[at++] = if (random() % 12 == 0) 10 else 32
    }
    return result
}

// RFC 1951 stored blocks: byte-aligned header, LEN, one's-complement NLEN, payload.
private fun storedBlocks(input: ByteArray): ByteArray {
    val blocks = (input.size + 65534) / 65535
    val output = ByteArray(input.size + 5 * blocks)
    var source = 0
    var dest = 0
    while (source < input.size) {
        val count = minOf(65535, input.size - source)
        output[dest++] = if (source + count == input.size) 1 else 0
        output[dest++] = count.toByte()
        output[dest++] = (count ushr 8).toByte()
        output[dest++] = count.inv().toByte()
        output[dest++] = (count.inv() ushr 8).toByte()
        input.copyInto(output, dest, source, source + count)
        dest += count
        source += count
    }
    return output
}
