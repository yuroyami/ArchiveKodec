# Benchmarks

The `benchmarks` module runs the same Kotlin corpus generator, codec calls and
checksum calls on JVM and Kotlin/JS (Node). It is a development tool and is not
published with the libraries. It has no dependency beyond the core and Kotlin
standard library.

Run from the repository root with JDK 21 and the Android SDK required by the
multiplatform build. Gradle installs the configured Node distribution.

```sh
# Short run, including a comparison with the committed baseline.
python3 benchmarks/run.py --check

# Longer warm-up, more samples, and 4 MiB cases.
python3 benchmarks/run.py --profile full --output benchmarks/build/full.json

# Tests for the comparison gate, including an intentional slowdown.
python3 -B -m unittest discover -s benchmarks -p 'test_*.py'
```

Use `--reuse-daemon` to reuse a local Gradle daemon. Avoid running other builds or
benchmarks at the same time. Results are saved to `benchmarks/build/results.json`
unless `--output` is supplied. Each result includes every measured sample,
iterations, input size and CRC-32, and throughput in decimal MB/s of input bytes.
The report also records the runtime versions, source revision, working-tree state
and operating system. Compare several runs before drawing performance conclusions.
The runner finishes compilation first, then executes JVM and Node separately;
Gradle's asynchronous compilation must not overlap either measurement run.

## Workloads and timing

The versioned corpora use a fixed xorshift32 seed of 42, implemented in common
Kotlin with 32-bit arithmetic. They include a small ASCII word dictionary, random
bytes, repeated `A` bytes, and random bytes explicitly wrapped in RFC 1951 stored
blocks. Smoke runs use 100 B, 1 KiB and 64 KiB inputs. Full runs add 4 MiB inputs.

Every text, random and run input measures CRC-32, CRC-32C, Adler-32, CRC-64,
xxHash32, raw DEFLATE encode/decode, LZ4 frame encode/decode, and raw Snappy
encode/decode. Stored-block cases measure inflate. Decoder inputs are made once
before timing; DEFLATE streams use the core encoder except for the explicit
stored-block cases. Corpus creation, checksum comparisons and round-trip checks
are outside measurement.

JVM results include JDK `CRC32`, `CRC32C`, `Adler32`, raw `Inflater`, and raw
`Deflater` at levels 1 and 6. Both inflaters read exactly the same compressed bytes.
The JDK inflater reference allocates the known output size; the core grows its
output buffer. Encoding measurements include fresh encoder instances, output
allocation, and conversion to the returned byte array. The core encoder has no
compression-level setting, so the JDK levels are references, not identical parsing
algorithms. The JDK checksums are checked against the core before timing.

The harness uses current-thread CPU time on JVM and `process.cpuUsage()` (user plus
system CPU time) on Node. Node's measure covers the process, including runtime
helper threads; JVM's excludes helper and GC threads. These clocks avoid charging
time while the process is descheduled, but do not make JVM/Node numbers directly
comparable. This small custom harness uses CPU timers rather than the wall-clock
timers of a general benchmark framework.

Each case has an adaptive warm-up of at least 100 ms of CPU time in smoke mode, or
250 ms in full mode. A minimum call count also warms tiny inputs past JIT
invocation thresholds: 4 MiB divided by input size, clamped to 8–20,000 calls.
Both conditions must be satisfied; the actual warm-up is saved with each result.
Batches target 50 ms or 100 ms respectively, with between one
and 1,048,576 calls per batch. The reported rate uses the fastest of three smoke
samples or five full samples. A JVM volatile sink and a Node global sink retain
results to prevent dead-code elimination. Sink and call overhead is included,
which matters most for tiny inputs and hardware-accelerated JDK checksums.

## Baseline and CI

`benchmarks/baseline.json` contains all measurements from the documented smoke
baseline. The CI benchmark job runs both runtimes, requires every expected case,
checks corpus identities, and rejects any throughput below 10% of its baseline
(a slowdown greater than 10×). This intentionally wide threshold catches severe
regressions on shared runners; smaller changes require repeated measurements and
review. It is not an API throughput guarantee.

To replace the baseline after an intentional change:

```sh
python3 benchmarks/run.py --record-baseline
```

Review the JSON diff, repeat the run with `--check`, and update this page with the
machine, runtimes and representative numbers. CI never updates the baseline.
Changing corpus generation also requires increasing `corpus_version` in both the
Kotlin harness and Python validator.

### Initial smoke baseline

Recorded on 2026-10-08 on a shared Linux x86_64 cloud worker reporting an Intel
Xeon Platinum 8573C, with five CPUs available to the process. The library was at
`c1b727c`; the benchmark harness was in the working tree. Runtimes were OpenJDK
21.0.12.1 and Node 24.10.0 (V8 13.6.233.10), using Kotlin 2.4.10. Compilation
finished before the separately executed JVM and Node runs.

Representative **64 KiB text** results, in decimal MB/s; the final row uses the
stored-block corpus. These are short smoke measurements on a shared machine,
not sustained-throughput claims. The full profile is preferable for optimization
work. All 261 measurements, including the other corpora and small inputs, are in
the [baseline JSON](https://github.com/yuroyami/ArchiveKodec/blob/main/benchmarks/baseline.json).

| Operation | Kotlin JVM | JDK reference | Kotlin/JS |
|---|---:|---:|---:|
| CRC-32 | 492.8 | 21,584.8 | 591.2 |
| CRC-32C | 463.8 | 26,238.6 | 446.2 |
| Adler-32 | 338.6 | 9,379.4 | 505.3 |
| CRC-64 | 134.8 | — | 279.9 |
| xxHash32 | 1,260.3 | — | 504.6 |
| DEFLATE encode | 6.1 | 39.5 (level 1), 8.7 (level 6) | 1.1 |
| Inflate | 29.3 | 230.4 | 3.4 |
| LZ4 frame encode | 88.5 | — | 6.4 |
| LZ4 frame decode | 64.3 | — | 1.2 |
| Snappy encode | 65.5 | — | 8.5 |
| Snappy decode | 183.8 | — | 7.4 |
| Inflate, stored blocks | 688.1 | 2,169.4 | 419.0 |
