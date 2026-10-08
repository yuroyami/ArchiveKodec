# ArchiveKodec

Compression and archive formats in pure Kotlin, for Kotlin Multiplatform. Gunzip
a buffer or read a ZIP on iOS and in the browser, as well as on the JVM, from one
shared codebase.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yuroyami/archivekodec)](https://central.sonatype.com/artifact/io.github.yuroyami/archivekodec)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Targets](https://img.shields.io/badge/targets-Android%20%7C%20Apple%20%7C%20JVM%20%7C%20JS%20%7C%20Wasm%20%7C%20Linux%20%7C%20Windows-blue)](#targets)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/ArchiveKodec/)** · an overview with
examples, plus the generated API reference.

## Install

Both artifacts are on Maven Central under the group `io.github.yuroyami`. Versions
before 0.2.0 use older coordinates: 0.1.0 is `io.github.yuroyami:kitearchive`.

| Artifact | Coordinates | What it holds |
| --- | --- | --- |
| Core | `io.github.yuroyami:archivekodec:0.2.0` | Codecs, archive readers, ZIP and ar writers, checksums, the sniffer and the safety checks. Depends on `kotlin-stdlib` only |
| IO adapters | `io.github.yuroyami:archivekodec-io:0.2.0` | Four adapters over kotlinx-io `RawSource`. Adds `kotlinx-io-core` and the core as API dependencies |

Both build for every target in [Targets](#targets).

Kotlin Multiplatform, in the shared source set:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:archivekodec:0.2.0")
            // Only if you already use kotlinx-io. See "The -io artifact".
            implementation("io.github.yuroyami:archivekodec-io:0.2.0")
        }
    }
}
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
  (major version 52), compiled against the Java 8 API with `-Xjdk-release=1.8`,
  the same floor as `kotlin-stdlib` and kotlinx-io. Building the libraries
  themselves needs JDK 21.
- **Android minSdk 21** or newer.
- JPMS module names: `io.github.yuroyami.archivekodec` and
  `io.github.yuroyami.archivekodec.io` (`Automatic-Module-Name`).

Callers outside Kotlin:

- **Java** uses the JVM artifact directly. Kotlin objects are reached through
  `INSTANCE` (`ArchiveKodec.INSTANCE.getGzip()`), and top-level functions through
  their `Kt` file classes (`ArchiveKodecIoKt.readAllBytes(source)`). Throwing
  methods declare their checked archive exceptions.
- **Swift** has no published framework or XCFramework yet. Build the `ArchiveKodec`
  or `ArchiveKodecIo` framework from this repository
  (`./gradlew :archivekodec:linkReleaseFrameworkIosArm64`), or depend on the
  artifact from a Kotlin Multiplatform module that your app already exports.
  [Expected failures and Apple consumers](#expected-failures-and-apple-consumers)
  shows the error bridge.
- **JavaScript and TypeScript** cannot call the library directly. No declaration
  is exported with `@JsExport`, so it is usable from Kotlin/JS and Kotlin/Wasm code only.

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
| Codecs | DEFLATE both directions, gzip and zlib framing, LZ4 and Snappy both directions |
| Archives | ZIP read and write including ZIP64, GNU ar read and write, plus TAR and cpio readers |
| Checksums | CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32 |
| Detection | A sniffer that names a format from its first bytes |

Two things apply to every entry point. First, everything is whole-array. You
pass a `ByteArray` and get a `ByteArray` back, so the whole payload stays in
memory, and there is no streaming API. Second, the readers do not sanitize entry
names. You must call `PathGuard` yourself before you use a name as a path.

```kotlin
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.security.PathGuard

val gz = ArchiveKodec.gzip.compress("hello".encodeToByteArray())
val back = ArchiveKodec.gzip.decompress(gz)

val zipBytes = ZipWriter.write(
    listOf(ZipWriter.FileSpec("hello.txt", "Hello!".encodeToByteArray(), ZipMethod.STORED)),
)

ArchiveKodec.open(ByteArrayRandomAccessSource(zipBytes)).use { reader ->
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

val lz4 = ArchiveKodec.codec(CodecId.LZ4)      // null for an unimplemented id
val out = lz4!!.decompress(frameBytes)
```

| Codec | Decode | Encode |
| --- | --- | --- |
| store | yes | yes |
| DEFLATE (RFC 1951) | yes | yes (fixed and dynamic Huffman, whichever is smaller) |
| zlib (RFC 1950) | yes | yes |
| gzip (RFC 1952) | all members, with header/trailer integrity checks | yes |
| LZ4 (frame and block) | yes | yes |
| Snappy (raw block) | yes | yes, with greedy back-reference matching |
| Snappy (framed stream) | yes, with CRC-32C validation | yes, with stored-chunk fallback |

Dynamic DEFLATE trees use binary package-merge to minimize weighted code length
within the 15-bit literal/distance and 7-bit code-length limits. The encoder checks
symbol coverage and code-space occupancy before selecting a table. Raw DEFLATE,
gzip, zlib and deflated ZIP all share this builder; compressed bytes are deterministic
for the same input, but may change when encoder behavior is corrected.

`gzip`, `zlib`, `deflate` and `store` have named accessors on `ArchiveKodec`. You
reach LZ4 and Snappy through `ArchiveKodec.codec(id)`.

`CodecId.SNAPPY` and `Codecs.SNAPPY` select raw Snappy blocks.
`CodecId.SNAPPY_FRAMED` and `Codecs.SNAPPY_FRAMED` select standard `.sz` framing.
The low-level `Snappy` object also exposes `frameCompress` and `frameDecompress`.

`CodecId` also carries entries for bzip2, LZMA, LZMA2, xz, zstd, brotli and PPMd.
None of those have an implementation. `ArchiveKodec.codec(...)` returns `null` for
them. A ZIP entry that uses one of them appears in the entry list, but reading it
throws.

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
import ArchiveKodec

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

## Read an archive

```kotlin
val reader = ArchiveKodec.open(ByteArrayRandomAccessSource(bytes))
reader.entries()          // parsed from the header or central directory only
reader.read(entry)        // decode one entry
```

`open` sniffs the format and returns a `ZipReader`, `TarReader`, `ArReader` or
`CpioReader`. When the header is unknown, all `open` overloads search the last
65,557 bytes for a ZIP end record with a complete EOF-terminated comment. This
opens zipapps and other prefixed ZIPs; recognized header formats retain priority.
An unknown source throws `ArchiveFormatException`, and a recognized format
without a reader throws `ArchiveUnsupportedException`. `entries()`
does not decompress entries. ZIP listing validates the central directory and
local headers, so custom sources must support those metadata reads. `read` seeks
to one local header for a single-entry extract.

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
read, and separate calls do not share an archive-wide output budget.

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
view and Java `try`-with-resources through `ArchiveReaderKt.asAutoCloseable(reader)`.
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
physical source positions, including `ArchiveEntry.dataOffset` for local headers.
ZIP64 end records may contain a variable-length extensible sector; searches use
bounded chunks rather than a fixed 56-byte assumption. Existing absolute-offset
directory and locator gaps remain readable. Shifted local headers and payloads
still undergo the same range, overlap, metadata, size, ratio and CRC32 checks.

Entries with encryption bit 0, strong-encryption bit 6, or the AES method marker
99 have `isEncrypted = true`. Reading them throws `ArchiveUnsupportedException`
before decoding ciphertext. Compressed patched data (bit 5) is likewise
unsupported. Masked local headers used with central-directory encryption
(bit 13) throw `ArchiveUnsupportedException` during listing. The public codec
field still falls back to STORE for an unknown numeric ZIP method; reading that
entry throws an unsupported-method error.

| Format | Read | Write |
| --- | --- | --- |
| ZIP and ZIP64 | yes (STORE and DEFLATE) | yes (STORE and DEFLATE) |
| TAR (v7, ustar, GNU) | yes | no |
| ar (GNU) | yes | yes |
| cpio (newc, crc, odc) | yes | no |

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
and read. `ByteArrayRandomAccessSource` is the only implementation in the
library. The whole archive must therefore be in memory before you can open it.

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
Both headers use the reproducible default timestamp 1980-01-01 00:00:00.

## Sanitize entry names before writing them

`ZipReader`, `TarReader`, `ArReader` and `CpioReader` return decoded
`ArchiveEntry.name` without path sanitization. A name of
`../../etc/passwd` or `/etc/passwd` arrives unchanged. Directory names retain
every stored trailing slash, including `e//` and `./`. Use `isDirectory` to
identify directories; a type-5 TAR directory need not end in a slash.

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
The property is null on manually constructed entries. `ArchiveEntry` is an
ordinary class retaining its original constructor, `copy`, destructuring,
equality, hash and string representation; copies preserve the stored-byte
snapshot. Stored bytes do not participate in the original value equality.

Joining such a name to an output directory is the Zip-Slip vulnerability.
Zip-Slip means an archive entry escapes the folder you meant to extract into, and
overwrites a file somewhere else on disk. The library will not stop you.

`PathGuard` is the check, and it only runs when you call it:

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
RAR and Snappy framing. `ArchiveFormat.SNAPPY` identifies framed input; decode
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
codecs validate the remaining structure and payload. The sniffer names xz, zstd,
bzip2, 7z and RAR, and ArchiveKodec cannot decode any of those five formats.

## Targets

Both artifacts build for the same targets, so both can go in one `commonMain`.
The matrix covers every target KitePDF and ImageKodec declare. Neither artifact has
platform-specific code, so each target compiles the same common sources.

| Target | Both artifacts |
| --- | --- |
| Android, minSdk 21, compileSdk 36, Java 8 bytecode | yes |
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

`archivekodec-io` is one common source file with four functions:

```kotlin
fun RawSource.readAllBytes(): ByteArray
fun RawSource.toRandomAccessSource(): RandomAccessSource
fun Codec.compress(source: RawSource): ByteArray
fun Codec.decompress(source: RawSource, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray
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
- `PathGuard` is not wired into any reader. Path safety is the caller's
  responsibility. See
  [Sanitize entry names before writing them](#sanitize-entry-names-before-writing-them).
- Built-in reader listings enforce `maxEntries`; buffered APIs do not recurse
  into nested archives, so `maxNestingDepth` remains reserved. Every implemented codec
  applies `checkRatio` before output allocation or growth. ZIP checks each entry;
  uncompressed TAR/ar/cpio reads check their known 1:1 ratio.
- `ArchiveEntry.size` stays a `Long` during parsing. Readers validate source
  ranges before allocation, including ZIP local-header and payload extents.
  Configured output limits apply first; a whole-array read above `Int.MAX_VALUE`
  then throws `ArchiveFormatException`. Larger valid entries can be listed from
  a seekable source, but extracting them to a stream is still planned.
- TAR validates every nonzero header checksum, accepting the standard unsigned
  sum and the legacy signed-byte sum. Complete records may end without the EOF
  trailer; a partial header or invalid checksum throws `ArchiveFormatException`
  at that record's offset. An empty source is accepted as an empty archive.
- TAR skips pax `x` and `g` extended headers and does not handle sparse files.
- cpio stops at the first trailer. Both byte orders of the old binary dialect are
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

`commonTest` runs on the JVM, Android host, Node.js and arm64 iOS simulator.
Android host tests run on the host JVM against the Android target's compiled
code; they do not run on an emulator or device. Both modules enable these tests
with the Android KMP plugin's `withHostTest {}` option.

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
compares consumed cursors, checks legacy JVM method linking, and measures small-cap
bomb rejection without allocating the expanded payload. `ZipInteropTest`
round-trips ZIPs in both directions against
`java.util.zip`, including a 70,000-entry ZIP64 archive.

`XxHash32Test` checks upstream known-answer vectors across accumulator and tail
boundaries, unsigned results, nonzero seeds and unaligned slices. The expected
values are independent of the Kotlin implementation.

The unpublished `benchmarks` module measures the same fixed corpora on JVM and
Node, with JDK checksum and DEFLATE references. Run
`python3 benchmarks/run.py --check` for the smoke comparison or
`python3 benchmarks/run.py --profile full` for larger inputs and longer sampling.
See [benchmark methodology and baseline](docs/benchmarks.md) before comparing rates.

## Building

The ABI references in each module's `api/` directory cover JVM classes, the
actual Android AAR classes and the merged klib declarations of every other target.
Both modules' `check` tasks require ABI checks. The Linux and Apple CI lanes both
check the complete target matrix without inferring unsupported targets. Kotlin/Native
cross-compiles the Apple klibs on Linux, so a complete ABI check needs the Android
SDK and no Apple toolchain.

```bash
./gradlew checkPortableAbi            # JVM JARs and Android AARs
./gradlew checkKotlinAbi              # complete current ABI matrix
./gradlew checkLegacyAbi              # compatibility name for the complete check
./gradlew updateKotlinAbi             # deliberate reference update; review and commit its diff
```

The version is written once, as `version` in `gradle.properties`. Development
builds carry a `-SNAPSHOT` suffix. `ArchiveKodec.VERSION` is generated from it,
and `./gradlew checkReadmeVersion` (part of `check` and the portable CI lane) fails
unless every coordinate in this README and on the docs landing page names the
same release. To release, drop
the suffix, publish, then set the next `-SNAPSHOT` and update the README snippets
in the same commit.

Update a reference only alongside an intentional API change, with that change
named in the commit and release notes. See [the API baseline records](api/README.md)
for the frozen, checksum-verified 0.1.0 artifacts under the original `kitearchive`
coordinates. Those historical dumps use the old namespace; the current references
record ArchiveKodec after its rename and fixes.

```bash
./gradlew :archivekodec:jvmTest         # core test suite
./gradlew :archivekodec:testAndroidHostTest :archivekodec-io:testAndroidHostTest
./gradlew swiftConsumerTest             # macOS arm64: compile and run core/IO Swift consumers
./gradlew :archivekodec-io:jvmTest      # kotlinx-io adapter tests
./gradlew :io-consumer-test:compileJava # downstream IO API compile check
./gradlew build                        # all modules, all targets
```

You need JDK 21 and the Android SDK. Point `sdk.dir` in `local.properties` at
your SDK install.

The committed `kotlin-js-store/yarn.lock` pins the npm dependencies used by the
JavaScript toolchain. When deliberately updating them, run
`./gradlew kotlinUpgradeYarnLock`, review the lockfile diff and commit it with
the build change. API pages link declarations to their source files on `main`.

Pull requests and main pushes run the `CI` workflow without path filters. Its
`Verification` check requires all three platform lanes to succeed. The Linux lane
runs the JVM, Android host, JS Node, wasmJs Node, wasmWasi Node and `linuxX64`
suites of both modules. It compiles `linuxArm64` and the four androidNative
targets, and builds both Android AARs/JVM JARs, the core JS distribution and the
downstream Java compile check. The macOS lane runs the iOS, macOS, tvOS and
watchOS simulator suites, compiles the Apple device targets and runs the
independent Swift consumers. The Windows lane runs both `mingwX64` suites.
All six Apple frameworks are built; device and x64 checks cover compilation and
exported headers, while runtime checks use the arm64 iOS simulator. Android
verification includes both host-test suites, compilation and packaging. Android
device/emulator tests and JS browser tests are not configured in these lanes.
Pages builds and deploys only after verification succeeds for the same main commit.
`Verification` is the
stable check to select in branch protection.

Run the identical commands locally with JDK 21 and Android SDK 36 installed:

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

- [Missing codecs](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Amore-codecs): bzip2, zstd, brotli and PPMd.
- [LZMA, xz and 7z](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Alzma-family).
- [Streaming and extraction](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Astreaming-api): reading from a file, and
  extracting without holding the whole archive in memory.
- [Security hardening](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Asecurity-hardening): one issue per guard listed under
  Limits.
- [Writers](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Acontainer-writers) for TAR and cpio.
- [More containers](https://github.com/yuroyami/ArchiveKodec/labels/plan%3Amore-containers): ISO 9660, SquashFS, CAB and WIM.

RAR, ACE, StuffIt and ALZ stay out of scope. See License below.

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
redistributed. RAR is out of scope for licensing reasons, and ArchiveKodec
will never create RAR archives. ACE, StuffIt and ALZ are proprietary with no
clean reference to work from, so they are out of scope too.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[ImageKodec](https://github.com/yuroyami/ImageKodec),
[KiteQR](https://github.com/yuroyami/KiteQR).
