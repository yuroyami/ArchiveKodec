<div class="kite-hero" markdown>

# ArchiveKodec

Compression and archive formats in pure Kotlin, for Kotlin Multiplatform. Gunzip a buffer or
read a ZIP on iOS and in the browser, as well as on the JVM, from one shared codebase.

<div class="kite-hero-actions" markdown>
[Get started](#install){ .kite-primary }
[API reference](api/)
[GitHub](https://github.com/yuroyami/ArchiveKodec)
</div>

</div>

`java.util.zip` exists on the JVM and Android and nowhere else, so a multiplatform project
that reads archives usually adds a cinterop binding on iOS and a JavaScript shim on the web.
ArchiveKodec implements the formats in common Kotlin instead, with no JNI, no cinterop and no
native binary. The core artifact depends on `kotlin-stdlib` and nothing else.

## Install

0.2.0 is not published to Maven Central yet. These are the coordinates it will use:

| Artifact | Coordinates | What it adds |
| --- | --- | --- |
| Core | `io.github.yuroyami:archivekodec:0.2.0` | Codecs, archive readers and writers, checksums, the sniffer and the limits |
| Files | `io.github.yuroyami:archivekodec-io:0.2.0` | A file source, `extract` and kotlinx-io adapters |
| Crypto | `io.github.yuroyami:archivekodec-crypto:0.2.0` | Platform AES for encrypted ZIP and 7z archives |

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:archivekodec:0.2.0")
            implementation("io.github.yuroyami:archivekodec-io:0.2.0")
        }
        // archivekodec-crypto has no JS or Wasm build.
        jvmMain.dependencies {
            implementation("io.github.yuroyami:archivekodec-crypto:0.2.0")
        }
    }
}
```

A [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain) project lists the same
coordinates in its `module.yaml`:

```yaml
dependencies:
  - io.github.yuroyami:archivekodec:0.2.0
  - io.github.yuroyami:archivekodec-io:0.2.0
```

A JVM or Android project that is not multiplatform uses the same coordinates, and Gradle picks
the platform variant. Maven does not read Gradle module metadata, so a Maven project depends on
the platform artifact (`archivekodec-io-jvm` for the files module):

```xml
<dependency>
  <groupId>io.github.yuroyami</groupId>
  <artifactId>archivekodec-jvm</artifactId>
  <version>0.2.0</version>
</dependency>
```

The requirements:

- Kotlin 2.1 or newer. The libraries are compiled at language and API version 2.2, which a
  compiler one minor version older can read.
- Java 8 or newer on the JVM.
- Android minSdk 21 or newer.

0.1.0 is on Maven Central under the older name `io.github.yuroyami:kitearchive:0.1.0`.

## A first example

```kotlin
val gz = ArchiveKodec.gzip.compress("hello".encodeToByteArray())
val back = ArchiveKodec.decompress(gz) // detects gzip

val zipBytes = ZipWriter.write(listOf(ZipWriter.FileSpec("hello.txt", back)))

ArchiveKodec.open(zipBytes).use { reader ->
    for (entry in reader.entries()) {
        if (PathGuard.isRootDirectory(entry)) continue
        val content = reader.read(entry)
        println("${PathGuard.sanitize(entry.name)}: ${content.size} bytes")
    }
}
```

## Guides

- [Compression](compression.md): codecs, encoder options, gzip headers and checksums.
- [Reading archives](reading.md): opening, listing, reading, closing and errors.
- [Entry names and Zip-Slip](entry-names.md): the name check, safe output paths and links.
- [Encrypted archives](encryption.md): WinZip AES, ZipCrypto and 7z AES.
- [Writing archives](writing.md): ZIP, TAR, ar and cpio.
- [Files and extraction](files.md): the file source, `extract` and kotlinx-io.
- [Custom codecs and formats](extending.md): registries and your own readers.

From other languages: [Java](java.md), [Swift](swift.md) and
[JavaScript and TypeScript](javascript.md).

## Reference

- [Format support](formats.md): what each codec and reader handles, and the sniffer.
- [Limits](limits.md): the settings that bound memory and output.
- [Benchmarks](benchmarks.md): the benchmark harness and its baseline.

## Targets

The core and `archivekodec-io` build for Android, `jvm`, `js`, `wasmJs`, `wasmWasi`, iOS,
`macosArm64`, tvOS, watchOS, `linuxX64`, `linuxArm64`, `mingwX64` and the four
androidNative targets. `archivekodec-crypto` builds for all of them except `js`, `wasmJs` and
`wasmWasi`.
