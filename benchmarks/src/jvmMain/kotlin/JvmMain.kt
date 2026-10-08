package io.github.yuroyami.archivekodec.benchmarks

import io.github.yuroyami.archivekodec.checksum.Adler32 as KotlinAdler32
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.checksum.Crc32c
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Checksum
import java.util.zip.Deflater
import java.util.zip.Inflater

private val timer = ManagementFactory.getThreadMXBean().also {
    check(it.isCurrentThreadCpuTimeSupported) { "This JVM has no current-thread CPU timer" }
    it.isThreadCpuTimeEnabled = true
}
@Volatile private var sink: Any? = null
internal actual fun cpuNanos(): Double = timer.currentThreadCpuTime.toDouble()
internal actual fun consume(value: Any) { sink = value }
internal actual fun profile(): String = System.getenv("BENCHMARK_PROFILE") ?: "smoke"
internal actual fun runtime(): String = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
fun main() = runBenchmarks("jvm")

internal actual fun referenceCases(input: ByteArray, deflated: ByteArray, stored: Boolean): List<Case> {
    check(inflate(deflated, input.size).contentEquals(input))
    val cases = mutableListOf(Case("jdk.inflate") { inflate(deflated, input.size) })
    if (stored) return cases
    fun checksum(factory: () -> Checksum): Long = factory().apply { update(input, 0, input.size) }.value
    check(checksum(::CRC32) == Crc32.of(input))
    check(checksum(::CRC32C) == Crc32c.of(input))
    check(checksum(::Adler32) == KotlinAdler32.of(input))
    cases += listOf(
        Case("jdk.crc32") { checksum(::CRC32) },
        Case("jdk.crc32c") { checksum(::CRC32C) },
        Case("jdk.adler32") { checksum(::Adler32) },
    )
    for (level in listOf(1, 6)) {
        check(inflate(deflate(input, level), input.size).contentEquals(input))
        cases += Case("jdk.deflate.$level") { deflate(input, level) }
    }
    return cases
}

private fun inflate(input: ByteArray, expectedSize: Int): ByteArray {
    val decoder = Inflater(true)
    try {
        decoder.setInput(input)
        // Unlike the core's growing buffer, this reference knows the output length.
        val output = ByteArray(expectedSize)
        var used = 0
        while (!decoder.finished()) {
            val count = decoder.inflate(output, used, output.size - used)
            check(count > 0 || decoder.finished()) { "Inflater made no progress" }
            used += count
        }
        check(used == expectedSize)
        return output
    } finally {
        decoder.end()
    }
}

private fun deflate(input: ByteArray, level: Int): ByteArray {
    val encoder = Deflater(level, true)
    try {
        encoder.setInput(input)
        encoder.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!encoder.finished()) {
            val count = encoder.deflate(buffer)
            check(count > 0 || encoder.finished()) { "Deflater made no progress" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally {
        encoder.end()
    }
}
