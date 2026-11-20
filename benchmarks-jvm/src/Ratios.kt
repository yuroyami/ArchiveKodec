package io.github.yuroyami.archivekodec.benchmarks

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.codec.bzip2.Bzip2Options
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.lz4.Lz4Options
import java.io.ByteArrayOutputStream
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// The compression-ratio harness that scripts/compression-ratios.py drives (BENCHMARK_SUITE=ratios).
// It reads a job file of tab-separated lines and prints one RATIO_RESULT line per job:
//   <id> encode <codec> <variant> <input> <output> [<zip output>]
//   <id> decode <codec> <input> <output>
//   <id> unzip <input> <output>
//   <id> heap <operation> <codec> <input>
// A heap job finds the smallest -Xmx of HEAP_LADDER_MIB at which one operation (arrayencode,
// arraydecode, streamencode or streamdecode) succeeds in a child JVM, and reports it in MiB,
// or -1 when even the largest fails. Variants: default, level=N (DEFLATE, zlib, gzip, bzip2), linked (LZ4 linked blocks),
// session (the codec's EncoderSession fed 64 KiB writes) and records=N (one encode per N-byte record).
// Every encode is decoded again and compared with its input. Sizes do not depend on timing,
// so jobs run on RATIOS_THREADS threads (default 2).

private const val RESULT_PREFIX = "RATIO_RESULT "

internal fun runRatios() {
    val jobFile = System.getenv("RATIOS_JOBS") ?: error("RATIOS_JOBS must name a job file")
    val threads = System.getenv("RATIOS_THREADS")?.toInt() ?: 2
    val jobs = File(jobFile).readLines().filter { it.isNotBlank() }.map { it.split('\t') }
    println("RATIO_META {\"runtime\":${quote("${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")}," +
        "\"version\":${quote(ArchiveKodec.VERSION)}}")
    val pool = Executors.newFixedThreadPool(threads)
    try {
        val futures = jobs.map { job -> pool.submit(Callable { report(job) }) }
        for (future in futures) future.get()
    } finally {
        pool.shutdown()
    }
}

private fun report(job: List<String>) {
    val id = job[0]
    val line = try {
        val size = when (job[1]) {
            "encode" -> encodeJob(job[2], job[3], File(job[4]), File(job[5]), job.getOrNull(6)?.let(::File))
            "decode" -> decodeJob(job[2], File(job[3]), File(job[4]))
            "unzip" -> unzipJob(File(job[2]), File(job[3]))
            "heap" -> smallestHeap(job[2], job[3], job[4]).toLong()
            else -> error("unknown job kind ${job[1]}")
        }
        "{\"id\":${quote(id)},\"ok\":true,\"size\":$size}"
    } catch (failure: Throwable) {
        "{\"id\":${quote(id)},\"ok\":false,\"error\":${quote(failure.toString())}}"
    }
    synchronized(RESULT_PREFIX) { println(RESULT_PREFIX + line) }
}

private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

private fun codec(name: String): Codec = Codecs.byName(name) ?: error("no codec named $name")

private fun encodeJob(name: String, variant: String, input: File, output: File, zipOutput: File?): Long {
    val codec = codec(name)
    val data = input.readBytes()
    val encoded: ByteArray
    if (variant.startsWith("records=")) {
        // One encode per record, as PalmDOC and MOBI store text; each record is checked on its own.
        val record = variant.removePrefix("records=").toInt()
        val out = ByteArrayOutputStream()
        var total = 0L
        for (start in data.indices step record) {
            val piece = data.copyOfRange(start, minOf(start + record, data.size))
            val packed = codec.compress(piece)
            check(codec.decompress(packed).contentEquals(piece)) { "$name record at $start did not round-trip" }
            out.write(packed)
            total += packed.size
        }
        encoded = out.toByteArray()
        output.writeBytes(encoded)
        return total
    }
    encoded = when {
        variant == "default" -> codec.compress(data)
        variant.startsWith("level=") -> codec.compress(data, levelOptions(name, variant.removePrefix("level=").toInt()))
        variant == "linked" -> codec.compress(data, Lz4Options { linkedBlocks = true })
        variant == "session" -> {
            val out = ByteArrayOutputStream()
            val session = codec.encoder(ByteSink { bytes, offset, length -> out.write(bytes, offset, length) })
            for (start in data.indices step 65536) session.write(data, start, minOf(65536, data.size - start))
            session.finish()
            out.toByteArray()
        }
        else -> error("unknown variant $variant")
    }
    check(codec.decompress(encoded).contentEquals(data)) { "$name $variant did not round-trip" }
    output.writeBytes(encoded)
    if (zipOutput != null) zipOutput.writeBytes(singleEntryZip(name, input.name, data, encoded))
    return encoded.size.toLong()
}

private fun levelOptions(name: String, level: Int) = when (name) {
    "deflate", "zlib", "gzip" -> DeflateOptions { this.level = level }
    "bzip2" -> Bzip2Options { this.level = level }
    else -> error("$name has no levels")
}

private fun decodeJob(name: String, input: File, output: File): Long {
    val decoded = codec(name).decompress(input.readBytes())
    output.writeBytes(decoded)
    return decoded.size.toLong()
}

private fun unzipJob(input: File, output: File): Long = ArchiveKodec.open(input.readBytes()).use { reader ->
    val entry = reader.entries().single { !it.isDirectory }
    val decoded = reader.read(entry)
    output.writeBytes(decoded)
    decoded.size.toLong()
}

private val HEAP_LADDER_MIB = listOf(16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536)

/** Binary search of [HEAP_LADDER_MIB], assuming that a larger heap never fails where a smaller one passes. */
private fun smallestHeap(operation: String, codec: String, input: String): Int {
    var low = 0
    var high = HEAP_LADDER_MIB.size // the first index known to pass; size means none yet
    var probe = HEAP_LADDER_MIB.indexOf(256)
    while (low < high) {
        if (passes(HEAP_LADDER_MIB[probe], operation, codec, input)) high = probe else low = probe + 1
        probe = (low + high) / 2
        if (probe == HEAP_LADDER_MIB.size) break
    }
    if (high == HEAP_LADDER_MIB.size) return -1
    return HEAP_LADDER_MIB[high]
}

private fun passes(heapMib: Int, operation: String, codec: String, input: String): Boolean {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val process = ProcessBuilder(java, "-Xmx${heapMib}m", "-cp", System.getProperty("java.class.path"),
        HeapChild::class.java.name, operation, codec, input).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    return process.waitFor() == 0 && output.lines().any { it.startsWith("OK ") }
}

/** The child JVM of a heap job: one operation, then `OK <bytes>`. Any failure, including OutOfMemoryError, exits non-zero. */
internal object HeapChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val codec = codec(args[1])
        val size = when (args[0]) {
            "arrayencode" -> codec.compress(File(args[2]).readBytes()).size.toLong()
            "arraydecode" -> codec.decompress(File(args[2]).readBytes()).size.toLong()
            "streamencode" -> {
                var written = 0L
                val session = codec.encoder(ByteSink { _, _, length -> written += length })
                val piece = ByteArray(65536)
                FileInputStream(args[2]).use { input ->
                    while (true) {
                        val n = input.read(piece)
                        if (n < 0) break
                        session.write(piece, 0, n)
                    }
                }
                session.finish()
                written
            }
            "streamdecode" -> FileInputStream(args[2]).use { input ->
                codec.decompress(ByteSource { bytes, offset, length -> input.read(bytes, offset, length) },
                    ByteSink { _, _, _ -> }, ArchiveLimits.DEFAULT)
            }
            else -> error("unknown operation ${args[0]}")
        }
        println("OK $size")
    }
}

/**
 * A ZIP holding one member whose data is [packed], so that 7-Zip can read the PPMd and
 * Deflate64 streams, which have no standalone file format. ZipWriter writes only stored and
 * DEFLATE members.
 */
private fun singleEntryZip(codec: String, name: String, original: ByteArray, packed: ByteArray): ByteArray {
    val (method, version) = when (codec) {
        "ppmd" -> 98 to 63
        "deflate64" -> 9 to 21
        else -> error("$codec has no ZIP wrapping here")
    }
    val nameBytes = name.encodeToByteArray()
    val crc = Crc32.of(original)
    val out = ByteArrayOutputStream()
    fun u16(v: Int) { out.write(v and 0xFF); out.write(v ushr 8 and 0xFF) }
    fun u32(v: Long) { for (i in 0 until 4) out.write((v ushr (8 * i)).toInt() and 0xFF) }
    fun common() {
        u16(version); u16(0); u16(method); u16(0); u16(0x21) // no flags; 1980-01-01 00:00
        u32(crc); u32(packed.size.toLong()); u32(original.size.toLong()); u16(nameBytes.size); u16(0)
    }
    u32(0x04034b50); common(); out.write(nameBytes); out.write(packed)
    val directory = out.size().toLong()
    u32(0x02014b50); u16(version); common(); u16(0); u16(0); u16(0); u32(0); u32(0); out.write(nameBytes)
    val directorySize = out.size() - directory
    u32(0x06054b50); u16(0); u16(0); u16(1); u16(1); u32(directorySize); u32(directory); u16(0)
    return out.toByteArray()
}
