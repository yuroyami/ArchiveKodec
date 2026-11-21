# Benchmarks

The `benchmarks` module runs the same Kotlin corpus generator, codec calls and
checksum calls on JVM and Kotlin/JS (Node). It is a development tool and is not
published with the libraries. It has no dependency beyond the core and Kotlin
standard library.

Run from the repository root, with the Android SDK that the build needs in
`ANDROID_HOME`. The Kotlin Toolchain downloads the JDK. The runner uses `node` from
`PATH`, or else the Node that the toolchain downloaded.

```sh
# Short run, including a comparison with the committed baseline.
python3 benchmarks/run.py --check

# Longer warm-up, more samples, and 4 MiB cases.
python3 benchmarks/run.py --profile full --output benchmarks/build/full.json

# Tests for the comparison gate, including an intentional slowdown.
python3 -B -m unittest discover -s benchmarks -p 'test_*.py'
```

Avoid running other builds or benchmarks at the same time. Results are saved to `benchmarks/build/results.json`
unless `--output` is supplied. Each result includes every measured sample,
iterations, input size and CRC-32, and throughput in decimal MB/s of input bytes.
The report also records the runtime versions, source revision, working-tree state
and operating system. Compare several runs before drawing performance conclusions.
The runner finishes compilation first, then runs JVM and Node one after the other,
so no compilation overlaps a measurement run. The `benchmarks-jvm` and `benchmarks-js`
apps hold the entry points for the two runtimes.

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
allocation, and conversion to the returned byte array. The core encoder runs at its
default level 6, and the JDK levels are references, not identical parsing algorithms.
The JDK checksums are checked against the core before timing.

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

## Compression ratios

Compressed sizes do not depend on speed, so they have their own harness:
`scripts/compression-ratios.py` measures every encoder on the Silesia Corpus next to the
reference tools, and `--memory` finds the heap each codec needs.
[Compression ratios](compression.md#compression-ratios) and [Memory](compression.md#memory)
show the results and how to reproduce them.

## Encryption

```sh
python3 benchmarks/run.py --suite crypto
```

The crypto suite measures the built-in provider, `CryptoProvider.DEFAULT`, on random data:

- AES-128 and AES-256 in ECB mode, AES-256 CBC decryption, HMAC-SHA1, and the write and read
  of one stored WinZip AES-256 entry, in MB/s of input.
- The PBKDF2 of one WinZip AES-256 entry, and the opening of a 7z AES archive that 7-Zip wrote
  with 2^19 key rounds, in milliseconds per call.

On the JVM it adds the JDK's AES-256 and HMAC-SHA1 as references. The suite has no baseline,
and CI does not run it. It saves its report to `benchmarks/build/crypto.json`, and
[Encrypted archives](encryption.md#speed) shows the latest numbers.

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

### Smoke baseline

Recorded on 2026-11-19 on a shared Linux x86_64 cloud worker reporting an Intel Xeon
processor at 2.10 GHz, with four CPUs available to the process. Runtimes were OpenJDK
21.0.13 and Node 22.23.5 (V8 12.4.254.21), using Kotlin 2.4.21. Compilation finished before
the separately executed JVM and Node runs. The previous baseline, of October 2026, was 3 to
100 times below these numbers after the speed work since then, so its 10x gate no longer
caught anything.

Representative **64 KiB text** results, in decimal MB/s; the final row uses the
stored-block corpus. These are short smoke measurements on a shared machine,
not sustained-throughput claims. The full profile is preferable for optimization
work. All 261 measurements, including the other corpora and small inputs, are in
the [baseline JSON](https://github.com/yuroyami/ArchiveKodec/blob/main/benchmarks/baseline.json).

| Operation | Kotlin JVM | JDK reference | Kotlin/JS |
|---|---:|---:|---:|
| CRC-32 | 1,344.8 | 57,847.5 | 1,089.3 |
| CRC-32C | 1,343.9 | 57,622.1 | 1,055.8 |
| Adler-32 | 1,875.2 | 22,093.0 | 1,339.0 |
| CRC-64 | 525.5 |  | 437.9 |
| xxHash32 | 3,750.1 |  | 1,547.1 |
| DEFLATE encode | 14.4 | 148.0 (level 1), 27.5 (level 6) | 9.5 |
| Inflate | 521.5 | 579.9 | 124.3 |
| LZ4 frame encode | 220.4 |  | 124.3 |
| LZ4 frame decode | 788.6 |  | 101.8 |
| Snappy encode | 210.2 |  | 134.6 |
| Snappy decode | 673.0 |  | 123.5 |
| Inflate, stored blocks | 3,313.6 | 5,566.3 | 2,996.7 |
