# KiteArchive

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Targets](https://img.shields.io/badge/targets-Android%20|%20iOS%20|%20JVM%20|%20JS-success)](#install)
[![Core deps](https://img.shields.io/badge/core%20dependencies-kotlin--stdlib%20only-blue)](#why-kitearchive)
[![Ported from](https://img.shields.io/badge/ports-zlib%20·%20xz%20·%20zstd%20·%20bzip2-orange)](reference/REFERENCES.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-lightgrey)](#license--credits)
[![Status](https://img.shields.io/badge/status-DEFLATE%20%2F%20gzip%20%2F%20zlib%20%2F%20tar-brightgreen)](PORTING_STATUS.md)
[![Tests](https://img.shields.io/badge/tests-36%20passing-brightgreen)](PORTING_STATUS.md)

**A pure-Kotlin compression & archive toolkit for Kotlin Multiplatform: from-scratch ports of the canonical references (zlib/puff, XZ, zstd, bzip2, …). The same `.kt` runs on Android, iOS, desktop JVM and the browser — no JNI, no cinterop, no native binary.**

```kotlin
// codecs — buffered today, streaming on the roadmap
val gz = KiteArchive.gzip.compress("hello".encodeToByteArray())
val back = KiteArchive.gzip.decompress(gz)              // bomb-guarded by default

// sniff a format from its magic bytes
KiteArchive.sniff(bytes)        // -> ArchiveFormat.ZIP / TAR / GZIP / XZ / ZSTD / ...

// browse a container without extracting (seekable source)
val reader = KiteArchive.open(ByteArrayRandomAccessSource(tarBytes))
reader.entries().forEach { println("${it.name} (${it.size} B)") }
val data = reader.read(reader.entries().first())        // partial extract
```

## Why KiteArchive

Every Kotlin Multiplatform app that touches archives today reaches outside Kotlin: `java.util.zip` on Android/JVM (which simply does not exist on iOS), a zlib cinterop or `pako` shim on the other targets. You inherit a native build, a native ABI, and a different code path per platform. KiteArchive closes that gap with one codebase, one mental model, every target.

The honest niche: **no existing library is a single, standalone, pure-Kotlin-`commonMain` codec that runs byte-identically on Android + iOS + JVM with no platform engine underneath.** Okio/kmp-zip delegate DEFLATE to system zlib; Kompress/korlibs are real but format-thin or framework-bound. KiteArchive is the broad, decoupled, golden-tested toolkit that fills it — exactly as KitePDF mirrors MuPDF and KiteQR mirrors ZXing.

The library is split into two artifacts so the boundary stays honest. The **core** is pure computation — codecs, container parsers, checksums, the path-traversal and decompression-bomb guards — and depends on nothing but `kotlin-stdlib`. The **`-io`** artifact adds the only things that need real I/O: kotlinx-io `RawSource`/`RawSink` bridges and seekable file handles. Filesystem access (Android SAF, the iOS sandbox) stays the consumer's problem — the core only ever sees byte streams.

## Install

KiteArchive is `0.0.1-SNAPSHOT`, consumed via `mavenLocal()`. Not on Maven Central yet.

```bash
./gradlew publishToMavenLocal
```

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // pure core: codecs, containers, checksums, security guards (Android, iOS, JVM, JS)
            implementation("io.github.yuroyami:kitearchive:0.0.1-SNAPSHOT")

            // kotlinx-io bridge: stream + seekable-file adapters (Android, iOS, JVM)
            implementation("io.github.yuroyami:kitearchive-io:0.0.1-SNAPSHOT")
        }
    }
}
```

## What works today

- 📦 **DEFLATE** (RFC 1951) — faithful `puff`-based inflate + a hash-chained LZ77 deflate encoder (fixed-Huffman; dynamic-Huffman is the next ratio upgrade).
- 🗜️ **gzip** (RFC 1952) & **zlib** (RFC 1950) — full framing with CRC-32 / Adler-32, compress **and** decompress. Golden-tested against CPython `zlib`/`gzip` output (level-6 dynamic blocks).
- 🧮 **Checksums** — CRC-32, CRC-32C, Adler-32, CRC-64/XZ, all verified against the standard `"123456789"` check values.
- 🗂️ **TAR** reader (v7 / ustar / GNU long-name, octal + base-256 fields).
- 🔎 **Magic sniffer** — ZIP, TAR, gzip, zlib, bzip2, xz, zstd, lz4, 7z, RAR.
- 🛡️ **Security, default-on** — `PathGuard` (Zip-Slip / traversal / symlink-escape names) and `ArchiveLimits` (uncompressed-size, ratio, entry-count, nesting caps) baked into the decode contract.
- 🧩 **Public API** locked — `Codec`, `RandomAccessSource`, `ArchiveReader`, `ArchiveEntry` — so the engine work behind them is additive.

See **[PORTING_STATUS.md](PORTING_STATUS.md)** for the full codec/format roadmap (bzip2 → LZMA/xz → zstd → …) and the per-format feasibility/legal calls.

## Building

```bash
./gradlew :kitearchive:jvmTest         # pure-core test suite
./gradlew :kitearchive-io:jvmTest      # kotlinx-io bridge tests
./gradlew build                        # all modules, all targets
```

Requires JDK 21 and the Android SDK (`local.properties` → `sdk.dir`).

## Porting references

Clean-room ports start from permissively-licensed references checked out under `reference/` (git-ignored, never distributed) — see **[reference/REFERENCES.md](reference/REFERENCES.md)** for the codec→source→license map. **RAR / ACE / StuffIt are deliberately excluded** (license / no clean reference); KiteArchive will never create RAR archives.

## License & credits

KiteArchive is provided under the **Apache-2.0** license. It is an independent, clean-room reimplementation; the inflate path derives from Mark Adler's public-domain `puff`. Format references are each under permissive licenses (zlib / 0BSD / BSD / MIT / Apache-2.0). See [NOTICE](NOTICE).
