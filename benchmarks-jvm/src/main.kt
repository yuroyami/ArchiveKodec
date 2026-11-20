package io.github.yuroyami.archivekodec.benchmarks

// BENCHMARK_SUITE=ratios runs the compression-ratio jobs of scripts/compression-ratios.py instead.
fun main() = if (System.getenv("BENCHMARK_SUITE") == "ratios") runRatios() else runBenchmarks("jvm")
