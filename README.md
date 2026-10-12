# ArchiveKodec

Compression and archive formats in pure Kotlin, for Kotlin Multiplatform. Gunzip
a buffer or read a ZIP on iOS and in the browser, as well as on the JVM, from one
shared codebase.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the
direction and review of its author.</sub>

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/archivekodec)](https://central.sonatype.com/artifact/io.github.yuroyami/archivekodec)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-Android%20%7C%20Apple%20%7C%20JVM%20%7C%20JS%20%7C%20Wasm%20%7C%20Linux%20%7C%20Windows-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/ArchiveKodec/)** · an overview with
examples, plus the generated API reference.

## Install

The artifacts are on Maven Central under the group `io.github.yuroyami`. Versions
before 0.2.0 use older coordinates: 0.1.0 is `io.github.yuroyami:kitearchive`.

| Artifact | Coordinates | What it holds |
| --- | --- | --- |
| Core | `io.github.yuroyami:archivekodec:0.2.0` | Codecs, archive readers, ZIP, TAR, ar and cpio writers, checksums, the sniffer and the safety checks. Depends on `kotlin-stdlib` only |
| IO adapters | `io.github.yuroyami:archivekodec-io:0.2.0` | `FileRandomAccessSource`, a seekable file source, and four adapters over kotlinx-io `RawSource`. Adds `kotlinx-io-core` and the core as API dependencies |
| Crypto | `io.github.yuroyami:archivekodec-crypto:0.2.0` | `CryptographyKotlinProvider`, the platform AES, HMAC and PBKDF2 that WinZip AES and 7z AES need. Adds cryptography-kotlin and the core as API dependencies |

The core and the IO adapters build for every target in [Targets](#targets). The crypto
artifact builds for all of them except JS, wasmJs and wasmWasi; see
[Encrypted archives](#encrypted-archives).

Kotlin Multiplatform, in the shared source set:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:archivekodec:0.2.0")
            // Only if you already use kotlinx-io. See "The -io artifact".
            implementation("io.github.yuroyami:archivekodec-io:0.2.0")
        }
        // Only to read or write AES-encrypted archives; not available on JS or Wasm.
        jvmMain.dependencies {
            implementation("io.github.yuroyami:archivekodec-crypto:0.2.0")
        }
    }
}
```

A [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain) project lists the
same coordinates in its `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:archivekodec:0.2.0
  - io.github.yuroyami:archivekodec-io:0.2.0

dependencies@jvm:
  - io.github.yuroyami:archivekodec-crypto:0.2.0
```

A JVM or Android project that is not multiplatform uses the same coordinates.
Gradle reads the published module metadata and picks the JVM or Android variant:

```kotlin
dependencies {
    implementation("io.github.yuroyami:archivekodec:0.2.0")
}
```

Maven does not read Gradle module metadata. The root `archivekodec` POM only
lists `kotlin-stdlib`, so a Maven project depends on the platform artifact
instead (`archivekodec-io-jvm` for the adapters):

```xml
<dependency>
  <groupId>io.github.yuroyami</groupId>
  <artifactId>archivekodec-jvm</artifactId>
  <version>0.2.0</version>
</dependency>
```

Requirements:

- **Kotlin 2.1 or newer** in the consuming project. The libraries are compiled with
  Kotlin 2.4 at language and API version 2.2, so their metadata is readable by a
  compiler one minor version older and they call no standard-library API newer
  than 2.2. Kotlin/Native and Kotlin/JS klibs follow Kotlin's own klib rules, which
  can be stricter.
- **Java 8 or newer** at run time on the JVM. The jars contain Java 8 class files
  (major version 52), compiled against the Java 8 API with `-Xjdk-release=8`,
  the same floor as `kotlin-stdlib` and kotlinx-io. The build of the libraries
  downloads its own JDK 21.
- **Android minSdk 21** or newer.
- JPMS module names: `io.github.yuroyami.archivekodec` and
  `io.github.yuroyami.archivekodec.io`, from a `module-info.class` in each jar.
  `archivekodec-crypto` has no declared name, so Java takes `archivekodec.crypto.jvm`
  from the file name.

Callers outside Kotlin:

- **Java** uses the JVM artifact directly, with static utilities such as
  `Gzip.compress(bytes)` and `ArchiveKodec.open(source)`, overloads such as
  `reader.read(entry)`, and fields such as `ArchiveLimits.DEFAULT`. Throwing
  methods declare their checked archive exceptions. See the [Java example](#java-example).
- **Swift** imports the `ArchiveKodecKit` XCFramework (or `ArchiveKodecIo`, which
  adds the kotlinx-io adapters) through Swift Package Manager, and passes `Data`
  in and out. See [Swift](#swift).
- **JavaScript and TypeScript** call a small exported facade, with TypeScript
  declarations. It is not on npm yet; build it from this repository. See
  [JavaScript and TypeScript](#javascript-and-typescript).

### Java example

```java
import io.github.yuroyami.archivekodec.ArchiveKodec;
import io.github.yuroyami.archivekodec.archive.ArchiveEntry;
import io.github.yuroyami.archivekodec.archive.ArchiveReader;
import io.github.yuroyami.archivekodec.archive.ArchiveReaders;
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter;
import io.github.yuroyami.archivekodec.checksum.Crc32;
import io.github.yuroyami.archivekodec.security.ArchiveLimits;
import io.github.yuroyami.archivekodec.wrapper.Gzip;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class Example {
    public static void main(String[] args) throws Exception {
        byte[] original = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] restored = Gzip.decompress(Gzip.compress(original));
        long crc = Crc32.of(restored);

        ArchiveLimits.Builder settings = new ArchiveLimits.Builder();
        settings.setMaxUncompressedBytes(1024 * 1024);
        settings.setMaxEntries(100);
        byte[] zip = ZipWriter.write(Collections.singletonList(
            new ZipWriter.FileSpec("hello.txt", restored)));
        ArchiveReader reader = ArchiveKodec.open(zip, settings.build());
        try (AutoCloseable resource = ArchiveReaders.asAutoCloseable(reader)) {
            ArchiveEntry entry = reader.entries().get(0);
            byte[] content = reader.read(entry); // Uses this reader's limits.
            System.out.println(new String(content, StandardCharsets.UTF_8));
        }
    }
}
```

Codec instances are available through `Codecs.GZIP` or `ArchiveKodec.getGzip()`;
`codec.decompress(bytes)` uses `ArchiveLimits.DEFAULT`. Incremental checksums
support `checksum.update(bytes)` and `checksum.update(bytes, offset)`. Top-level
helpers are `RandomAccessSources.readFully(source, offset, length)`,
`ArchiveReaders.asAutoCloseable(reader)`, and, in the IO artifact,
`ArchiveKodecIo.readAllBytes(source)` and `ArchiveKodecIo.decompress(codec, source)`.

The unreleased Java API changes require recompiling JVM consumers. Object methods
became static, companion constants became fields, and the old utility classes
`RandomAccessSourceKt`, `ArchiveReaderKt` and `ArchiveKodecIoKt` became
`RandomAccessSources`, `ArchiveReaders` and `ArchiveKodecIo`. Update Java imports
and calls when upgrading. Kotlin named arguments and Swift file-class names are
unchanged by these JVM annotations.

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
| Codecs | DEFLATE, Deflate64, bzip2, LZMA, LZMA2, xz, zstd, brotli and PPMd both directions, gzip and zlib framing, LZ4 and Snappy both directions |
| Archives | ZIP read and write including ZIP64, GNU ar read and write, TAR read and write (regular files/directories), cpio read and write (newc/crc), 7z read, ISO 9660 read (Joliet, Rock Ridge), CAB read (stored, MSZIP, LZX), WIM read (XPRESS, LZX), and SquashFS 4.0 read (gzip, LZMA, LZO, xz, LZ4, zstd), and RAR read (RAR4 stored; RAR5 stored and compressed, including solid) |
| Checksums | CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32 |
| Detection | A sniffer that names a format from its first bytes |

Two things apply to every entry point. First, everything is whole-array. You
pass a `ByteArray` and get a `ByteArray` back, so the whole payload stays in
memory, and there is no streaming API. Second, readers refuse unsafe entry names
by default but keep the stored spelling. Join `PathGuard.sanitize(entry.name)`,
not the raw name, to an output directory.

```kotlin
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.security.PathGuard

val gz = ArchiveKodec.gzip.compress("hello".encodeToByteArray())
val back = ArchiveKodec.decompress(gz) // Detects the compressed stream format.

val zipBytes = ZipWriter.write(
    listOf(ZipWriter.FileSpec("hello.txt", "Hello!".encodeToByteArray(), ZipMethod.STORED)),
)

ArchiveKodec.open(zipBytes).use { reader ->
    for (entry in reader.entries()) {
        // Archives of the current directory may contain a root directory entry.
        if (PathGuard.isRootDirectory(entry)) continue
        // The reader returns names unchanged. Sanitize one before you use it as a path.
        val safeName = PathGuard.sanitize(entry.name)
        val content = reader.read(entry)
        println("$safeName (${content.size} B)")
    }
}
```

## Compress and decompress

```kotlin
ArchiveKodec.gzip.compress(bytes)      // gzip member
ArchiveKodec.zlib.compress(bytes)      // zlib stream: PNG IDAT, PDF FlateDecode
ArchiveKodec.deflate.compress(bytes)   // bare RFC 1951
ArchiveKodec.store.compress(bytes)     // identity

val out = ArchiveKodec.lz4.decompress(frameBytes)
val detected = ArchiveKodec.decompress(compressedBytes) // gzip, zlib, bzip2, xz, zstd, LZ4 or framed Snappy

ArchiveKodec.codec("lz4")                 // Same instance as ArchiveKodec.lz4
ArchiveKodec.codec(ArchiveFormat.GZIP)      // Same instance as ArchiveKodec.gzip
```

Decompressing into a `ByteSink` writes the output in chunks instead of returning one
array. DEFLATE, Deflate64, zlib and gzip stream, holding about 1 MiB of output at a time,
so the output can be larger than memory; other codecs decode in memory and then write.
The limits are checked before every write, so raise `maxUncompressedBytes` for outputs
above its 2 GiB default. `archivekodec-io` adds the same call for kotlinx-io sources and
sinks.

```kotlin
val written: Long = ArchiveKodec.gzip.decompress(gzBytes, ByteSink { bytes, offset, length ->
    file.write(bytes, offset, length)
}, ArchiveLimits { maxUncompressedBytes = 16L shl 30 })
```

| Codec | Decode | Encode |
| --- | --- | --- |
| store | yes | yes |
| DEFLATE (RFC 1951) | yes, with an optional preset dictionary | yes (zlib's levels 0 to 9 and strategies, 9 to 15 window bits, preset dictionaries; blocks of 16,384 symbols, each stored, fixed or dynamic, whichever is smallest) |
| Deflate64 (ZIP method 9) | yes (64 KiB window, long matches) | yes (DEFLATE-compatible codes, 32 KiB window) |
| bzip2 | yes (any level, concatenated streams, randomised blocks, CRC-checked) | yes (level 9; ZIP method 12 reads) |
| LZMA (`.lzma`) | yes (known size or end marker) | yes (greedy/lazy parser, no end marker) |
| LZMA2 (raw chunks) | yes | yes (LZMA chunks, stored when incompressible) |
| xz | yes (CRC-32, CRC-64, SHA-256, delta and x86 BCJ filters, index, concatenated streams; other BCJ refused) | yes (one LZMA2 block, CRC-64) |
| zstd (RFC 8878) | yes (all block, literal and table modes; skippable and concatenated frames; xxHash64 checked; dictionaries refused) | yes (predefined tables, raw literals) |
| brotli (RFC 7932) | yes (context modelling, block switching, static dictionary with all 121 transforms, metadata and uncompressed meta-blocks) | yes (one prefix code per category, no dictionary references) |
| PPMd var.I rev. 1 (ZIP method 98) | yes (restart and cut-off restore; freeze refused) | yes (order 6, up to 16 MiB of model memory) |
| zlib (RFC 1950) | yes, including FDICT with the dictionary passed in | yes, including FDICT |
| gzip (RFC 1952) | all members, with header/trailer integrity checks | yes |
| LZ4 (frame and block) | yes | yes (64 KiB to 4 MiB blocks, block and content checksums, content size, linked blocks) |
| Snappy (raw block) | yes | yes, with greedy back-reference matching |
| Snappy (framed stream) | yes, with CRC-32C validation | yes, with stored-chunk fallback |

Encoders take options. `DeflateOptions` (level 0 to 9, default 6; strategy; window bits;
preset dictionary) goes to `Deflate.encode`, `Zlib.compress`, `Gzip.compress` and
`ZipWriter.FileSpec`'s `deflateOptions`; `Lz4Options` (block size, checksums, content size,
linked blocks) goes to `Lz4.frameCompress`. `Codec.compress(data, options)` takes the
options class of that codec and rejects any other with `IllegalArgumentException`. Levels
follow zlib's table, and the output is within a few bytes of zlib's at the same level:

```kotlin
val fast = Deflate.encode(bytes, DeflateOptions { level = 1 })
val primed = Zlib.compress(bytes, DeflateOptions { dictionary = sharedDictionary })
val back = Zlib.decompress(primed, ArchiveLimits.DEFAULT, sharedDictionary)
val frame = Lz4.frameCompress(bytes, Lz4Options { blockSize = Lz4BlockSize.KB_64; contentChecksum = true })
```

Dynamic DEFLATE trees use binary package-merge to minimize weighted code length
within the 15-bit literal/distance and 7-bit code-length limits. The encoder checks
symbol coverage and code-space occupancy before selecting a table. Raw DEFLATE,
gzip, zlib and deflated ZIP all share this builder; compressed bytes are deterministic
for the same input, but may change when encoder behavior is corrected.

`gzip`, `zlib`, `deflate`, `store`, `lz4`, `snappy` and `snappyFramed` have non-null
accessors on `ArchiveKodec`; the corresponding `Codecs` fields are also available.
Lookups by id, format or case-insensitive codec name use that same registry and
return `null` for unavailable codecs. Names are codec identifiers, not HTTP aliases:
`deflate` means raw DEFLATE, while `zlib` selects its RFC 1950 wrapper.

`ArchiveKodec.decompress(bytes, limits)` detects gzip, zlib, LZ4 and framed Snappy
and decodes one framing layer. It keeps each codec's concatenation and integrity
rules. Containers and unsupported or unknown streams throw
`ArchiveUnsupportedException` naming the detected format. Raw DEFLATE, raw Snappy,
LZMA and brotli need an explicit codec because they cannot be reliably sniffed.

`CodecId` is an open immutable identifier: `CodecId("zstd") == CodecId.ZSTD`.
Names are normalized to lowercase ASCII; `name` and `displayName` return that
value. There is no `implemented` flag or exhaustive enum list. Ask the registry
with `byId(id) != null`, or inspect its `implemented` snapshot.

To supply an external decoder or replace a built-in engine, derive an immutable
registry and pass it to the ZIP reader:

```kotlin
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.CodecRegistry

fun readWithZstd(zipBytes: ByteArray, zstd: Codec): ByteArray {
    val codecs = CodecRegistry.DEFAULT.with(zstd, zipMethod = 93)
    return ZipReader(ByteArrayRandomAccessSource(zipBytes), codecs).use { reader ->
        reader.read(reader.entries().first())
    }
}
```

`with` returns a new registry and leaves the original and `DEFAULT` unchanged.
A replacement with the same id takes over all existing method bindings for that
id: `DEFAULT.with(myDeflate)` replaces method 8 when `myDeflate.id` equals
`CodecId.DEFLATE`. An explicit `zipMethod` can also bind a new id to a known or
private method number in `0..65535`.
Lookups by id, name, format and ZIP method all use the selected registry.
`ArchiveKodec` and `Codecs` convenience lookups always use `DEFAULT`.

Custom codecs must implement the registered ZIP method's exact payload framing,
keep their id stable, and enforce the supplied limits while decoding. The reader
decrypts and authenticates encrypted entries before a codec sees their data, rejects
inconsistent headers, and verifies output limits, size and CRC-32. Registry bindings are immutable; codec instances are shared and
retain their own thread-safety rules. Registration does not change sniffing or
add a compression method to `ZipWriter`.

Calling `Codec` and `ArchiveReader` is stable, but implementing them is not:
later releases may add members to these interfaces. Kotlin implementations must
opt in, and the compiler reports an error otherwise:

```kotlin
@OptIn(ExperimentalArchiveImplementation::class)
class ZstdCodec : Codec { /* ... */ }
```

Java and Swift do not check Kotlin opt-in markers, but the same caveat applies.
`RandomAccessSource` is not marked; implement it freely.

`CodecId.SNAPPY` and `Codecs.SNAPPY` select raw Snappy blocks.
`CodecId.SNAPPY_FRAMED` and `Codecs.SNAPPY_FRAMED` select standard `.sz` framing.
The low-level `Snappy` object also exposes `frameCompress` and `frameDecompress`.

Brotli's 120 KB static dictionary ships inside the core artifact as 58 KB of raw
DEFLATE. It is inflated the first time a stream references it, so a process that never
decodes brotli never pays for it.

PPMd comes in two variants. `Codecs.PPMD` is var.I revision 1 in ZIP's method 98 framing
(a two-byte header with the order, memory size and restore method, then an end marker),
and the 7z reader decodes 7z's var.H itself. Both allocate the model memory their header
names up front, as the reference decoders do; `ArchiveLimits.maxDecoderMemory` (default
256 MiB) caps it before anything is allocated.

Every `Codec.decompress` takes an `ArchiveLimits`, defaulting to
`ArchiveLimits.DEFAULT`. Read [Limits](#limits) to see what it enforces. It
enforces less than the field list suggests. Direct `Inflate.inflate`,
`Gzip.decompress` and `Zlib.decompress` use the same default and accept an explicit
`ArchiveLimits`. The old gzip/zlib `maximumSize: Long` overloads are deprecated
compatibility delegates; their default also comes from `ArchiveLimits.DEFAULT`.

```kotlin
val limits = ArchiveLimits {
    maxUncompressedBytes = 8L * 1024 * 1024
    maxCompressionRatio = 100
}
val decoded = Gzip.decompress(bytes, limits)
val tighter = limits.copy { maxUncompressedBytes = 1024 }
```

Policies are immutable snapshots. Configuration uses a fresh mutable builder;
reusing or retaining that builder cannot change a built policy. Each setting must
be nonnegative. Invalid settings throw `IllegalArgumentException` at build time;
zero keeps its existing meaning. Java and Swift can construct a zero-argument
`ArchiveLimits.Builder`, set its properties and call `build()` (`try` in Swift).
Kotlin callers can also use `ArchiveLimits()` for the defaults. The old field-list
constructor, data-class copy and destructuring functions have been removed for
the next minor release; rebuild callers and migrate to the builder or lambda API.

DEFLATE writes check the cumulative byte budget and whole-input compression ratio
before output growth or copying. A zero-byte cap accepts valid empty output.
Violations throw `DecompressionBombException`. gzip validates optional header
CRC-16, CRC-32 and ISIZE for each member, then concatenates all members under the
same budget. It accepts zero padding between/after members and rejects other
trailing bytes. zlib validates Adler-32 and requires exactly one complete stream;
raw DEFLATE also rejects bytes after its final block. Wrapper integrity failures
throw `ArchiveFormatException`. The gzip-to-zlib fallback uses the same checks.

### Read and write gzip header metadata

Use the format-specific `Gzip` API when you need member metadata:

```kotlin
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.GzipHeader

val header = GzipHeader {
    name = "report.txt"
    comment = "monthly report"
    mtimeEpochSeconds = 1_700_000_000L
    os = 3 // Unix
    hasHeaderCrc = true
}
val member = Gzip.compress(bytes, header)
val originalName = Gzip.readHeader(member)?.name
```

`Gzip.compress(bytes)` and `ArchiveKodec.gzip.compress(bytes)` retain their
reproducible default output: MTIME 0 (unavailable), OS 255 (unknown), XFL 0 and
no optional fields. `GzipHeader.Builder().build()` is also available, including
from Java; `header.copy { ... }` configures an independent snapshot. Names and
comments must be ISO 8859-1 text without NUL. Null omits the field; an empty value
emits an empty field. Supply an original basename when writing a name; names are
preserved literally and are not sanitized for filesystem use.

Timestamps must fit unsigned 32-bit seconds. OS and `extraFlags` must fit one
unsigned byte. `extra` holds up to 65,535 raw extra-field bytes, excluding XLEN;
the header owns a copy and its getter returns a copy. `isText` sets FTEXT without
converting the payload. `extraFlags` is informational XFL metadata and does not
select a compression level. `hasHeaderCrc` writes FHCRC as the low 16 bits of the
header's CRC-32; the reader verifies it.

`readHeader` inspects only the first member and needs only its complete header.
It returns null when the first two bytes are not gzip magic, and throws
`ArchiveFormatException` for recognized but malformed/truncated headers or a bad
header CRC. It does not decode or validate the payload, trailer or later members,
and does not accept the zlib fallback. Use `Gzip.decompress` for full validation.

## Expected failures and Apple consumers

Expected archive failures share the `ArchiveException` base. Its `offset` and
`entryName` identify source context when known, and `cause` retains the original
failure. Entry reads rebase codec offsets to the archive source. Categories include
`ArchiveFormatException`, `ArchiveUnsupportedException`, `ArchiveIOException`,
`ArchiveClosedException`, `ArchiveSizeException`, `InflateException`, `DecompressionBombException`, and
`ArchivePathException`. Buffer-slice mistakes on the source/builder APIs retain
their documented `IllegalArgumentException` contract.

Throwing core and adapter entry points export `NSError` parameters to Objective-C
and `throws` to Swift. Java methods declare the same checked archive exception
contract, so Java callers must catch or declare those failures. The IO framework exports the core and kotlinx-io types
needed by its public methods; import `ArchiveKodecIo` when using that framework.

```swift
import Foundation
import ArchiveKodecKit

do {
    let output = try Gzip.shared.decompress(input: payload)
    print(output.size)
} catch {
    let failure = (error as NSError).kotlinException as? ArchiveException
    print(failure?.message ?? String(describing: error))
    print(failure?.entryName as Any, failure?.offset as Any)
}
```

Custom `RandomAccessSource` and `Codec` implementations must report expected
failures as `ArchiveException` subclasses. Recoverable source IO failures in the
kotlinx-io adapter become `ArchiveIOException`. The bridge covers expected errors;
resource exhaustion and implementation bugs are outside that contract.

## Swift

Swift apps use one of two XCFrameworks, built for every Apple target in the
[target table](#targets):

- `ArchiveKodecKit` holds the library: `import ArchiveKodecKit`. It is not named
  `ArchiveKodec`, because Swift would then hide the module behind the `ArchiveKodec`
  type, and `ArchiveKodec.Crc32` would not resolve.
- `ArchiveKodecIo` holds the library plus the [`-io` artifact](#the--io-artifact):
  `import ArchiveKodecIo`.

Link one, not both: each carries its own Kotlin runtime.

They are not published yet. Build both on macOS from a clone of this repository:

```bash
./kotlin build
python3 scripts/apple_frameworks.py
python3 integration-tests/swift-package/package.py
```

`./kotlin build` compiles the klibs. `apple_frameworks.py` links the release
frameworks and XCFrameworks from them. `package.py` zips the XCFrameworks and writes a
Swift package to `build/swiftpm/ArchiveKodec/`. Add that folder to Xcode as a local
package, or depend on it from a `Package.swift`:

```swift
.package(path: "path/to/ArchiveKodec/build/swiftpm/ArchiveKodec"),
// and in the target:
.product(name: "ArchiveKodecKit", package: "ArchiveKodec"),
```

`package.py --url https://example.com/downloads/` writes a package for hosting
instead. Each zip becomes a remote binary target pinned by its
SHA-256, and `checksums.txt` lists the sums. Upload both zips to that address.

`ArchiveKodec.shared` takes and returns `Data`, so payloads need no `KotlinByteArray`:

```swift
import Foundation
import ArchiveKodecKit

let kodec = ArchiveKodec.shared
let packed = try kodec.compress(codec: kodec.gzip, data: Data("hello".utf8))
let unpacked = try kodec.decompress(codec: kodec.gzip, data: packed)    // or decompress(data:) to detect the format

let reader = try kodec.open(data: zipData)                             // Data; the reader keeps its own copy
defer { try? reader.close() }
for entry in try reader.entries() where !entry.isDirectory {
    let contents: Data = try kodec.read(reader: reader, entry: entry)  // under the reader's limits
    print(entry.name, contents.count)
}
```

For other APIs, `kodec.bytes(data:)` turns `Data` into a `KotlinByteArray`, and
`kodec.data(bytes:)` turns one back. Each makes one copy. Kotlin default arguments do
not reach Swift, so the library has explicit overloads without them, such as
`reader.read(entry:)` and `codec.decompress(data:)`. Those use `ArchiveLimits.DEFAULT`,
or the reader's limits. Failures arrive as Swift errors; see
[Expected failures and Apple consumers](#expected-failures-and-apple-consumers).

`swift test --package-path integration-tests/swift-package` runs a Swift-only package
with XCTest cases for the calls above. It depends on the generated package, so run the
three commands above first.

## Read an archive

```kotlin
val reader = ArchiveKodec.open(bytes)
reader.entries()          // parsed from the header or central directory only
reader.read(entry)        // decode one entry
```

`open` sniffs the format and returns a `ZipReader`, `TarReader`, `ArReader`,
`CpioReader` or `SevenZipReader`. Byte-array overloads borrow plain archive bytes without copying;
keep those bytes unchanged until the reader closes. Gzip input is fully decoded
into an independent snapshot before the contained archive is opened. One gzip
layer is supported, so `.tar.gz` and `.zip.gz` open directly:

```kotlin
ArchiveKodec.open(tarGzipBytes, limits).use { reader ->
    val entries = reader.entries()
}
```

The same policy bounds gzip output, entry listing and each entry read separately;
it is not a cumulative extraction budget. Gzip decode failures use offsets in the
compressed input; subsequent reader offsets refer to the unwrapped archive.
For a `RandomAccessSource`, ownership transfers only after a successful open,
and closing the returned reader also closes the original compressed source.
Other compression wrappers require explicit decoding before opening the container.

When the header is unknown, all `open` overloads search the last
65,557 bytes for a ZIP end record with a complete EOF-terminated comment. This
opens zipapps and other prefixed ZIPs; recognized header formats retain priority.
An unknown source throws `ArchiveFormatException`, and a recognized format
without a reader throws `ArchiveUnsupportedException`. `entries()`
does not decompress entries; opening gzip input has already decoded its outer layer.
ZIP listing validates the central directory and
local headers, so custom sources must support those metadata reads. `read` seeks
to one local header for a single-entry extract.

Built-in readers only read entries they listed, or unchanged `copy()` results of
them. An entry built with the public constructor, an entry from another reader
(even of the same format and with equal metadata), or a copy with changed metadata
throws `ArchiveFormatException` naming the entry, before any payload read. Source
positions are private reader state; entries no longer expose `dataOffset`.

Readers enforce a default ceiling of 100,000 listed entries during parsing,
before retaining each additional entry. Directories and cpio hard-link records
count; ar symbol/name tables, TAR metadata records and cpio trailers do not.
ZIP also checks its declared count before loading the central directory, and
counts actual records so wrapped or understated counts cannot bypass the ceiling.
An excess throws `DecompressionBombException`; failed listings are not cached.
Pass a policy when opening a legitimately larger archive:

```kotlin
val limits = ArchiveLimits { maxEntries = 200_000 }
ArchiveKodec.open(source, limits).use { reader ->
    for (entry in reader.entries()) {
        val data = reader.read(entry) // defaults to reader.limits
    }
}
```

Each concrete reader also accepts `(source, limits)` or
`(source, nameDecoder, limits)`, and the facade has the corresponding overloads.
Existing constructor and `open` calls still use `ArchiveLimits.DEFAULT`.
An explicit policy on `read(entry, limits)` overrides the reader's read policy;
it does not relax the ceiling used to parse its listing. Byte limits apply per
read. To cap the total across reads, share an `ExtractionBudget`:

```kotlin
val budget = ExtractionBudget(64L * 1024 * 1024)
for (entry in reader.entries()) {
    val bytes = reader.read(entry, budget = budget) // throws once the total passes 64 MiB
}
```

Each budgeted read decodes with its output ceiling lowered to the bytes the budget
has left, so it stops as soon as the total would pass, then charges what it
produced; a failed read charges nothing. The reader keeps no running total, so the
same reader can serve several budgets. A budget is mutable and meant for one
extraction on one thread.

### Browse an uncompressed Linux initramfs

`CpioReader` and `ArchiveKodec.open` read one CPIO archive and stop at its first
`TRAILER!!!`. Choose `InitramfsReader` explicitly to browse an entire buffer of
newc/crc segments:

```kotlin
import io.github.yuroyami.archivekodec.archive.cpio.InitramfsReader

InitramfsReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
    for (segment in reader.segments()) {
        println("${segment.startOffset}..${segment.endOffset}, trailer=${segment.hasTrailer}")
        for (entry in segment.entries) {
            val body = reader.read(entry)
        }
    }
    // The same entries, flattened in source order:
    val allEntries = reader.entries()
}
```

Each trailer ends a segment and resets hard links. Segment offsets are absolute,
with an exclusive end; they include record alignment and the trailer but exclude
surrounding zero padding. Extra zero padding may appear between records, and raw
headers must begin at absolute four-byte offsets. The final segment may omit its
trailer (`hasTrailer == false`). Empty/all-zero buffers have no segments;
trailer-only segments are preserved with empty entry lists. Duplicate names
remain in source order; this API does not apply a filesystem overlay.

`entries()` and `segments()` inspect metadata without reading payloads. A later
malformed or unsupported segment fails the complete listing with its source
offset; a partial result is never cached. Compressed segments, including gzip,
and odc/binary CPIO are explicitly unsupported. The reader does not automatically
decompress them. The usual source ownership, custom name decoder and per-read
limits apply, including crc byte-sum verification when an entry is read.

`maxEntries` counts members across the whole buffer. `ArchiveLimits.maxSegments`
separately caps all segments, including empty ones, at 100,000 by default. Zero
allows no segments; `UNBOUNDED` raises it to `Int.MAX_VALUE`. Other readers ignore
this setting. Configure it through the existing `ArchiveLimits` builder or `copy`.

ar resolves GNU and COFF long-name references from the `//` table. GNU names end
with `/\n` (LF-only names are also accepted); COFF names end with NUL and retain
any stored trailing slash. A table containing NUL uses the COFF delimiter, so
embedded LF characters remain in the raw name for path validation. A referenced
name without a terminator inside the table throws `ArchiveFormatException`, as
do invalid or outside-table offsets.

BSD `#1/n` names occupy the first `n` bytes of a member; the reader excludes all
of those bytes from its payload, removing only trailing NUL name padding. The
leading BSD ranlib index (`__.SYMDEF`, `__.SYMDEF SORTED`, `__.SYMDEF_64` or
`__.SYMDEF_64 SORTED`) is skipped. Later members with those names remain files.

Built-in readers own the source supplied to their constructor or returned by a
successful `open`. Close the reader, including after listing or reading fails;
those failures leave it open so other entries can still be read. If `open` throws
before returning a reader, the caller still owns and must close the source.
Do not share an owned source between readers or close it separately during use.

Import `io.github.yuroyami.archivekodec.archive.use` for scoped Kotlin cleanup.
It closes on normal return, non-local return, or an exception. If both the block
and cleanup throw, the block failure remains primary and the close failure is
suppressed on it. `reader.asAutoCloseable()` provides a standard Kotlin resource
view and Java `try`-with-resources through `ArchiveReaders.asAutoCloseable(reader)`.
Swift callers use `try reader.close()` in their cleanup handling. The adapter is
hidden from Objective-C to preserve the reader's catchable NSError close contract.

Closing releases the source and cached metadata before calling the source's
cleanup hook. That hook is attempted once; repeated close calls are no-ops even
if cleanup throws. Subsequent `entries()` and `read()` calls throw
`ArchiveClosedException` without accessing the source, including cached listings
and empty/directory reads. Previously returned entry values remain usable, and
`format` and `toString()` remain available. Readers require external serialization
for concurrent operations or close. Resource-free custom readers can use the
interface's default close; implementations retaining a handle must override it.

`RandomAccessSource` implementations need not support concurrent calls. Even a
thread-safe source does not make its reader thread-safe. `entries()` may return
the reader's shared cached list: treat it as read-only, including from Java, and
use `toMutableList()` when you need to edit the listing. `ByteArrayRandomAccessSource`
borrows its input array without copying it; keep those bytes unchanged during use.
Checksum instances also hold mutable state: use one per thread or serialize all
updates, value reads and resets.

The `archivekodec-io` adapters consume a `RawSource` from its current position
without closing it or synchronizing access. The caller must close that original
source on success or failure. The snapshot returned by `toRandomAccessSource()`
has independent ownership; closing it does not close the original `RawSource`.

ZIP/ZIP64 supports single-volume archives. Split-volume disk metadata throws
`ArchiveUnsupportedException`. End-record comments must reach EOF, and missing
or malformed required ZIP64 fields throw `ArchiveFormatException` with entry
context. ZIP64 disk sentinels that resolve to disk zero are supported.

ZIP listing compares each local filename as bytes, compression method, and
general-purpose flags with the central directory. Contradictions throw
`ArchiveFormatException` with the directory entry name and local field offset.
Extraction uses the parsed directory method and rechecks the local fields and
extra-field length before reading compressed data. Pass an entry returned by
that reader, or an unchanged value copy; modified entry metadata is rejected,
including changes that remove its CRC or alter its sizes. Data descriptors,
ZIP64 size representations, differing extra fields, timestamps, and comments
remain supported.

Each ZIP member's local header, filename, extra fields and compressed data must
occupy a separate range before the central directory. Listing rejects an extent
that crosses the next local header or the directory with `ArchiveFormatException`,
the earlier entry's name, and the conflicting boundary offset. Duplicate local
offsets retain the `DecompressionBombException` category. Checks use physical
offset order while the listing retains directory order; adjacent entries, empty
entries, padding and data-descriptor gaps are valid. These structural checks
apply even with `ArchiveLimits.UNBOUNDED`. Output and ratio limits still apply
per entry.

ZIP and ZIP64 support prepended launchers and self-extracting prefixes with
ZIP-relative or already absolute offsets. Listing resolves stored offsets to
physical source positions for the reader's private local-header locators.
ZIP64 end records may contain a variable-length extensible sector; searches use
bounded chunks rather than a fixed 56-byte assumption. Existing absolute-offset
directory and locator gaps remain readable. Shifted local headers and payloads
still undergo the same range, overlap, metadata, size, ratio and CRC32 checks.

Entries with encryption bit 0, strong-encryption bit 6, or the AES method marker
99 have `isEncrypted = true`, and `ArchiveEntry.encryption` names the scheme. ZipCrypto
and WinZip AES entries are decrypted given a password; see
[Encrypted archives](#encrypted-archives). PKWARE strong encryption throws
`ArchiveUnsupportedException` before any ciphertext is read. Compressed patched data
(bit 5) is likewise unsupported. Masked local headers used with central-directory encryption
(bit 13) throw `ArchiveUnsupportedException` during listing.

`ArchiveEntry.zipMethod` exposes the original central-directory method number,
including 99 for AES; it is null for other containers and manually constructed
entries, and survives entry copies. Known methods retain their codec ids, while
an unbound method uses `CodecId("zip-method-N")` (for example, `zip-method-11` for
method 11), rather than STORE. A custom ZIP binding reports the registered id and
retains the original number. Check `reader.codecs.byZipMethod(entry.zipMethod!!)`
for decoder availability; registering an id alone does not bind an unknown wire
method. Reading an unbound method throws `ArchiveUnsupportedException` before
fetching its payload. A custom decoder bound to a method only ever receives decrypted,
authenticated data.

### Encrypted archives

ZIP and 7z archives protected by a password are read with two options: the password and,
for AES, a `CryptoProvider`. The core has no AES of its own: a table-driven AES written in
Kotlin is slow and leaks key bits through cache timing. `archivekodec-crypto` supplies
`CryptographyKotlinProvider`, which runs the platform's implementation through
cryptography-kotlin (the JDK, a bundled OpenSSL 3, or Apple's libraries).

```kotlin
val options = ArchiveOpenOptions {
    password = ArchivePasswordProvider.of(secret)   // or a callback per entry name
    crypto = CryptographyKotlinProvider()
}
ArchiveKodec.open(bytes, null, options).use { reader ->
    for (entry in reader.entries()) println("${entry.name} ${entry.encryption}")
    val data = reader.read(reader.entries().first())
}

// Writing: WinZip AES-256 for every file.
val zip = ZipWriter.write(files, ZipEncryption(password, CryptographyKotlinProvider()))
```

| Scheme | Read | Write | Notes |
| --- | --- | --- | --- |
| WinZip AES (AE-1, AE-2; 128, 192, 256-bit) | yes | yes (AE-2) | The HMAC-SHA1 tag is checked before any decompression; a mismatch is `ArchiveAuthenticationException` |
| ZipCrypto | yes, no provider needed | never | Broken; reported as `ArchiveEncryption.ZIP_CRYPTO_INSECURE` |
| 7z AES-256 | yes, including encrypted headers | no | No authentication: a wrong password shows up as data that fails to decode or to match its CRC |
| PKWARE strong encryption | no | no | `ArchiveUnsupportedException` |

A missing or wrong password is `ArchivePasswordException`. The password callback gets
the entry name, or null for 7z encrypted headers, which are needed just to list the
archive. Passwords are hashed as UTF-8 for ZIP and UTF-16LE for 7z, as 7-Zip does. 7z
key derivation is capped at 2^24 SHA-256 rounds (7-Zip writes 2^19).

`CryptoProvider` is a plain interface, so any other implementation can be passed instead.
The readers call it synchronously; browsers offer only the asynchronous WebCrypto, so
`archivekodec-crypto` has no JS or Wasm build.

| Format | Read | Write |
| --- | --- | --- |
| ZIP and ZIP64 | yes (STORE, DEFLATE, Deflate64, bzip2, LZMA, zstd and PPMd) | yes (STORE and DEFLATE) |
| TAR (v7, ustar, GNU, pax) | yes | ustar + GNU long names; regular files/directories |
| 7z | yes (copy, LZMA, LZMA2, PPMd, Deflate, Deflate64, bzip2, delta, BCJ x86; compressed headers; solid folders decoded once) | no |
| ar (GNU, BSD, COFF; GNU thin) | yes (thin members through an `ExternalMemberResolver`) | yes |
| cpio (newc, crc, odc) | yes | newc and crc |
| ISO 9660 (Joliet, Rock Ridge) | yes (multi-extent files; Rock Ridge symlinks read as their target) | no |
| CAB | yes (stored, MSZIP and LZX folders, each decoded once; Quantum and files that span cabinets are listed but refused on read) | no |
| WIM | yes (uncompressed, XPRESS and LZX; deduplicated resources and SHA-1 checks; several images as `1/`, `2/`; LZMS and solid `.esd` images refused) | no |
| SquashFS 4.0 | yes (gzip, LZMA, LZO, xz with the x86 BCJ filter, LZ4, zstd; fragments, sparse blocks, symlinks read as their target, devices and FIFOs listed) | no |
| RAR4 and RAR5 | yes: stored members of both, and RAR5 compressed members (all methods, solid archives, delta, x86 and ARM filters), with CRC checks. RAR4 compression, encrypted and multi-volume members, and archives with encrypted headers are refused on read | never |

TAR pathname prefixes are read only for headers with the complete six-byte
`ustar\0` marker. The standard version is `00`; missing or nonstandard version
bytes do not discard a pathname in that known layout. Old GNU and v7 extension
bytes are not path prefixes. GNU long-name records override the base name and
prefix, and the layout is selected separately for each header.

CPIO hard links share their group's last nonempty stored payload. Each alias
reports the logical size and payload offset, including when its own record has
no body. A group containing only empty records stays empty; directories and
members with a single link do not join groups. Listing reads headers and names
without fetching payloads, and its cached list is read-only.
Reading checks every `crc` record in the group using its physical body's unsigned
byte sum modulo 2^32, including overwritten bodies. That sum is not exposed as
`ArchiveEntry.crc32`. Empty-body sums are validated during listing. Checksum
mismatches throw `ArchiveFormatException` with the physical record's name and
check-field offset. Checks are repeated on every read; overwritten bodies use
an 8 KiB buffer, while output limits apply to the logical bytes returned.

The readers read through the `RandomAccessSource` interface, which supports seek
and read. The core ships `ByteArrayRandomAccessSource`; `archivekodec-io` adds
`FileRandomAccessSource`, which reads a file on demand.

### GNU thin ar archives

A thin archive (`ar rcT`, `llvm-ar --thin`) stores member headers and paths but not the
member data. `entries()` lists the referenced members with their sizes and a zero
`compressedSize`. Reading one needs a resolver, because the library never opens files:

```kotlin
val resolver = ExternalMemberResolver { path -> fileBytesBesideTheArchive(path) }
ArchiveKodec.open(bytes, FormatRegistry.DEFAULT, options = ArchiveOpenOptions { externalMembers = resolver })
```

Treat `path` as untrusted. Without a resolver, reading a thin member throws
`ArchiveUnsupportedException`; a resolver that returns null or bytes of the wrong size
causes `ArchiveFormatException`.

## Register a container format

`ArchiveFormat` is an open value: `ArchiveFormat("erofs")` gives a custom
reader an honest identifier. Its `id` is lowercase ASCII, while `name` and
`toString()` keep uppercase diagnostic names. There is no exhaustive enum list.
Use the same identifier for the descriptor and `ArchiveReader.format`.

```kotlin
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.FormatRegistry
import io.github.yuroyami.archivekodec.archive.FormatSpec
import io.github.yuroyami.archivekodec.archive.readFully

val erofs = FormatSpec(
    id = "erofs",
    matches = { source -> // the EROFS superblock magic sits 1024 bytes in
        source.size >= 1028 && source.readFully(1024, 4).contentEquals(byteArrayOf(0xE2.toByte(), 0xE1.toByte(), 0xF5.toByte(), 0xE0.toByte()))
    },
    open = { source, options -> MyErofsReader(source, options) },
)
val formats = FormatRegistry.DEFAULT.with(erofs)
val options = ArchiveOpenOptions { limits = myLimits; codecs = myCodecs }
val reader = ArchiveKodec.open(bytes, formats, options = options)
```

`MyErofsReader` is your implementation. Its factory receives the selected limits,
filename decoder and codec registry through immutable `ArchiveOpenOptions`; it
must enforce the policies relevant to that format. Null `nameDecoder` preserves
each built-in reader's default. Closing the returned reader must close the source.
A failed factory must leave the source open for the caller. Implementing
`ArchiveReader` requires `@OptIn(ExperimentalArchiveImplementation::class)`.
`ArchiveEntry` has no public source position, so a custom reader keeps its own
locators, for example in a list parallel to its listing. Build entries with the
fixed three-argument constructor, or set other metadata through the builder:

```kotlin
ArchiveEntry("dir/file.txt", size = 3, isDirectory = false) {
    lastModifiedEpochSeconds = 1_700_000_000
    crc32 = 0x352441C2
}
```

`entry.copy { name = "renamed" }` configures an independent builder. New entry
metadata will be added as builder properties, so these signatures stay fixed.

`with` returns an independent registry. The newest descriptor runs first and
replaces an earlier descriptor with the same id. Detectors can read at any offset,
including ISO 9660's signature at 32769; bounds-check before reading short inputs.
A detector must not close or retain the source. Probe failures propagate, while
`false` proceeds to the next descriptor. All built-in probes share one 512-byte
header read. `Magic` remains the low-level built-in header detector.

```kotlin
ArchiveKodec.sniff(bytes, formats)          // Also accepts a RandomAccessSource.
ArchiveKodec.open(bytes, formats, format = "erofs", options = options)
ArchiveKodec.open(source, format = "tar")  // Explicit built-in selection.
```

An explicit id skips all probing and ZIP footer fallback; the selected reader
still validates its input. Automatic opening uses the first match, then the ZIP
footer fallback if no descriptor matches. Recognized formats without a reader
throw `ArchiveUnsupportedException`. Built-in gzip opening unwraps once, applying
the same registry and options to the contained archive. Replacing the gzip
descriptor uses your factory instead. Plain byte arrays remain borrowed until
close; gzip output is an independent snapshot. No registry changes global defaults.

## Write TAR and tar.gz

```kotlin
import io.github.yuroyami.archivekodec.archive.tar.TarWriter

val tar = TarWriter.write(
    listOf(
        TarWriter.FileSpec("dir/", byteArrayOf()) {
            isDirectory = true
            mode = 0x1ED // permission bits: octal 0755
        },
        TarWriter.FileSpec("dir/file.txt", bytes),
    ),
)
val tarGzip = ArchiveKodec.gzip.compress(tar)
```

`TarWriter` writes regular files and directories using ustar headers, unsigned
header checksums, 512-byte data padding and two zero blocks at the end. Names
that exceed 100 UTF-8 bytes use the ustar prefix field when a path split fits;
otherwise a GNU `L` long-name record preserves the complete name. No names are
silently truncated. An empty archive is exactly two zero blocks.

Member descriptions own their body snapshots. Use `FileSpec(name, data) { ... }`,
`FileSpec.Builder(name, data).build()` or `file.copy { ... }` to configure
`isDirectory`, `mode`, `uid`, `gid` and `modificationTime` (Unix seconds).
Defaults are regular-file type, permission mode octal `0644` and time/IDs zero.
Mode contains permission bits only, from octal `0000` through `7777`; uid/gid
must fit seven octal digits and nonnegative timestamps eleven octal digits.
Directories require empty bodies. A name ending in slash must explicitly be a
directory, so readers cannot silently hide a regular file's data.

The writer preserves names, duplicates and member order. Empty names, NUL and
malformed Unicode are rejected. Paths are not sanitized for filesystem extraction.
The result must fit in one `ByteArray`. Links, devices, pax metadata, sparse files
and binary numeric fields are not emitted. Compression is a separate codec step,
as shown above.

## Write GNU ar

```kotlin
import io.github.yuroyami.archivekodec.archive.ar.ArWriter

val archive = ArWriter.write(
    listOf(
        ArWriter.FileSpec("debian-binary", "2.0\n".encodeToByteArray()),
        ArWriter.FileSpec("control.tar.gz", controlTarGzip),
        ArWriter.FileSpec("data.tar.gz", dataTarGzip),
    ),
)
```

`ArWriter` writes GNU headers, even-byte padding and a `//` table for long names.
It preserves UTF-8 basenames, spaces, duplicate names and member order. Empty/dot
names, slashes, backslashes, colons, ASCII controls, malformed Unicode and reserved
symbol-index names are rejected. It does not silently strip directories or truncate
names. BSD inline names and thin archives are not emitted.

File descriptions own their payload snapshots. Use `FileSpec(name, data) { ... }`
or `FileSpec.Builder(name, data).build()` to set `modificationTime` (Unix seconds),
`uid`, `gid` and `mode`. Defaults are time/IDs zero and Unix mode octal `0100644`;
numeric values must fit their decimal/octal header fields. The complete archive
must fit in one `ByteArray`. No linker symbol index is generated; run `ranlib`
when a linker needs one. Debian member contents and ordering remain the caller's
responsibility; the example assumes already-created control and data archives.

## Write cpio

```kotlin
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter

val archive = CpioWriter.write(
    listOf(
        CpioWriter.FileSpec("etc", byteArrayOf()) { mode = 0x41ED }, // directory, 0755
        CpioWriter.FileSpec("etc/config", configBytes),
        CpioWriter.FileSpec("config", "etc/config".encodeToByteArray()) {
            mode = 0xA1FF // symbolic link, 0777; the body is the target
        },
    ),
)
```

The default dialect is `newc` (`070701`). Pass `CpioWriter.Dialect.CRC` as the
second argument for `070702` and its unsigned body-byte sum modulo 2^32. This
checksum is not CRC-32. Both dialects use fixed hexadecimal headers, NUL-terminated
UTF-8 names, zero padding to four-byte boundaries and a final `TRAILER!!!` record.
No odc, old binary or compressed output is emitted; apply a codec separately if
the consumer expects a compressed archive. The complete result must fit in one
`ByteArray`.

Member descriptions own their data snapshots and offer `FileSpec(name, data) { ... }`,
`FileSpec.Builder(name, data).build()` and `file.copy { ... }`. Configure Unix
`mode`, `inode`, `uid`, `gid`, `linkCount`, `modificationTime` (Unix seconds),
`deviceMajor`/`deviceMinor` and `rdevMajor`/`rdevMinor`. Numeric fields must fit
unsigned 32-bit headers; `mode` contains a supported Unix file type and its
permissions in 16 bits. Defaults are regular-file mode octal `0100644`, one link
and all other metadata zero. Directories and special files require empty bodies;
symlinks require nonempty target bytes without NUL.

Hard links use matching device and inode numbers with `linkCount > 1`.
The writer preserves body placement; callers choose which member carries the
shared data. Conflicting file types in a hard-link group are rejected. Names,
duplicates and member order are preserved. Empty names, NUL, malformed Unicode
and the exact reserved name `TRAILER!!!` are rejected. Names are not sanitized
for filesystem extraction, and no filesystem objects are created by the writer.

## Write a ZIP

```kotlin
val bytes = ZipWriter.write(
    listOf(
        ZipWriter.FileSpec("readme.txt", text, ZipMethod.STORED),
        ZipWriter.FileSpec("data.bin", payload, ZipMethod.DEFLATED),
    ),
)
```

`FileSpec` accepts `ZipMethod.STORED` or `ZipMethod.DEFLATED`; its two-argument
constructor defaults to deflated. It owns a payload snapshot, and each access to
`file.data` returns an independent copy. Reusing input arrays or builders cannot
change an existing file, its hash, or its written payload. Equality compares name,
method and byte content; descriptions contain a bounded escaped name and byte
count. Use `file.copy { ... }` or `FileSpec(name, data) { ... }` for configuration,
and `ZipWriter.FileSpec.Builder(name, data).build()` from Java. Swift exports the
builder as `ZipWriter.FileSpecBuilder(name:data:)`; its `build()`, direct file
constructors and `doCopy(configure:)` require `try`. The old CodecId constructor, codec
getter, generated field-list copy and destructuring functions are removed for
the next minor release; rebuild callers and migrate to the typed methods.

`ZipWriter` is not reachable from the `ArchiveKodec` object, so import it directly.
Its whole-array output is limited by the platform's `ByteArray` capacity, so sizes
and offsets cannot reach 4 GiB.
It emits ZIP64 end records when there are more than 65,535 entries; smaller
entry counts use the classic ZIP layout. Member sizes and offsets always use
classic 32-bit fields, including in archives with ZIP64 entry counts.
Names use strict UTF-8 and bit 11 in both ZIP headers. All names are validated
before payload compression or output assembly. Backslashes, leading slashes,
drive/device prefixes, `..` components, ASCII controls (including NUL and DEL),
malformed Unicode, and encoded names longer than 65,535 bytes throw
`ArchiveFormatException` carrying the original entry name. ZIP64 does not extend
this filename limit. A drive/device prefix is a nonempty prefix followed by `:`
in the first nonempty path component other than `.`; colons in later components
are supported. Accepted names retain their exact bytes, including relative dot
components, repeated forward slashes, trailing directory slashes, and empty
anonymous names. The caller chooses the mapping from filesystem names.
Both headers use the reproducible default timestamp 1980-01-01 00:00:00 unless
a time is set. Optional settings record a time, a Unix mode and comments:

```kotlin
val script = ZipWriter.FileSpec("run.sh", bytes, ZipMethod.STORED) {
    lastModifiedEpochSeconds = 1_700_000_000
    unixMode = 0b1000000111101101   // 0o100755: a regular, executable file
    comment = "build script"
}
val zip = ZipWriter.write(listOf(script), "archive comment")
```

A set time is written as a UTC DOS time, clamped to 1980–2107 with odd seconds
rounded down, and as an Info-ZIP extended timestamp when it fits 32 bits; readers
prefer the latter, so the exact second survives. A set mode marks the entry as
made on Unix (host 3) and stores `st_mode` in the high 16 bits of the external
attributes, as Info-ZIP does. Comments are UTF-8 and must fit 65,535 bytes. A spec
without these settings produces the same bytes as before.

### Entry times and modes on read

`lastModifiedEpochSeconds` is UTC seconds, or null when the archive holds no
time. ZIP prefers the extended-timestamp field and otherwise reads the zoneless
DOS time as UTC; an invalid DOS date gives null. TAR, ar and cpio read their
mtime fields, and an empty TAR time field gives null. `unixMode` is the Unix
`st_mode` including file-type bits, such as `0o100755`, or null when the archive
stores none. ZIP reports it only for entries made on Unix; TAR and ar add the
file-type bits their mode fields omit. `comment` holds a ZIP entry comment.

## Sanitize entry names before writing them

By default, `ZipReader`, `TarReader`, `ArReader` and `CpioReader` refuse a
listing that contains a name `PathGuard.sanitize` would reject: `entries()`
throws `ArchivePathException` naming the first such entry. That covers
`../../etc/passwd`, `/etc/passwd`, `C:/x`, empty names and control characters.
Directory entries naming the root, such as `./` or `.`, are allowed. Accepted
names keep their stored spelling: directory names retain every stored trailing
slash, including `e//` and `./`, and `./a` is not rewritten. Use `isDirectory`
to identify directories; a type-5 TAR directory need not end in a slash.

To list hostile names as stored, for inspection or your own policy, opt out:

```kotlin
ArchiveKodec.open(source, ArchiveLimits { rejectUnsafeNames = false })
```

`ArchiveLimits.UNBOUNDED` also turns the check off.

ZIP uses strict UTF-8 when bit 11 is set. Otherwise a valid version-1 Unicode
Path extra field (`0x7075`, matching the stored name's CRC32) takes precedence
over CP437. Both central and local fields are supported; conflicting valid
Unicode names are rejected. Unknown versions, stale CRCs and malformed optional
UTF-8 payloads fall back to the stored name. TAR, ar and CPIO default to strict
UTF-8. Invalid UTF-8 in a required filename raises `ArchiveFormatException`.

For another legacy encoding, supply an `ArchiveNameDecoder` to a concrete reader
or to `ArchiveKodec.open(source, nameDecoder)`. For example, JVM callers can use:

```kotlin
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import java.nio.charset.Charset

val charset = Charset.forName("GBK")
val decoder = ArchiveNameDecoder { bytes -> String(bytes, charset) }
ArchiveKodec.open(source, decoder).use { reader -> /* list and read entries */ }
```

ZIP's UTF-8 flag and valid Unicode Path metadata take precedence over this
legacy decoder. There is no automatic charset guessing. `ArchiveNameDecoder.UTF8`
and `.CP437` are available on every target. Callback inputs are defensive copies;
callback exceptions are translated to contextual archive failures.

`ArchiveEntry.nameBytes` returns a defensive copy of the stored filename without
container padding or terminators. TAR prefixes and base names are joined with
the stored slash; GNU long names retain their full path bytes. ZIP retains the
header bytes even when a Unicode Path field supplies the displayed name.
The property is null on manually constructed entries. Copies preserve the
stored-byte snapshot. Stored bytes do not participate in value equality.

Joining an unchecked name to an output directory is the Zip-Slip vulnerability.
Zip-Slip means an archive entry escapes the folder you meant to extract into, and
overwrites a file somewhere else on disk. The default listing check prevents
traversal names from reaching you, but it does not normalize them. Join the
result of `sanitize`, not the raw name, and keep doing so when you opt out:

```kotlin
val safe = PathGuard.sanitize(entry.name)     // throws ArchivePathException
if (PathGuard.isSafe(entry.name)) { /* ... */ }
```

Archives made from the current directory can contain a directory entry named
`.` or `./`. Skip it with `PathGuard.isRootDirectory(entry)` before calling
`sanitize`, as in the first example. This helper checks the directory flag and
recognizes only nonempty, relative names made of dot and separator segments.
It returns false for unsafe names and for files; you must still sanitize every
remaining name. `sanitize` and `isSafe` keep rejecting root names such as `.`,
`./` and `./.`, so they cannot become file destinations. Skipping the entry does
not create a directory or apply its metadata to your output root.

`sanitize` rejects empty names, control characters and NUL, absolute paths,
Windows drive-letter paths and any `..` segment. It normalizes backslashes to
forward slashes, collapses `.` segments, and returns a relative path. It checks names,
not links: a symlink entry (`entry.isSymbolicLink`) can point outside your output
folder through a perfectly clean name. Skip links, as `extract` does by default, or
check each target with `PathGuard.resolveLink(entry.name, target)` before creating it,
and create links only after writing every file, so no file is written through one.

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

Both `Checksum.update` and `XxHash32.hash` accept an `offset` and `length`.
Omitting `length` selects the suffix beginning at `offset`:

```kotlin
val checksum = Crc32()
checksum.update(bytes, offset = 3) // bytes[3] through the end
checksum.update(bytes, offset = bytes.size, length = 0) // valid empty slice
val suffixHash = XxHash32.hash(bytes, offset = 3)
```

Negative or out-of-bounds slices throw `IllegalArgumentException` before reading
bytes or changing checksum state. A rejected update can be followed by a valid
one. Swift calls to `update(data:offset:length:)` and `hash(data:offset:length:seed:)`
now require `try`, including calls through the `Checksum` protocol; invalid slices
arrive as catchable errors. Valid explicit slices and whole-array digests are unchanged.

## Sniff a format

```kotlin
ArchiveKodec.sniff(header)     // ArchiveFormat.ZIP, TAR, GZIP, ...
ArchiveKodec.sniff(source)     // reads the first 512 bytes of a RandomAccessSource
```

The sniffer recognizes ZIP, TAR, ar, cpio, gzip, zlib, bzip2, xz, zstd, LZ4, 7z,
RAR, ISO 9660, CAB, WIM, SquashFS and Snappy framing. ISO 9660 keeps its `CD001` signature at byte
32769; a source sniff reads those five bytes only when the first 512 bytes are all
zero or end in an MBR boot signature, as ISO images (including hybrid ones) do. `ArchiveFormat.SNAPPY` identifies framed input; decode
it with `CodecId.SNAPPY_FRAMED`. Raw Snappy and brotli have no identifying magic
bytes, and LZMA-alone is not identified reliably from its header.
The sniffer never returns `BROTLI` or `LZMA`.

Source sniffing fills `min(source.size, 512)` bytes through repeated reads, so
one-byte and other partial positive reads are supported. Negative source sizes,
premature EOF, zero progress and invalid returned counts throw
`ArchiveFormatException`; source I/O errors propagate unchanged. Sniffing does
not close the source. A successful `open` transfers ownership to the reader;
when opening fails, the caller retains the source.
The sniffing overloads remain header-only. `open` additionally uses the bounded
ZIP-footer fallback for UNKNOWN headers; readers validate the candidate archive.

A complete checksum-valid TAR header takes priority over short signatures,
including when a member name resembles another format. This recognizes v7,
ustar, GNU and legacy signed-checksum headers. A complete first zero record also
identifies an empty TAR; empty input and shorter zero prefixes remain unknown.
ZIP requires a complete recognized four-byte signature, including supported
empty archives beginning with a digital-signature or ZIP64 end record. Bzip2
requires a level digit and a complete first block or end marker (ten bytes).
Zlib checks the header and rejects a reserved first DEFLATE block type when
available. Dictionary-based zlib candidates require the four-byte dictionary
identifier and first block byte; explicit decompression still reports a typed
unsupported-dictionary error.

A match is format evidence, not full archive or stream validation. Readers and
codecs validate the remaining structure and payload.

## Targets

Both artifacts build for the same targets, so both can go in one `commonMain`.
The matrix covers every target KitePDF and ImageKodec declare. The core is common
code, apart from the [JavaScript exports](#javascript-and-typescript) and the
[`Data` bridges for Swift](#swift); the `-io` file source has one implementation per
platform family.

| Target | Both artifacts |
| --- | --- |
| Android, minSdk 21, compileSdk 37, Java 8 bytecode | yes |
| `jvm`, Java 8 bytecode | yes |
| `js`, browser and Node | yes |
| `wasmJs`, browser and Node | yes |
| `wasmWasi`, Node | yes |
| `iosArm64`, `iosSimulatorArm64`, `iosX64` | yes |
| `macosArm64` | yes |
| `tvosArm64`, `tvosSimulatorArm64` | yes |
| `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` | yes |
| `linuxX64`, `linuxArm64` | yes |
| `mingwX64` | yes |
| `androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64` | yes |

There is no `macosX64` or `tvosX64`. Kotlin 2.4 deprecates both and plans to remove
them ([target tiers](https://kotlinlang.org/docs/native-target-support.html)).

## The `-io` artifact

`archivekodec-io` adds a file source, a suspending extraction call and kotlinx-io adapters.
It depends on kotlinx-io and kotlinx-coroutines.

`FileRandomAccessSource` opens a file read-only and reads it on demand, so a
multi-gigabyte archive can be listed and read without loading it:

```kotlin
ArchiveKodec.open(FileRandomAccessSource("/data/backup.zip")).use { reader ->
    val entries = reader.entries()          // reads only the central directory
    val one = reader.read(entries.first())  // reads only that member
}
```

The reader owns the source and closes it. Small reads go through a cache of four
64 KiB pages; larger reads go straight to the file. It uses `RandomAccessFile` on
the JVM and Android, POSIX `pread` on Apple, Linux and Android native, `ReadFile`
on Windows, and Node's `fs` on Kotlin/JS and Kotlin/Wasm (JS, Node 20.16 or later).
Browsers and WASI have no file system, so there the constructor throws
`ArchiveUnsupportedException`. Android content URIs (SAF) and other non-path
sources still need a `RandomAccessSource` of your own.

`extract` writes a chosen set of entries to sinks, reporting progress and stopping when
its coroutine is cancelled:

```kotlin
val passwords = PromptedPasswords { name -> askUser("Password for $name") } // a suspending prompt
ArchiveKodec.open(FileRandomAccessSource(path), format = null, options = ArchiveOpenOptions { password = passwords }).use { reader ->
    val wanted = reader.entries().filter { it.name.startsWith("docs/") }
    reader.extract(wanted, progress = { done, total -> bar.update(done, total) }, passwords = passwords) { entry ->
        SystemFileSystem.sink(Path(outDir, PathGuard.sanitize(entry.name)))
    }
}
```

Entries are extracted in listing order, so a solid block (a 7z or cabinet folder, a solid
RAR5 stream) is decoded once for all its entries. Directories are skipped, and each sink
is closed after its entry. Cancellation is checked between entries and before each write of
about 1 MiB of a streamed entry: ZIP's stored, DEFLATE and Deflate64 members stream
(`ArchiveReader.read(entry, sink)`), so even a large one stops promptly; other members are
read whole and then written. `extract` writes nothing to disk by itself and does not create
symbolic links; join names with `PathGuard.sanitize` as above.

Symbolic links are skipped by default: a link to `../..` followed by a file "inside" it is
the classic way out of an extraction folder. Pass `onLink` to receive each link with its
target and decide, checking it with `PathGuard.resolveLink(entry.name, target)`, which
resolves the target against the link's directory and refuses one that leaves the root.
Hard links are written as ordinary files with the data of the member they name.
`ArchiveEntry.isSymbolicLink`, `isHardLink` and `linkTarget` describe links in every
listing (TAR, ZIP, cpio, 7z, RAR, ISO 9660 and SquashFS).

The adapters:

```kotlin
fun RawSource.readAllBytes(): ByteArray
fun RawSource.toRandomAccessSource(): RandomAccessSource
fun Codec.compress(source: RawSource): ByteArray
fun Codec.decompress(source: RawSource, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray
```

```kotlin
fun Codec.decompress(source: RawSource, sink: RawSink, limits: ArchiveLimits = ArchiveLimits.DEFAULT): Long
fun Codec.compress(source: RawSource, sink: RawSink): Long
```

`toRandomAccessSource()` drains the entire source into a `ByteArray` and wraps it;
use `FileRandomAccessSource` for files. Decompressing into a `RawSink` streams the
output for DEFLATE, zlib and gzip; the compressed input is always read whole, and
compression is not streamed.

## JavaScript and TypeScript

The Kotlin/JS build is also an ES module for plain JavaScript and TypeScript, with a
generated `.d.mts`. It exports a small surface made of types JavaScript has: bytes are
`Int8Array` (Kotlin's `ByteArray`), sizes are numbers, and failures throw an `Error`
with a message. The Kotlin API is not exported as it is, because Kotlin collections,
`Long` and interface default arguments have no JavaScript form.

The module is not published to npm yet. Build it with
`python3 scripts/js_package.py`. The module lands in
`archivekodec/build/dist/js/productionLibrary/`, with a `package.json` to install
from that folder.

```js
import { gzip, gunzip, open, zip, ZipMember } from "./ArchiveKodec-KMP-archivekodec.mjs";

// A Uint8Array goes in as an Int8Array view over the same memory, without a copy.
const u8 = new TextEncoder().encode("hello");
const bytes = new Int8Array(u8.buffer, u8.byteOffset, u8.length);

const packed = gzip(bytes);              // level 6; gzip(bytes, 9) for smaller output
const unpacked = gunzip(packed);         // at most 2 GiB; gunzip(packed, maxBytes) for less

const archive = open(zip([new ZipMember("notes/a.txt", bytes)]));
for (const entry of archive.entries) {
    console.log(entry.name, entry.size, entry.isDirectory);
}
const data = archive.read("notes/a.txt"); // or archive.readAt(entry.index)
archive.close();

// And back to a Uint8Array:
const out = new Uint8Array(data.buffer, data.byteOffset, data.length);
```

| Export | What it does |
| --- | --- |
| `gzip`/`gunzip`, `deflate`/`inflate`, `zlib`/`unzlib`, `lz4`/`unlz4` | One codec each. Compressors take an optional `level` (DEFLATE family, 0–9); decompressors take an optional `maxBytes` |
| `compress(codec, data, level?)`, `decompress(codec, data, maxBytes?)` | Any codec by id; `codecs()` lists the ids |
| `decompressAny(data, maxBytes?)` | Detects the compressed format, like `ArchiveKodec.decompress` |
| `sniff(data)` | The format id the bytes start with, such as `"zip"`, or `"unknown"` |
| `open(data, format?, password?, maxBytes?)` | An `Archive` over any readable format; `format` skips detection |
| `Archive` | `format`, `entries` (an array of `Entry`), `read(name)`, `readAt(index)`, `close()` |
| `Entry` | `index`, `name`, `size`, `compressedSize`, `isDirectory`, `isEncrypted`, `isSymbolicLink`, `isHardLink`, `linkTarget`, `lastModified` (milliseconds, for `new Date`), `crc32`, `unixMode`, `codec`, `comment` |
| `zip(members)`, `ZipMember(name, data)` | Write a DEFLATE-compressed ZIP |
| `version()` | The library version |

Every decoding call has [ArchiveLimits.DEFAULT](#limits), with the output cap set by
`maxBytes`. Entry names are stored names, not safe paths: check them as
[Sanitize entry names before writing them](#sanitize-entry-names-before-writing-them)
describes before writing files. The `wasmJs` build exports nothing: Kotlin/Wasm can
export only top-level functions over numbers, so JavaScript callers use the JS build.

`python3 integration-tests/js-consumer/run.py` runs a plain JavaScript file and a
`--strict` TypeScript file against the built module (`integration-tests/js-consumer/`).

## Limits

- Whole-array input. Codecs take a whole `ByteArray`; DEFLATE, zlib and gzip can stream
  their output to a `ByteSink`, other codecs return or write one array, and an archive
  member is read into one `ByteArray` unless it is a ZIP stored or DEFLATE member read into
  a sink. Archives can be opened from a file without loading them, and entries extracted
  with progress and cancellation (`FileRandomAccessSource` and `extract` in `archivekodec-io`).
- `PathGuard` is not wired into any reader. Path safety is the caller's
  responsibility. See
  [Sanitize entry names before writing them](#sanitize-entry-names-before-writing-them).
- Built-in reader listings enforce `maxEntries`; initramfs listings additionally
  enforce `maxSegments`, including empty segments. No API opens archives nested
  inside other archives, so there is no nesting-depth limit. `maxDecoderMemory`
  caps memory a header asks a decoder to allocate up front: the PPMd model and the RAR5 window. Every implemented codec
  applies `checkRatio` before output allocation or growth. ZIP checks each entry;
  uncompressed TAR/ar/cpio reads check their known 1:1 ratio.
- The default `maxCompressionRatio` of 10,000:1 cannot reject a valid stream from
  the current codecs. DEFLATE (including gzip/zlib) stays below 1032:1, LZ4 below
  255:1, Snappy (raw or framed) below 64:3, and STORE is 1:1. The default preserves
  highly compressible legitimate input; lower it when ratio-based rejection is
  required. It can still reject impossible declared sizes before decoding.
  The default 2 GiB output cap applies to each decode/read separately. Pass an
  `ExtractionBudget` to `read` to cap the cumulative output of several reads.
- `ArchiveEntry.size` stays a `Long` during parsing. Readers validate source
  ranges before allocation, including ZIP local-header and payload extents.
  Configured output limits apply first; a whole-array read above `Int.MAX_VALUE`
  then throws `ArchiveFormatException`. Larger valid entries can be listed from
  a seekable source, but extracting them to a stream is still planned.
- TAR validates every nonzero header checksum, accepting the standard unsigned
  sum and the legacy signed-byte sum. Complete records may end without the EOF
  trailer; a partial header or invalid checksum throws `ArchiveFormatException`
  at that record's offset. An empty source is accepted as an empty archive.
- TAR applies pax `x` records to the next member and `g` records to every later
  member. `path`, `size` and `mtime` override the ustar fields; pax names are
  UTF-8 unless `hdrcharset=BINARY`. Other keywords are ignored. A pax header
  above 16 MiB throws `ArchiveUnsupportedException`; a malformed record throws
  `ArchiveFormatException`.
- TAR sparse members (GNU `S`, pax GNU sparse 0.0, 0.1 and 1.0) list their
  expanded `size`, with `compressedSize` holding the stored bytes, and read with
  zero-filled holes. Holes count toward `maxUncompressedBytes` but not toward the
  compression ratio. An inconsistent sparse map throws `ArchiveFormatException`.
- `CpioReader` stops at the first trailer; the explicit `InitramfsReader` continues
  across uncompressed newc/crc segments. Both byte orders of the old binary dialect are
  recognized but listing throws `ArchiveUnsupportedException`. Empty input and
  unknown or truncated magic before the trailer throw `ArchiveFormatException`
  with the header offset, rather than returning an empty or partial listing.
- The LZ4 frame decoder consumes the complete sequence of concatenated standard
  frames and all 16 skippable-frame types, including a skippable-only sequence.
  Skippable payloads are ignored. Invalid or truncated trailing data throws
  `ArchiveFormatException`; the legacy frame magic throws
  `ArchiveUnsupportedException`. Empty input is malformed.
  The output limit covers the combined result; the compression-ratio limit applies
  separately to each standard frame's encoded length, excluding metadata and other
  frames. Skippable magics are shared with zstd, so a skippable prefix alone does
  not identify the format to the sniffer; choose the codec explicitly.
- The LZ4 frame decoder verifies the full descriptor checksum, checks each
  advertised block checksum before decoding/copying, and checks the advertised
  content checksum before returning output. Corrupt or truncated checksum fields
  throw `ArchiveFormatException` with their source offset.
- LZ4 frame version bits must be `01`; reserved FLG/BD bits must be zero.
  Dictionary-tagged self-contained blocks remain readable. A match requiring
  unavailable history names a declared unsigned Dictionary ID in
  `ArchiveUnsupportedException`. Without an ID, a malformed-data error explains
  that an external dictionary may be required; this cannot be distinguished from
  damaged match bytes. Supplying dictionary bytes is not implemented yet.
- LZ4 honors the block-independence flag. Linked blocks can refer to the preceding
  64 KiB of output within the same frame, including stored and compressed blocks.
  Matches can cross from that history into overlapping current-block output.
  Independent blocks and subsequent frames start without preceding-block history.
- A present LZ4 content-size field is checked against the remaining combined
  output limit before decoding and must equal that frame's output. Checksums,
  content sizes, and dictionary metadata are local to each frame. Compressed LZ4
  blocks must end in
  a literal-only sequence, with at least five final literals after a match.
  Empty output uses a zero token; an empty compressed byte sequence is invalid.
- Snappy exposes separate raw-block and framed-stream codecs. The raw encoder
  keeps one candidate per hash slot and extends matches greedily. Framed output
  uses chunks of at most 64 KiB with masked CRC-32C checksums and a stored fallback.
  Framed decoding accepts concatenated streams, repeated identifiers and skippable
  chunks, rejects reserved unskippable types, and enforces combined output limits.
- The public API is not frozen. Both modules use strict explicit API mode, and
  committed ABI dumps detect changes across every supported target. `Codec` and
  `ArchiveReader` will change when a streaming API arrives.

## Testing

The common tests run on every platform that can execute them: the JVM, the Android
host, Node.js, headless Chrome, Linux, Windows, macOS and the iOS, tvOS and watchOS
simulators. Android tests run on the host JVM against the Android target's compiled
code. They do not run on an emulator or device.

At the [Android host-test checkpoint](https://github.com/yuroyami/ArchiveKodec/issues/110),
the core has 503 common test methods and 39 JVM-only methods. The IO module has
five common test methods. The complete local verification lanes execute:

| Target | Core | IO |
|---|---:|---:|
| JVM | 542 | 5 |
| Android host | 503 | 5 |
| Node.js | 503 | no IO target |
| arm64 iOS simulator | 503 | 5 |

These are test-method counts at that checkpoint; loops within methods and the
Swift consumer checks exercise additional cases. The gates read the XML reports
for the current counts as coverage grows.

The shared suites cover:

- DEFLATE round-trips.
- Dynamic Huffman on skewed alphabets.
- Length-limited Huffman code-space checks, exhaustive small-alphabet optimality,
  Zopfli weighted-cost answers, and the million-byte DEFLATE/gzip/zlib/ZIP regression.
- gzip and zlib decoding of vectors emitted by CPython.
- A ZIP produced by CPython `zipfile`.
- An LZ4 frame produced by the `lz4` CLI.
- Snappy, ar and cpio hand-written test vectors.
- Magic sniffing, checksum check values and `PathGuard`.
- Both facade overloads opening foreign v7/ustar/PAX/GNU TAR, GNU ar, newc/odc
  cpio and ZIP64 fixtures, including a GNU binary timestamp and long names.
- All four IO adapters across the 64 KiB boundary, irregular short reads,
  output limits and source ownership.

JVM-only suites test interoperability. `ZlibInteropTest` feeds our raw
DEFLATE, zlib and gzip output to `java.util.zip.Inflater` and `GZIPInputStream`.
The Inflater helper requires stream completion and full input consumption;
the JDK also validates the wrapper checksums and gzip size.
`WrapperDecodeInteropTest` decodes JDK-produced raw DEFLATE/zlib/gzip streams,
compares consumed cursors, checks the static JVM default-argument bridges, and measures small-cap
bomb rejection without allocating the expanded payload. `ZipInteropTest`
round-trips ZIPs in both directions against
`java.util.zip`, including a 70,000-entry ZIP64 archive.
`JavaInteropTest` executes Java source covering static utility calls, interface
overloads, reader limits, builders, checked exceptions and resource cleanup.

Real-world data comes from the standard compression corpora. `CorpusSliceTest`
runs on every target over slices of four Canterbury Corpus files (text, a SPARC
binary, a spreadsheet and C source, each over 32 KiB where the file allows): every
encoder must round-trip them and, except STORE and raw Snappy, make them smaller, and
DEFLATE runs every level at two window sizes. On the JVM, `CorpusRoundTripTest` does
the same over the whole Canterbury Corpus (2.8 MB, committed under
`archivekodec/testResources@jvm/canterbury` with SHA-256 sums), and decodes our DEFLATE, zlib
and gzip with `java.util.zip` and the JDK's with ours, at levels 1, 6 and 9, holding
our size within 0.5% of zlib's. The 212 MB Silesia Corpus is too large to commit, so
it runs on request; every encoder and the JDK checks take about 11 minutes on the JVM:

```bash
scripts/fetch-silesia.sh /tmp/silesia
ARCHIVEKODEC_SILESIA=/tmp/silesia ./kotlin test -m archivekodec -p jvm --include-classes='*CorpusRoundTripTest'
```

`XxHash32Test` checks upstream known-answer vectors across accumulator and tail
boundaries, unsigned results, nonzero seeds and unaligned slices. The expected
values are independent of the Kotlin implementation.

`./kotlin test -p wasmJs` runs the wasmJs suites in headless Chrome, which the
Kotlin Toolchain downloads. In the browser, the `-io` file tests check that
`FileRandomAccessSource` reports unsupported, and stop there. `./kotlin test` cannot
run the JS and wasmWasi suites yet. `python3 integration-tests/web-tests/run.py` links
both and runs them in Node.

The unpublished `benchmarks` module measures the same fixed corpora on JVM and
Node, with JDK checksum and DEFLATE references. Run
`python3 benchmarks/run.py --check` for the smoke comparison or
`python3 benchmarks/run.py --profile full` for larger inputs and longer sampling.
See [benchmark methodology and baseline](docs/benchmarks.md) before comparing rates.

## Building

The build uses the [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain).
`./kotlin` (or `kotlin.bat` on Windows) downloads the toolchain, the JDKs, Kotlin/Native,
Node and Chrome on first use. `project.yaml` lists the modules. Each module has a
`module.yaml`, and the three published modules share `library.module-template.yaml`.

```bash
./kotlin build                          # all modules, all platforms this host can build
./kotlin test -p jvm -p android         # some platforms; -m archivekodec for one module
./kotlin check abi klibAbi version      # the ABI and version checks
python3 integration-tests/web-tests/run.py       # JS and wasmWasi tests in Node
python3 integration-tests/swift-consumer/run.py  # macOS arm64: compile and run the Swift consumers
```

You need the Android SDK. Set `ANDROID_HOME` to your SDK install. The toolchain does
not read `local.properties`.

Local build plugins in `build-plugins/` add the parts that the toolchain does not
build by itself:

| Plugin | What it adds |
|---|---|
| `abi-check` | The `abi` and `klibAbi` checks, and the `updateAbi` and `updateKlibAbi` commands |
| `dokka` | The `dokka` and `dokkaSite` commands, which write the API reference to `build/dokka/html` |
| `jvm-module` | A `module-info.class` in the JVM jars of the core and the `-io` artifact |
| `version-constant` | The `version` check and the `updateVersion` command for `ArchiveKodec.VERSION` |

Two scripts build the outputs that no plugin can make. `scripts/apple_frameworks.py`
links the Apple frameworks and XCFrameworks (see [Swift](#swift)).
`scripts/js_package.py` assembles the JavaScript module (see
[JavaScript and TypeScript](#javascript-and-typescript)).

The ABI references in each module's `api/` directory cover the JVM classes and the
merged klib declarations of every other platform. The Android artifact contains the
JVM classes, so it has no reference of its own. The klib check reads the klibs of all
platforms, so run `./kotlin build` on macOS first.

```bash
./kotlin do updateAbi                   # deliberate reference update; review and commit its diff
./kotlin do updateKlibAbi
```

The version is `publishing.version` in `library.module-template.yaml`. The same file
repeats it as the `archivekodec.expectedVersion` test property, which `VersionTest`
compares with `ArchiveKodec.VERSION`. Development builds carry a `-SNAPSHOT` suffix. `./kotlin do updateVersion` writes
`ArchiveKodec.VERSION` from it, and `./kotlin check version` fails when the two differ.
The portable CI lane fails unless every coordinate in this README and on the docs
landing page names the same release. To release, drop the suffix, publish, then set
the next `-SNAPSHOT` and update the README snippets in the same commit.

Update a reference only alongside an intentional API change, with that change
named in the commit and release notes. See [the API baseline records](api/README.md)
for the frozen, checksum-verified 0.1.0 artifacts under the original `kitearchive`
coordinates. Those historical dumps use the old namespace; the current references
record ArchiveKodec after its rename and fixes. API pages link declarations to their
source files on `main`.

Pull requests and main pushes run the `CI` workflow without path filters. Its
`Verification` check requires all three platform lanes and the benchmark check to
succeed. Each lane runs `integration-tests/ci/run.py`.

- The Linux lane runs the JVM, Android host, wasmJs and `linuxX64` suites, and the
  JS and wasmWasi suites in Node. It compiles `linuxArm64` and the four androidNative
  platforms. It runs the JVM ABI check and the version check, checks the class file
  versions and the module descriptors, compiles the downstream Java check, and runs
  the JavaScript and TypeScript consumers.
- The macOS lane builds every platform, runs the iOS, macOS, tvOS and watchOS
  simulator suites and the klib ABI check, and runs the Swift consumers and the
  SwiftPM package over the release XCFrameworks. Device and x64 frameworks are
  checked for compilation and exported headers. Runtime checks use the arm64 iOS
  simulator.
- The Windows lane runs the `mingwX64` suites.

Android device or emulator tests are not configured in these lanes.
Pages builds and deploys only after verification succeeds for the same main commit.
`Verification` is the
stable check to select in branch protection.

Run the same lanes locally, with the Android SDK in `ANDROID_HOME`:

```bash
python3 integration-tests/ci/run.py portable
python3 integration-tests/ci/run.py apple # macOS arm64, Xcode and the simulator runtimes
python integration-tests/ci/run.py windows # Windows x64
```

The workflow's manual `failure_canary` input inserts a deliberately failing JVM
test into the checkout and must produce a failed portable lane and `Verification`
check. It always disables Pages deployment. The fixture is removed after execution
and is never committed. To check the failure path locally, run
`python3 integration-tests/ci/run.py portable --failure-canary`, expect a nonzero
exit with `CiFailureCanaryTest.deliberateFailure`, then run the normal portable
command to restore passing reports. Hosted run results remain separate evidence
from local command validation.

## What is planned

The table at the top lists what works today. Everything still missing has its own
issue, so the tracker is the current answer instead of a file that goes stale
between releases.

- [Streaming and extraction](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Astreaming-api): reading from a file, and
  extracting without holding the whole archive in memory.
- [Security hardening](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Asecurity-hardening): one issue per guard listed under
  Limits.
- [More containers](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Amore-containers): ISO 9660, SquashFS, CAB and WIM (all read).

Writing RAR, and reading ACE, StuffIt and ALZ, stay out of scope. See License below.

## License

Apache-2.0. See [LICENSE](LICENSE).

ArchiveKodec combines specification implementations with ports of permissively
licensed code. The inflate path is an altered Kotlin translation of Mark Adler's
zlib-licensed `puff`, via KiteTorrent and libtorrent. The encoder's canonical Huffman
assignment follows zlib's `trees.c`; both source files retain the zlib notices.
Its length-limited builder independently implements binary package-merge, with
Zopfli consulted as an algorithm and validation reference.
[NOTICE](NOTICE) and [reference/REFERENCES.md](reference/REFERENCES.md) record the
origins and licenses. Reference source trees are used for study and are not
redistributed. RAR archives are read from RARLAB's published format description,
not UnRAR source, and ArchiveKodec will never create RAR archives. ACE, StuffIt
and ALZ are proprietary with no clean reference to work from, so they are out of
scope.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[ImageKodec](https://github.com/yuroyami/ImageKodec),
[KiteQR](https://github.com/yuroyami/KiteQR).
