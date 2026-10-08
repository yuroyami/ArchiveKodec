package io.github.yuroyami.archivekodec.benchmarks

private external val process: dynamic

internal actual fun cpuNanos(): Double {
    val usage = process.cpuUsage()
    return ((usage.user as Double) + (usage.system as Double)) * 1000.0
}
internal actual fun consume(value: Any) { js("globalThis.archivekodecBenchmarkSink = value") }
internal actual fun profile(): String = (process.env.BENCHMARK_PROFILE as String?) ?: "smoke"
internal actual fun runtime(): String = "Node ${process.version} / V8 ${process.versions.v8}"
internal actual fun referenceCases(input: ByteArray, deflated: ByteArray, stored: Boolean): List<Case> = emptyList()
fun main() = runBenchmarks("js")
