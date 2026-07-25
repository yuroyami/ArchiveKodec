# KiteArchive

Compression codecs and archive readers written in pure Kotlin, for Kotlin
Multiplatform projects that need to gunzip a buffer or read a ZIP on iOS and the
browser as well as on the JVM. No JNI, no cinterop, no native binary; the core
artifact's only dependency is `kotlin-stdlib`.

```kotlin
import io.github.yuroyami.kitearchive.KiteArchive
import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.kitearchive.security.PathGuard

val gz = KiteArchive.gzip.compress("hello".encodeToByteArray())
val back = KiteArchive.gzip.decompress(gz)

KiteArchive.sniff(bytes)                            // -> ArchiveFormat.ZIP / TAR / GZIP / ...

val reader = KiteArchive.open(ByteArrayRandomAccessSource(zipBytes))
for (entry in reader.entries()) {
    // Names come back exactly as stored — sanitize before joining one to a path.
    println(PathGuard.sanitize(entry.name))
}
```

Everything is whole-array: you pass a `ByteArray` and get a `ByteArray` back, with
the payload fully in memory. There is no streaming API, and `PathGuard` is a
function you call rather than a guard the readers apply for you.

See the [README](https://github.com/yuroyami/KiteArchive) for install, targets and
the full list of limits, and the
[porting status](https://github.com/yuroyami/KiteArchive/blob/main/PORTING_STATUS.md)
for what is implemented per format and what is next.

## Modules

- **`kitearchive`** — the core (`kotlin-stdlib` only): DEFLATE, gzip, zlib, LZ4 and
  Snappy codecs; ZIP reader and writer (+ZIP64, STORE and DEFLATE); TAR, ar and
  cpio readers; CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32; a magic sniffer;
  and the opt-in `PathGuard` and `ArchiveLimits` checks. Targets Android, iOS, JVM
  and JS.
- **`kitearchive-io`** — four convenience adapters over kotlinx-io `RawSource`.
  There is no `RawSink` bridge and no seekable file handle. Targets Android, iOS
  and JVM — **no JS**, so it cannot go in the same `commonMain` as the core in a
  project that builds for JS.

## Not implemented

`CodecId` names bzip2, LZMA, LZMA2, xz, zstd, brotli and PPMd, and the sniffer
recognises several of them, but none has an implementation —
`KiteArchive.codec(id)` returns `null` for those ids. ZIP is the only container
with a writer.
