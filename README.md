# ArchiveKodec

Compression and archive formats in pure Kotlin, for Kotlin Multiplatform. Gunzip
a buffer or read a ZIP on iOS and in the browser, as well as on the JVM, from one
shared codebase.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/archivekodec)](https://central.sonatype.com/artifact/io.github.yuroyami/archivekodec)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-Android%20%7C%20iOS%20%7C%20JVM%20%7C%20JS-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/ArchiveKodec/)** · an overview with
examples, plus the generated API reference.

## What you get

`java.util.zip` exists on the JVM and Android and nowhere else. A Kotlin
Multiplatform project that reads or writes archives usually adds a cinterop
binding on iOS and a JavaScript shim on web. That means a native build, a native
ABI, and a different code path per target. ArchiveKodec implements the formats in
`commonMain` instead. There is no JNI, no cinterop and no native binary. The core
artifact depends on `kotlin-stdlib` and nothing else.

This is what works today:

| Area | What is implemented |
| --- | --- |
| Codecs | DEFLATE both directions, gzip and zlib framing, LZ4 both directions, a Snappy decoder |
| Archives | ZIP read and write including ZIP64, plus readers for TAR, ar and cpio |
| Checksums | CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32 |
| Detection | A sniffer that names a format from its first bytes |

Two things apply to every entry point. First, everything is whole-array. You
pass a `ByteArray` and get a `ByteArray` back, so the whole payload stays in
memory, and there is no streaming API. Second, the readers do not sanitize entry
names. You must call `PathGuard` yourself before you use a name as a path.

```kotlin
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.security.PathGuard

val gz = ArchiveKodec.gzip.compress("hello".encodeToByteArray())
val back = ArchiveKodec.gzip.decompress(gz)

val zipBytes = ZipWriter.write(
    listOf(ZipWriter.FileSpec("hello.txt", "Hello!".encodeToByteArray(), CodecId.STORE)),
)

val reader = ArchiveKodec.open(ByteArrayRandomAccessSource(zipBytes))
for (entry in reader.entries()) {
    // The reader returns names unchanged. Sanitize one before you use it as a path.
    val safeName = PathGuard.sanitize(entry.name)
    val content = reader.read(entry)
    println("$safeName (${content.size} B)")
}
```

## Install

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:archivekodec:0.2.0")
        }
    }
}
```

Versions before 0.2.0 use older coordinates. 0.1.0 is `io.github.yuroyami:kitearchive`.

There is a second artifact, `archivekodec-io`. It holds four small adapters and it
does not build for JS. Read [The `-io` artifact](#the--io-artifact) before you
add it.

## Compress and decompress

```kotlin
ArchiveKodec.gzip.compress(bytes)      // gzip member
ArchiveKodec.zlib.compress(bytes)      // zlib stream: PNG IDAT, PDF FlateDecode
ArchiveKodec.deflate.compress(bytes)   // bare RFC 1951
ArchiveKodec.store.compress(bytes)     // identity

val lz4 = ArchiveKodec.codec(CodecId.LZ4)      // null for an unimplemented id
val out = lz4!!.decompress(frameBytes)
```

| Codec | Decode | Encode |
| --- | --- | --- |
| store | yes | yes |
| DEFLATE (RFC 1951) | yes | yes (fixed and dynamic Huffman, whichever is smaller) |
| zlib (RFC 1950) | yes | yes |
| gzip (RFC 1952) | first member only | yes |
| LZ4 (frame and block) | yes | yes |
| Snappy (raw block) | yes | valid output, but it does not compress |

`gzip`, `zlib`, `deflate` and `store` have named accessors on `ArchiveKodec`. You
reach LZ4 and Snappy through `ArchiveKodec.codec(id)`.

`CodecId` also carries entries for bzip2, LZMA, LZMA2, xz, zstd, brotli and PPMd.
None of those have an implementation. `ArchiveKodec.codec(...)` returns `null` for
them. A ZIP entry that uses one of them appears in the entry list, but reading it
throws.

Every `Codec.decompress` takes an `ArchiveLimits`, defaulting to
`ArchiveLimits.DEFAULT`. Read [Limits](#limits) to see what it enforces. It
enforces less than the field list suggests. The lower-level `Gzip.decompress` and
`Zlib.decompress` do not take an `ArchiveLimits`. They take a `Long` cap instead.

## Read an archive

```kotlin
val reader = ArchiveKodec.open(ByteArrayRandomAccessSource(bytes))
reader.entries()          // parsed from the header or central directory only
reader.read(entry)        // decode one entry
```

`open` sniffs the format and returns a `ZipReader`, `TarReader`, `ArReader` or
`CpioReader`. Anything else throws `UnsupportedOperationException`. `entries()`
does not touch payload bytes, so listing a large ZIP is fast. `read` seeks to one
local header for a single-entry extract.

| Format | Read | Write |
| --- | --- | --- |
| ZIP and ZIP64 | yes (STORE and DEFLATE) | yes (STORE and DEFLATE) |
| TAR (v7, ustar, GNU) | yes | no |
| ar (GNU) | yes | no |
| cpio (newc, odc) | yes | no |

The readers read through the `RandomAccessSource` interface, which supports seek
and read. `ByteArrayRandomAccessSource` is the only implementation in the
library. The whole archive must therefore be in memory before you can open it.

## Write a ZIP

```kotlin
val bytes = ZipWriter.write(
    listOf(
        ZipWriter.FileSpec("readme.txt", text, CodecId.STORE),
        ZipWriter.FileSpec("data.bin", payload, CodecId.DEFLATE),
    ),
)
```

`ZipWriter` is the only writer in the library. It is not reachable from the
`ArchiveKodec` object, so import it directly. It emits ZIP64 records only when
they are needed, meaning more than 65,535 entries or a size or offset past 4 GiB.
Ordinary archives therefore stay in the classic ZIP layout.

## Sanitize entry names before writing them

`ZipReader`, `TarReader`, `ArReader` and `CpioReader` return `ArchiveEntry.name`
exactly as the archive stored it, and none of them validates it. A name of
`../../etc/passwd` or `/etc/passwd` arrives unchanged.

Joining such a name to an output directory is the Zip-Slip vulnerability.
Zip-Slip means an archive entry escapes the folder you meant to extract into, and
overwrites a file somewhere else on disk. The library will not stop you.

`PathGuard` is the check, and it only runs when you call it:

```kotlin
val safe = PathGuard.sanitize(entry.name)     // throws ArchivePathException
if (PathGuard.isSafe(entry.name)) { /* ... */ }
```

`sanitize` rejects empty names, control characters and NUL, absolute paths,
Windows drive-letter paths and any `..` segment. It normalizes backslashes to
forward slashes, collapses `.` segments, and returns a relative path. It has no
symlink logic. Nothing in the library writes to disk, so you must check symlinks
yourself when you extract. A symlink entry can point outside your output folder.

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
ArchiveKodec.sniff(header)     // ArchiveFormat.ZIP, TAR, GZIP, ...
ArchiveKodec.sniff(source)     // reads the first 512 bytes of a RandomAccessSource
```

The sniffer recognizes ZIP, TAR (ustar magic at offset 257), ar, cpio, gzip,
zlib, bzip2, xz, zstd, LZ4, 7z and RAR. Snappy and brotli have no magic bytes, so
it never detects them.

Sniffing is not decoding. The sniffer names xz, zstd, bzip2, 7z and RAR, and
ArchiveKodec cannot decode any of those five formats.

## Targets

The two artifacts do not build for the same targets. `archivekodec-io` has no JS
target, so adding both to one `commonMain` breaks a project that builds for JS.

| Target | `archivekodec` | `archivekodec-io` |
| --- | --- | --- |
| Android, minSdk 21, compileSdk 36 | yes | yes |
| `jvm`, JDK 21 toolchain | yes | yes |
| `iosArm64`, `iosSimulatorArm64`, `iosX64` | yes | yes |
| `js(IR)`, browser and Node | yes | no |

Neither artifact builds for `wasmJs`, macOS, Linux or mingw.

## The `-io` artifact

`archivekodec-io` is one common source file with four functions:

```kotlin
fun RawSource.readAllBytes(): ByteArray
fun RawSource.toRandomAccessSource(): RandomAccessSource
fun Codec.compress(source: RawSource): ByteArray
fun Codec.decompress(source: RawSource, limits: ArchiveLimits): ByteArray
```

Four facts about this artifact:

- There is no `RawSink` bridge, no `FileHandle` and no seeking.
- `toRandomAccessSource()` drains the entire source into a `ByteArray` and wraps
  that.
- These functions save you a `readByteArray()` call when you already hold a
  kotlinx-io `RawSource`. That is all they do.
- You cannot browse a large ZIP without loading all of it, because there is no
  seekable file handle.

Writing these four adapters yourself is usually less work than adding a second
artifact and a kotlinx-io dependency.

## Limits

- Whole-array only. Every entry point takes a `ByteArray` and returns a
  `ByteArray`, and the only `RandomAccessSource` is backed by a `ByteArray`. A
  2 GB archive needs 2 GB of heap plus room for the output. There is no streaming
  or suspend API.
- gzip and zlib do not verify their trailers on decode. The library writes the
  CRC-32 and ISIZE, and the Adler-32, when it compresses, then ignores them when
  it decompresses. Inflate stops at the final DEFLATE block and never reads them.
  A corrupt payload that still inflates therefore returns wrong data, with no
  error. The ZIP reader does check each entry's CRC, because that value sits in
  the central directory.
- The decompression-bomb check runs late for DEFLATE, gzip and zlib. A
  decompression bomb is a small file that expands to a very large one. The size
  check for those three codecs runs after inflation completes, so the bomb is
  fully in memory by the time the limit throws. LZ4 is the only codec that checks
  per block.
- `Gzip.decompress` and `Zlib.decompress` cap output at 4 MiB when called
  directly, while `ArchiveLimits.DEFAULT` allows 2 GiB. `Gzip.decompress(x)` and
  `ArchiveKodec.gzip.decompress(x)` therefore behave differently on the same input.
- `PathGuard` is not wired into any reader. Path safety is the caller's
  responsibility. See
  [Sanitize entry names before writing them](#sanitize-entry-names-before-writing-them).
- Of `ArchiveLimits`, only `checkOutputSize` and `checkRatio` are ever called.
  Nothing reads `maxEntries` or `maxNestingDepth`, and only `ZipReader` calls
  `checkRatio`.
- `ArchiveEntry.size` is a `Long`, but every reader narrows it to `Int` at the
  point of reading. An entry over 2 GiB therefore fails, whatever the ZIP64
  support on the directory side allows.
- gzip decodes the first member only. A multi-member `.gz` silently loses
  everything after the first member.
- TAR skips pax `x` and `g` extended headers and does not handle sparse files. ar
  does not handle BSD `#1/len` long names. cpio does not handle the old binary
  dialect.
- The LZ4 frame decoder skips the optional block and content xxHash32 checksums
  instead of verifying them.
- Snappy's encoder emits literals only, with no back-reference matching. The
  output is a valid Snappy block, and slightly larger than the input.
- The public API is not frozen. There is no explicit API mode and no committed
  ABI dump. `Codec` and `ArchiveReader` will change when a streaming API arrives.

## Testing

58 tests. `commonTest` runs on every target, and covers:

- DEFLATE round-trips.
- Dynamic Huffman on skewed alphabets.
- gzip and zlib decoding of vectors emitted by CPython.
- A ZIP produced by CPython `zipfile`.
- An LZ4 frame produced by the `lz4` CLI.
- Snappy, ar and cpio hand-written test vectors.
- Magic sniffing, checksum check values and `PathGuard`.

Two JVM-only suites test interoperability. `ZlibInteropTest` feeds our raw
DEFLATE, zlib and gzip output to `java.util.zip.Inflater` and `GZIPInputStream`.
Those two classes validate the Adler-32 and CRC-32/ISIZE trailers that our own
decoder skips. `ZipInteropTest` round-trips ZIPs in both directions against
`java.util.zip`, including a 70,000-entry ZIP64 archive.

Not covered: xxHash32 has no check-value test. The suite exercises it only as the
LZ4 frame header byte.

## Building

```bash
./gradlew :archivekodec:jvmTest         # core test suite
./gradlew :archivekodec-io:jvmTest      # kotlinx-io adapter tests
./gradlew build                        # all modules, all targets
```

You need JDK 21 and the Android SDK. Point `sdk.dir` in `local.properties` at
your SDK install.

## What is planned

The table at the top lists what works today. Everything still missing has its own
issue, so the tracker is the current answer instead of a file that goes stale
between releases.

- [Missing codecs](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Amore-codecs): bzip2, zstd, brotli, PPMd, and a Snappy
  encoder that actually compresses.
- [LZMA, xz and 7z](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Alzma-family).
- [Streaming and extraction](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Astreaming-api): reading from a file, and
  extracting without holding the whole archive in memory.
- [Security hardening](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Asecurity-hardening): one issue per guard listed under
  Limits.
- [Writers](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Acontainer-writers) for TAR, ar and cpio.
- [More containers](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Amore-containers): ISO 9660, SquashFS, CAB and WIM.

RAR, ACE, StuffIt and ALZ stay out of scope. See License below.

## License

Apache-2.0. See [LICENSE](LICENSE).

ArchiveKodec is an independent, clean-room reimplementation. The inflate path
derives from Mark Adler's public-domain `puff`. [NOTICE](NOTICE) lists the
permissively licensed reference sources, and this repository does not
redistribute them. RAR is out of scope for licensing reasons, and ArchiveKodec
will never create RAR archives. ACE, StuffIt and ALZ are proprietary with no
clean reference to work from, so they are out of scope too.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[ImageKodec](https://github.com/yuroyami/ImageKodec),
[KiteQR](https://github.com/yuroyami/KiteQR).
