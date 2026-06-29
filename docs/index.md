# KiteArchive

A pure-Kotlin compression & archive toolkit for Kotlin Multiplatform — from-scratch
ports of the canonical references (zlib/`puff`, XZ, zstd, bzip2, …). The same `.kt` runs
on Android, iOS, desktop JVM and the browser: **no JNI, no cinterop, no native binary.**

```kotlin
val gz = KiteArchive.gzip.compress("hello".encodeToByteArray())
val back = KiteArchive.gzip.decompress(gz)        // decompression-bomb guarded by default

KiteArchive.sniff(bytes)                            // -> ArchiveFormat.ZIP / TAR / GZIP / ...

val reader = KiteArchive.open(ByteArrayRandomAccessSource(tarBytes))
reader.entries().forEach { println(it.name) }      // browse without extracting
```

See the [README](https://github.com/yuroyami/KiteArchive) for install and the
[porting status](https://github.com/yuroyami/KiteArchive/blob/main/PORTING_STATUS.md)
for the full codec/format roadmap.

## Modules

- **`kitearchive`** — the pure core (`kotlin-stdlib` only): codecs (DEFLATE, gzip, zlib,
  store), checksums (CRC-32/32C, Adler-32, CRC-64), container readers (TAR; ZIP on the
  roadmap), the magic sniffer, and the default-on `PathGuard` + `ArchiveLimits` security
  guards. Targets Android, iOS, JVM, JS.
- **`kitearchive-io`** — the kotlinx-io adapter: `RawSource`/`RawSink` bridges and seekable
  file handles. Targets Android, iOS, JVM.
