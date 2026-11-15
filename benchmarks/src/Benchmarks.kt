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
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipEncryption
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.crypto.ArchivePasswordProvider
import io.github.yuroyami.archivekodec.crypto.CryptoProvider
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.ceil

internal expect fun cpuNanos(): Double
internal expect fun consume(value: Any)
internal expect fun profile(): String
internal expect fun runtime(): String
internal expect fun referenceCases(input: ByteArray, deflated: ByteArray, stored: Boolean): List<Case>
/** The suite that BENCHMARK_SUITE names: `codecs` (the default) or `crypto`. */
internal expect fun suite(): String
internal expect fun referenceCryptoCases(input: ByteArray, key: ByteArray): List<Case>

internal class Case(val operation: String, val invoke: () -> Any)

// All corpus generation and compression of decoder inputs happen outside timing.
// The benchmarks-jvm and benchmarks-js apps call this from their main functions.
fun runBenchmarks(platform: String) {
    val profile = profile()
    require(profile in listOf("smoke", "full")) { "BENCHMARK_PROFILE must be smoke or full" }
    when (suite()) {
        "codecs" -> runCodecBenchmarks(platform, profile)
        "crypto" -> runCryptoBenchmarks(platform, profile)
        else -> throw IllegalArgumentException("BENCHMARK_SUITE must be codecs or crypto")
    }
}

private fun runCodecBenchmarks(platform: String, profile: String) {
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
            val timing = time(case, warmupNs, sampleNs, samples, minimumWarmupCalls)
            val mbps = size * 1000.0 / timing.best
            println("BENCHMARK_RESULT {\"platform\":${quote(platform)},\"operation\":${quote(case.operation)}," +
                "\"corpus\":${quote(kind)},\"bytes\":$size,\"crc32\":$digest," +
                "\"warmup_calls\":${timing.warmupCalls},\"warmup_cpu_ns\":${timing.warmupNs}," +
                "\"iterations\":${timing.iterations},\"cpu_ns_per_call\":${timing.samples.joinToString(prefix = "[", postfix = "]")}," +
                "\"mb_per_second\":$mbps}")
        }
    }
}

private class Timing(val warmupCalls: Int, val warmupNs: Double, val iterations: Int, val samples: List<Double>) {
    val best: Double get() = samples.min()
}

// Time alone can expire during class loading or interpretation, so warm-up also needs a minimum number of calls.
private fun time(case: Case, warmupNs: Double, sampleNs: Double, samples: Int, minimumWarmupCalls: Int): Timing {
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
    val timing = Timing(warmupCalls, warmup, iterations, List(samples) { measure(iterations) / iterations })
    check(timing.best.isFinite() && timing.best > 0) { "Invalid CPU timer result for ${case.operation}" }
    return timing
}

private const val PASSWORD = "correct horse battery staple"

/**
 * Encryption through the core's built-in provider. AES, HMAC and whole WinZip AES entries report MB/s of
 * input. The key derivations run once for each WinZip AES entry or 7z reader, so they report CPU time per call.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun runCryptoBenchmarks(platform: String, profile: String) {
    val sizes = if (profile == "smoke") listOf(65536) else listOf(65536, 4194304)
    val warmupNs = if (profile == "smoke") 100_000_000.0 else 250_000_000.0
    val sampleNs = if (profile == "smoke") 50_000_000.0 else 100_000_000.0
    val samples = if (profile == "smoke") 3 else 5
    println("BENCHMARK_META {\"platform\":${quote(platform)},\"runtime\":${quote(runtime())}," +
        "\"profile\":${quote(profile)},\"suite\":\"crypto\",\"corpus_version\":1,\"samples\":$samples}")
    val crypto = CryptoProvider.DEFAULT
    val key128 = ByteArray(16) { it.toByte() }
    val key256 = ByteArray(32) { it.toByte() }
    val iv = ByteArray(16) { (3 * it).toByte() }
    val options = ArchiveOpenOptions { password = ArchivePasswordProvider.of(PASSWORD) }
    for (size in sizes) {
        val input = corpus("random", size)
        val files = listOf(ZipWriter.FileSpec("random.bin", input, ZipMethod.STORED))
        val zip = ZipWriter.write(files, ZipEncryption(PASSWORD.toCharArray()))
        check(ArchiveKodec.open(zip, null, options).use { it.read(it.entries().single()) }.contentEquals(input))
        val cases = listOf(
            Case("aes128.ecb") { crypto.aesEcbEncrypt(key128, input) },
            Case("aes256.ecb") { crypto.aesEcbEncrypt(key256, input) },
            Case("aes256.cbc.decrypt") { crypto.aesCbcDecrypt(key256, iv, input) },
            Case("hmac.sha1") { crypto.hmacSha1(key256, input) },
            Case("winzip.aes256.write") { ZipWriter.write(files, ZipEncryption(PASSWORD.toCharArray())) },
            Case("winzip.aes256.read") { ArchiveKodec.open(zip, null, options).use { it.read(it.entries().single()) } },
        ) + referenceCryptoCases(input, key256)
        for (case in cases) {
            val timing = time(case, warmupNs, sampleNs, samples, (4_194_304 / size).coerceIn(8, 20_000))
            cryptoResult(platform, case, size, timing, "\"mb_per_second\":${size * 1000.0 / timing.best}")
        }
    }
    val password = PASSWORD.encodeToByteArray()
    val salt = ByteArray(16) { it.toByte() }
    val sevenZip = Base64.decode(AES_7Z)
    val sevenZipOptions = ArchiveOpenOptions { this.password = ArchivePasswordProvider.of("SECRET") }
    check(ArchiveKodec.open(sevenZip, null, sevenZipOptions).use { it.read(it.entries().single()) }.size == 3000)
    val perCall = listOf(
        // One WinZip AES-256 entry: 66 bytes of key material from 1000 iterations.
        Case("pbkdf2.sha1.1000") { crypto.pbkdf2HmacSha1(password, salt, 1000, 66) },
        // A fresh reader derives the key with 2^19 SHA-256 rounds, then decrypts and decodes 3000 bytes.
        Case("sevenzip.aes.open") { ArchiveKodec.open(sevenZip, null, sevenZipOptions).use { it.read(it.entries().single()) } },
    )
    for (case in perCall) {
        val timing = time(case, warmupNs, sampleNs, samples, 8)
        cryptoResult(platform, case, 0, timing, "\"ms_per_call\":${timing.best / 1_000_000.0}")
    }
}

private fun cryptoResult(platform: String, case: Case, size: Int, timing: Timing, measure: String) {
    println("BENCHMARK_RESULT {\"suite\":\"crypto\",\"platform\":${quote(platform)},\"operation\":${quote(case.operation)}," +
        "\"bytes\":$size,\"warmup_calls\":${timing.warmupCalls},\"warmup_cpu_ns\":${timing.warmupNs}," +
        "\"iterations\":${timing.iterations},\"cpu_ns_per_call\":${timing.samples.joinToString(prefix = "[", postfix = "]")},$measure}")
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

// 7-Zip 23.01, `7z a -pSECRET`: one 3000-byte file, LZMA2 inside AES-256 with 2^19 key rounds.
private const val AES_7Z =
    "N3q8ryccAASfFOpdkAQAAAAAAABqAAAAAAAAADRahtSyU6FiiHMcOkIHS8gZh+8nKHnbOSFQ9CeFh/sSkvxYev4DBNv+LxBirUCs" +
    "YXDYNQA1bgZBqiIKw6jwJNv93fUCFhjlOekLJ9dToR8TQnqmjVDM9c90pm7RDQ5z/64hyBt7pGVsXuR52Sy+8IGwbusfBs547A4Y" +
    "lAIvDDEQKeUfP36jtYrC2ODbCz6rP1TzUVfc8Cs4mkD2eQeVQMnAgxXsHlbwuRwrbalnlH+MLTtwGJbnszkHm8uEXGpkfZi3ajka" +
    "ocm+4RC24Lb9LKHVFJJNday4/5LFR4rK/swCJZtTe3ErTS/gqD1s37JbeXI0BO1MvFOKi6olSPJaXhJB3xuJHGJ0hZMZOk9zFzkk" +
    "Ce7+8u2IwQmlXEue6FyDnCrNVuhUDVQwwTAhuEphyu7gRuPFCaLw4cowD8At2/MgByC5yD488hW9dKxn04Ry0+/MkCZZvZCyDiL5" +
    "qeMQHxNBsh4aRmYeAMjDVHy8ZSuabxxrlhSlSGm5/F8tBAgowYBbQLq7IURVP4lczcrdMJiQzAeK9on07snQY1ToWKCJfpJfy73A" +
    "6lMpuYfdr7mQdAtFnTwHNa64eQHN5eZWF3VgLhXwrJLgjIoNlJeEJFH4NBPa9p5kd2wUni4yQiL21OD9B0S7ultVX9U986KXl7Hf" +
    "fXE1cjQfmAeqMG0MohwLUx5Nv5Q3oLSnW1l/o5oGL3Kh6/+gC1kC64Q+7mnjkODzhxpWU4cDJ/Zlr8lJv8uieXMfR7ELL1B0yBED" +
    "BZsfuUFUJIajaxQF9n2mpT/zOeQxvkgrjkWJFfFgBYeh8Av6J4amaU8/Zbt9XEiRnQX0vDVzroW8xzB3syg4xo50qCXY1mFiT/hs" +
    "yhXo7uGS3DkGx29DHT8qDIDj1U9KwWxvIhrEi9n9iwyg8qfmR4a5VixAh/Tk90cKiG761WvuXC4dckTlbs9Ce3ETvJGs3QY10rgF" +
    "2MeGMzIUhinRHkht4qzUNzJVZPZRh5HbmEQLGADlTOJOE6Dz59LkTAc3rqdXEhhX18aVQC+Mliabf4qJDCGgYq/jXGEBQ/3N8zfC" +
    "ad/nxs7+vzr/IRXoo620GLHs/PWv8Gqw7SXxYI4fj8eMXiozsuqXuFR/5KqPziENU0jRqwtdkcTt+CftwXX9GSYdoPJU3uJ4dJh2" +
    "Bg0oRsP3w8PhqvWyCqKG7PlHA0ylfoxrCoJv1JLh+OPDjQ2OclZvVmtyW5E2BUc5c/1DVf81xm+w2KiwqSFXjPx6MK9HTcT/UaDQ" +
    "zP6RrbC7ovIJbwfKynjHISuXpKCbbGX1uGWZYBhx6s3G13KtE6MMTwRgzVW/Qm8r2ROqZ5uRi4Nu+47odVbRIwnTOmr+OYAg1fxA" +
    "2SWSAn/hgs2BGICSWsSjMrVLS/7a95yj+zjIH3Lv8S8or9e7T85OuLcfDmAM0wOOuyW05rrg7BS/Be6r6H47MktNq90SfhjYP0e5" +
    "TWsX5obSOYGGYvS3QzEo3Km8JIweaw3gF6/L2E30rg85mVxoiglnWNh4Fw9hwC/EZBJ792rJYTjyupp3FWs9xzxCzl3KeDZ5EET2" +
    "AQQGAAEJhJAABwsBAAIkBvEHARJTDzjymv7pI32M2E+IXCnz5lUhIQEAAQAMhIeLuAAICgEHNwbmAAAFAREPAGkAbgAuAHQAeAB0" +
    "AAAAGQQAAAAAFAoBAFv4ct2pV90BFQYBACCApIEAAA=="
