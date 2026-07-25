# KiteArchive

Compression and archive formats implemented in pure Kotlin, for Kotlin
Multiplatform projects that need to gunzip a buffer or read a ZIP on iOS and in
the browser as well as on the JVM.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-Android%20%7C%20iOS%20%7C%20JVM%20%7C%20JS-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

## What you get

`java.util.zip` exists on the JVM and Android and nowhere else, so a Kotlin
Multiplatform project that touches archives usually ends up with a zlib cinterop
on iOS and a JavaScript shim on web — a native build, a native ABI, and a
different code path per target. KiteArchive implements the formats in
`commonMain` instead. No JNI, no cinterop, no native binary; the core artifact's
only dependency is `kotlin-stdlib`.

What is implemented today: DEFLATE both directions, gzip and zlib framing, ZIP
read and write including ZIP64, LZ4, a Snappy decoder, readers for TAR, ar and
cpio, five checksums and a magic sniffer. Everything is whole-array — you hand it
a `ByteArray` and get a `ByteArray` back, with the whole payload in memory. There
is no streaming API, and the readers do not sanitize entry names — you call
`PathGuard` yourself before you touch a path.

```kotlin
import io.github.yuroyami.kitearchive.KiteArchive
import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.kitearchive.archive.zip.ZipWriter
import io.github.yuroyami.kitearchive.codec.CodecId
import io.github.yuroyami.kitearchive.security.PathGuard

val gz = KiteArchive.gzip.compress("hello".encodeToByteArray())
val back = KiteArchive.gzip.decompress(gz)

val zipBytes = ZipWriter.write(
    listOf(ZipWriter.FileSpec("hello.txt", "Hello!".encodeToByteArray(), CodecId.STORE)),
)

val reader = KiteArchive.open(ByteArrayRandomAccessSource(zipBytes))
for (entry in reader.entries()) {
    // Entry names come back exactly as stored. Sanitize before touching a path.
    val safeName = PathGuard.sanitize(entry.name)
    val content = reader.read(entry)
    println("$safeName (${content.size} B)")
}
```

## Install

KiteArchive is not published. It is `0.0.1-SNAPSHOT` and you consume it from
`mavenLocal()` after publishing it yourself.

```bash
./gradlew publishToMavenLocal
```

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitearchive:0.0.1-SNAPSHOT")
        }
    }
}
```

There is a second artifact, `kitearchive-io`. It is four small adapters and it
does not build for JS, so read [The `-io` artifact](#the--io-artifact) before you
add it.

## Compress and decompress

```kotlin
KiteArchive.gzip.compress(bytes)      // gzip member
KiteArchive.zlib.compress(bytes)      // zlib stream — PNG IDAT, PDF FlateDecode
KiteArchive.deflate.compress(bytes)   // bare RFC 1951
KiteArchive.store.compress(bytes)     // identity

val lz4 = KiteArchive.codec(CodecId.LZ4)      // null for an unimplemented id
val out = lz4!!.decompress(frameBytes)
```

| Codec | Decode | Encode |
| --- | --- | --- |
| store | yes | yes |
| DEFLATE (RFC 1951) | yes | yes — fixed and dynamic Huffman, whichever is smaller |
| zlib (RFC 1950) | yes | yes |
| gzip (RFC 1952) | first member only | yes |
| LZ4 (frame and block) | yes | yes |
| Snappy (raw block) | yes | valid output, but it does not compress |

`gzip`, `zlib`, `deflate` and `store` have named accessors on `KiteArchive`; LZ4
and Snappy are reached through `KiteArchive.codec(id)`.

`CodecId` also carries entries for bzip2, LZMA, LZMA2, xz, zstd, brotli and PPMd.
None of those have an implementation. `KiteArchive.codec(...)` returns `null` for
them, and a ZIP entry that uses one lists fine but throws on read.

Every `Codec.decompress` takes an `ArchiveLimits`, defaulting to
`ArchiveLimits.DEFAULT`. Read [Limits](#limits) for what it actually enforces — it
is less than the field list implies. The lower-level `Gzip.decompress` and
`Zlib.decompress` do not take an `ArchiveLimits` at all; they take a `Long` cap.

## Read an archive

```kotlin
val reader = KiteArchive.open(ByteArrayRandomAccessSource(bytes))
reader.entries()          // parsed from the header or central directory only
reader.read(entry)        // decode one entry
```

`open` sniffs the format and returns a `ZipReader`, `TarReader`, `ArReader` or
`CpioReader`; anything else throws `UnsupportedOperationException`. `entries()`
does not touch payload bytes, so listing a large ZIP is cheap, and `read` seeks
to one local header for a single-entry extract.

| Format | Read | Write |
| --- | --- | --- |
| ZIP and ZIP64 | yes — STORE and DEFLATE | yes — STORE and DEFLATE |
| TAR (v7, ustar, GNU) | yes | no |
| ar (GNU) | yes | no |
| cpio (newc, odc) | yes | no |

`RandomAccessSource` is the seek-and-read contract the readers work against.
`ByteArrayRandomAccessSource` is the only implementation in the library, so the
whole archive has to be in memory before you can open it.

## Write a ZIP

```kotlin
val bytes = ZipWriter.write(
    listOf(
        ZipWriter.FileSpec("readme.txt", text, CodecId.STORE),
        ZipWriter.FileSpec("data.bin", payload, CodecId.DEFLATE),
    ),
)
```

`ZipWriter` is the only writer in the library and it is not reachable from the
`KiteArchive` object — import it directly. It emits ZIP64 records only when they
are needed, meaning more than 65,535 entries or a size or offset past 4 GiB, so
ordinary archives stay classic.

## Sanitize entry names before writing them

`ZipReader`, `TarReader`, `ArReader` and `CpioReader` return `ArchiveEntry.name`
exactly as it was stored, and none of them validates it. A name of
`../../etc/passwd` or `/etc/passwd` comes back verbatim. Joining that to an
output directory is the Zip-Slip vulnerability, and the library will not stop you.

`PathGuard` is the check, and it only runs if you call it:

```kotlin
val safe = PathGuard.sanitize(entry.name)     // throws ArchivePathException
if (PathGuard.isSafe(entry.name)) { /* ... */ }
```

`sanitize` rejects empty names, control characters and NUL, absolute paths,
Windows drive-letter paths and any `..` segment; it normalises backslashes to
forward slashes and collapses `.` segments, returning a relative path. It has no
symlink logic. Nothing in the library writes to disk, so link-escape on extract
is entirely yours to handle.

## Checksums

```kotlin
Crc32.of(bytes)        // 0xCBF43926 for "123456789"
Crc32c.of(bytes)
Adler32.of(bytes)
Crc64.of(bytes)        // CRC-64/XZ
XxHash32.hash(bytes)
```

`Crc32`, `Crc32c`, `Adler32` and `Crc64` are classes implementing `Checksum`, so
they also work incrementally with `update` / `value` / `reset`. `XxHash32` is
one-shot only.

## Sniff a format

```kotlin
KiteArchive.sniff(header)     // ArchiveFormat.ZIP, TAR, GZIP, ...
KiteArchive.sniff(source)     // reads the first 512 bytes of a RandomAccessSource
```

Recognises ZIP, TAR (ustar magic at offset 257), ar, cpio, gzip, zlib, bzip2, xz,
zstd, LZ4, 7z and RAR. Snappy and brotli have no magic bytes and are never
detected. Sniffing is not decoding — xz, zstd, bzip2, 7z and RAR are recognised
here and have no implementation behind them.

## Targets

The two artifacts do not build for the same targets. `kitearchive-io` has no JS
target, so adding both to one `commonMain` breaks a project that builds for JS.

| Target | `kitearchive` | `kitearchive-io` |
| --- | --- | --- |
| Android, minSdk 21, compileSdk 36 | yes | yes |
| `jvm`, JDK 21 toolchain | yes | yes |
| `iosArm64`, `iosSimulatorArm64`, `iosX64` | yes | yes |
| `js(IR)`, browser and Node | yes | no |

Neither artifact builds for `wasmJs`, macOS, Linux or mingw.

## The `-io` artifact

`kitearchive-io` is one common source file with four functions:

```kotlin
fun RawSource.readAllBytes(): ByteArray
fun RawSource.toRandomAccessSource(): RandomAccessSource
fun Codec.compress(source: RawSource): ByteArray
fun Codec.decompress(source: RawSource, limits: ArchiveLimits): ByteArray
```

There is no `RawSink` bridge, no `FileHandle` and no seeking —
`toRandomAccessSource()` drains the entire source into a `ByteArray` and wraps
that. If you already hold a kotlinx-io `RawSource` these save you a
`readByteArray()` call. If you expected a seekable file handle so a large ZIP
could be browsed without loading it, that does not exist yet. Writing the four
adapters yourself is cheaper than taking a second artifact and a kotlinx-io
dependency for them.

## Limits

- Whole-array only. Every entry point takes a `ByteArray` and returns a
  `ByteArray`, and the only `RandomAccessSource` is backed by a `ByteArray`. A
  2 GB archive needs 2 GB of heap plus room for the output. There is no streaming
  or suspend API.
- gzip and zlib do not verify their trailers on decode. The CRC-32 and ISIZE, and
  the Adler-32, are written on compress and ignored on decompress — inflate stops
  at the final DEFLATE block and never reads them, so a corrupt payload that still
  inflates comes back silently wrong. ZIP entries are CRC-checked, because the
  value is in the central directory.
- The decompression-bomb size check for DEFLATE, gzip and zlib runs after
  inflation completes, so a bomb is fully in memory by the time the limit throws.
  LZ4 is the only codec that checks per block.
- `Gzip.decompress` and `Zlib.decompress` cap output at 4 MiB when called
  directly, while `ArchiveLimits.DEFAULT` allows 2 GiB. `Gzip.decompress(x)` and
  `KiteArchive.gzip.decompress(x)` therefore behave differently on the same input.
- `PathGuard` is not wired into any reader. Path safety is the caller's job; see
  [Sanitize entry names before writing them](#sanitize-entry-names-before-writing-them).
- Of `ArchiveLimits`, only `checkOutputSize` and `checkRatio` are ever called.
  `maxEntries` and `maxNestingDepth` are read by nothing, and `checkRatio` is
  called only by `ZipReader`.
- `ArchiveEntry.size` is a `Long`, but every reader narrows it to `Int` at the
  point of reading, so an entry over 2 GiB fails regardless of the ZIP64 support
  on the directory side.
- gzip decodes the first member only. A multi-member `.gz` silently loses
  everything after the first member.
- TAR skips pax `x` and `g` extended headers and does not handle sparse files. ar
  does not handle BSD `#1/len` long names. cpio does not handle the old binary
  dialect.
- The LZ4 frame decoder skips the optional block and content xxHash32 checksums
  rather than verifying them.
- Snappy's encoder emits literals only, with no back-reference matching. The
  output is a valid Snappy block and slightly larger than the input.
- The public API is not frozen. There is no explicit API mode and no committed
  ABI dump, and `Codec` and `ArchiveReader` will change when a streaming API
  arrives.

## Testing

58 tests. `commonTest` runs on every target and covers DEFLATE round-trips,
dynamic Huffman on skewed alphabets, gzip and zlib decoding of vectors emitted by
CPython, a ZIP produced by CPython `zipfile`, an LZ4 frame produced by the `lz4`
CLI, Snappy, ar and cpio hand vectors, magic sniffing, checksum check values and
`PathGuard`.

Two JVM-only suites do the interop that matters. `ZlibInteropTest` feeds our raw
DEFLATE, zlib and gzip output to `java.util.zip.Inflater` and `GZIPInputStream`,
which validate the Adler-32 and CRC-32/ISIZE trailers our own decoder skips —
that is what caught a signed-`Int` Adler-32 overflow. `ZipInteropTest` round-trips
ZIPs both directions against `java.util.zip`, including a 70,000-entry ZIP64
archive.

Not covered: xxHash32 has no check-value test; it is exercised only as the LZ4
frame header byte.

## Building

```bash
./gradlew :kitearchive:jvmTest         # core test suite
./gradlew :kitearchive-io:jvmTest      # kotlinx-io adapter tests
./gradlew build                        # all modules, all targets
```

Requires JDK 21 and the Android SDK (`local.properties` → `sdk.dir`).

## Status

[PORTING_STATUS.md](PORTING_STATUS.md) tracks what is implemented per format,
what is next, and the feasibility or licensing call on the formats that are not.

## License

Apache-2.0. See [LICENSE](LICENSE).

KiteArchive is an independent, clean-room reimplementation. The inflate path
derives from Mark Adler's public-domain `puff`. The permissively-licensed
reference sources consulted during porting are listed in [NOTICE](NOTICE) and are
not redistributed. RAR, ACE and StuffIt are out of scope for licensing reasons;
KiteArchive will never create RAR archives.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage),
[KiteQR](https://github.com/yuroyami/KiteQR).
