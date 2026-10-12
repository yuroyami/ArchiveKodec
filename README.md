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

0.2.0 is not published to Maven Central yet. These are the coordinates it will use:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:archivekodec:0.2.0")
            // Optional: a file source, extract and kotlinx-io adapters.
            implementation("io.github.yuroyami:archivekodec-io:0.2.0")
        }
        // Optional: AES for encrypted ZIP and 7z archives. Not on JS or Wasm.
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

`open` detects the format and removes one gzip layer, so a `.tar.gz` opens in one call. A
listing refuses names such as `../../etc/passwd` by default, but always build the output path
with `PathGuard.sanitize`. See [Reading archives](docs/reading.md) and
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
ArchiveKodec.open(FileRandomAccessSource(path)).use { reader ->
    reader.extract(reader.entries()) { entry ->
        SystemFileSystem.sink(Path(outDir, PathGuard.sanitize(entry.name)))
    }
}
```

`archivekodec-io` reads the file on demand, so a large archive never sits in memory.
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
- **Archives, read and write:** ZIP and ZIP64, TAR, GNU ar and cpio.
- **Archives, read only:** 7z, RAR4 and RAR5, ISO 9660, CAB, WIM and SquashFS.
- **Checksums:** CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32.
- **Encryption:** WinZip AES read and write, ZipCrypto and 7z AES read.

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
builds for all of them except `js`, `wasmJs` and `wasmWasi`. In a browser and on WASI the file
source cannot open a file, so read the file into a `ByteArray` there.

## Limits

- Codecs and most entry reads work on whole arrays, so an entry must fit one `ByteArray`.
  ZIP members that are stored, DEFLATE or Deflate64 can stream to a sink instead.
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
