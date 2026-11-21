# ArchiveKodec

Compression and archive formats in pure Kotlin, for Kotlin Multiplatform. Gunzip a buffer or
read a ZIP on iOS and in the browser, as well as on the JVM, from one shared codebase.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the
direction and review of its author.</sub>

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](docs/index.md)**: a guide for each task, the format tables and the limits.

`java.util.zip` exists on the JVM and Android and nowhere else. A Kotlin Multiplatform project
that reads or writes archives usually adds a cinterop binding on iOS and a JavaScript shim on
the web, which means a native build and a different code path for each target. ArchiveKodec
implements the formats in `commonMain` instead. There is no JNI, no cinterop and no native
binary, and the core artifact depends on `kotlin-stdlib` and nothing else.

## Install

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:archivekodec:0.2.0")
            // Optional: a file source, extract and kotlinx-io adapters.
            implementation("io.github.yuroyami:archivekodec-io:0.2.0")
        }
        // Optional: the platform's AES, faster for large encrypted archives. Not on JS or Wasm.
        jvmMain.dependencies {
            implementation("io.github.yuroyami:archivekodec-crypto:0.2.0")
        }
    }
}
```

The libraries need Kotlin 2.1 or newer, Java 8 or newer on the JVM and Android minSdk 21.
[Install](docs/index.md#install) covers the Kotlin Toolchain and Maven. 0.1.0 is on Maven
Central under the older name `io.github.yuroyami:kitearchive`.

## Usage

### Compress and decompress

```kotlin
val gz = ArchiveKodec.gzip.compress(bytes)
val raw = ArchiveKodec.deflate.compress(bytes)
val back = ArchiveKodec.decompress(gz) // detects gzip, zlib, bzip2, xz, zstd, LZ4 and framed Snappy
```

Every codec takes a `ByteArray` and returns one. DEFLATE, zlib and gzip can also stream their
output to a sink. See [Compression](docs/compression.md).

### Read an archive

```kotlin
ArchiveKodec.open(bytes).use { reader ->
    for (entry in reader.entries()) {
        if (entry.isDirectory) continue
        val content = reader.read(entry)
        val path = PathGuard.sanitize(entry.name) // a safe relative path for this entry
    }
}
```

`open` detects the format and removes one compression layer, so a `.tar.gz` or `.tar.xz` opens
in one call. A listing refuses names such as `../../etc/passwd` by default, but always build
the output path with `PathGuard.sanitize`. See [Reading archives](docs/reading.md) and
[Entry names and Zip-Slip](docs/entry-names.md).

### Write a ZIP

```kotlin
val zip = ZipWriter.write(
    listOf(
        ZipWriter.FileSpec("readme.txt", text, ZipMethod.STORED),
        ZipWriter.FileSpec("data.bin", payload),  // deflated
    ),
)
```

TAR, GNU ar and cpio have writers too. See [Writing archives](docs/writing.md).

### Extract a file from disk

```kotlin
val source = FileRandomAccessSource(path)
// The reader closes the source, but only once it exists: close it yourself if open throws.
val reader = try { ArchiveKodec.open(source) } catch (e: Throwable) { source.close(); throw e }
reader.use {
    reader.extract(reader.entries()) { entry ->
        val target = Path(outDir, PathGuard.sanitize(entry.name))
        target.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(target)
    }
}
```

`archivekodec-io` reads the file on demand, so a large ZIP, TAR, 7z or other seekable
archive is listed and read without loading it. A compressed layer such as `.tar.gz` is read
and decoded whole in memory by `open`; `TarStreamSink` streams one instead
([Compression](docs/compression.md#stream-both-sides)).
`extract` reports progress, stops when its coroutine is cancelled and skips symbolic links by
default. See [Files and extraction](docs/files.md).

### More

[Encrypted archives](docs/encryption.md), [limits](docs/limits.md) and
[custom codecs and formats](docs/extending.md). Java, Swift and JavaScript have their own
pages: [Java](docs/java.md), [Swift](docs/swift.md) and
[JavaScript and TypeScript](docs/javascript.md).

## Formats

- **Codecs, both directions:** DEFLATE, Deflate64, gzip, zlib, bzip2, LZMA, LZMA2, xz, zstd,
  brotli, PPMd, LZ4 and Snappy.
- **Image and e-book codecs:** LZW (PDF, TIFF and GIF), PackBits and PalmDOC in both
  directions, and MOBI's HUFF/CDIC decode.
- **Archives, read and write:** ZIP and ZIP64, TAR, GNU ar and cpio.
- **Archives, read only:** 7z, RAR4 and RAR5, ISO 9660, CAB, WIM and SquashFS.
- **Checksums:** CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32.
- **Encryption:** WinZip AES read and write, ZipCrypto and 7z AES read, on every target
  with the built-in constant-time AES.

[Format support](docs/formats.md) lists what each codec and reader handles, and what it
refuses.

## Platforms

| Family | Targets |
| --- | --- |
| Android, JVM | Android (minSdk 21), `jvm` (Java 8 bytecode) |
| Apple | `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`, `tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| Other native | `linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64` |
| Web | `js` and `wasmJs` (browser and Node), `wasmWasi` (Node) |

The core and `archivekodec-io` build for every target in the table. `archivekodec-crypto`
builds for all of them except `js`, `wasmJs` and `wasmWasi`, and the core reads and writes
encrypted archives on every target without it. In a browser and on WASI the file
source cannot open a file, so read the file into a `ByteArray` there.

## Limits

- What streams, in memory that does not grow with the data: `decompress(source, sink)` for
  DEFLATE, zlib, gzip, bzip2, xz, zstd, LZ4, lzip, `.lzma`, Unix compress and lzop; the
  `encoder` of DEFLATE, zlib, gzip, bzip2, xz, zstd, LZ4 and lzip; `ZipReader.read(entry, sink)`
  for unencrypted stored, DEFLATE, Deflate64, bzip2, zstd and xz members; the TAR, ar and cpio
  stream readers and writers, `TarStreamSink` and `ZipStreamWriter`.
- Everything else works on whole arrays, so its input and output must each fit one
  `ByteArray`: the other codecs, `read(entry)` into an array, the other ZIP members and the
  entries of the other archive readers (read whole even into a sink), and the compression
  layer `open` removes.
- The encoders are not tuned to match the reference tools: [Compression ratios](docs/compression.md#compression-ratios)
  gives the measured sizes, and [Memory](docs/compression.md#memory) what each codec holds. No
  stream they write declares a window or dictionary above 8 MiB.
- The default compression ratio limit of 10,000:1 rejects valid bzip2, zstd and brotli files
  with long runs of one byte. [Limits](docs/limits.md) shows how to raise it.
- The Swift frameworks and the JavaScript module are built from this repository. They are not
  on Swift Package Manager or npm yet.
- The public API is not frozen. `Codec` and `ArchiveReader` can gain members, so implementing
  them needs an opt-in.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers the build, the tests and the CI lanes. Open work is
in [GitHub Issues](https://github.com/yuroyami/ArchiveKodec/issues).

## License

Apache-2.0. See [LICENSE](LICENSE).

ArchiveKodec combines implementations of the format specifications with ports of permissively
licensed code. The inflate path is an altered Kotlin translation of Mark Adler's zlib-licensed
`puff`, and the encoder's canonical Huffman codes follow zlib's `trees.c`. PPMd is ported from
the public-domain `Ppmd7.c` and `Ppmd8.c` of Igor Pavlov's LZMA SDK. [NOTICE](NOTICE) and
[reference/REFERENCES.md](reference/REFERENCES.md) record the origins and licenses.

RAR archives are read from RARLAB's published format description, not from UnRAR source, and
ArchiveKodec will never create RAR archives.

Part of the Kite family: [KiteCore](https://github.com/yuroyami/KiteCore),
[KitePDF](https://github.com/yuroyami/KitePDF),
[ImageKodec](https://github.com/yuroyami/ImageKodec),
[AudioKodec](https://github.com/yuroyami/AudioKodec),
[KiteQR](https://github.com/yuroyami/KiteQR).
