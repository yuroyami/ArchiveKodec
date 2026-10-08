<div class="kite-hero" markdown>

# ArchiveKodec

ArchiveKodec implements compression codecs and archive readers in pure Kotlin, for
Kotlin Multiplatform. Gunzip a buffer or read a ZIP on iOS and in the browser, as
well as on the JVM.

<div class="kite-hero-actions" markdown>
[Get started](#install){ .kite-primary }
[API reference](api/)
[GitHub](https://github.com/yuroyami/ArchiveKodec)
</div>

</div>

There are no JNI or cinterop dependencies. The core artifact depends on
`kotlin-stdlib` and nothing else. Both modules build for the targets listed below.

## Install

| Artifact | Coordinates |
| --- | --- |
| Core | `io.github.yuroyami:archivekodec:0.2.0` |
| kotlinx-io adapters | `io.github.yuroyami:archivekodec-io:0.2.0` |

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:archivekodec:0.2.0")
            // Only if you already use kotlinx-io:
            implementation("io.github.yuroyami:archivekodec-io:0.2.0")
        }
    }
}
```

A JVM or Android project that is not multiplatform uses the same line in its
`dependencies` block; Gradle picks the platform variant. Maven does not read
Gradle module metadata, so a Maven project depends on `archivekodec-jvm` (and
`archivekodec-io-jvm`) instead:

```xml
<dependency>
  <groupId>io.github.yuroyami</groupId>
  <artifactId>archivekodec-jvm</artifactId>
  <version>0.2.0</version>
</dependency>
```

Requirements: Kotlin 2.1 or newer, Java 8 or newer on the JVM, and Android
minSdk 21. Java calls the JVM artifact directly. Swift has no published framework
yet, and JavaScript cannot call the library because nothing is exported with
`@JsExport`. The [README's Install section](https://github.com/yuroyami/ArchiveKodec#install)
covers these callers and the reasons behind each requirement.

## A first example

```kotlin
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.security.PathGuard

val gz = ArchiveKodec.gzip.compress("hello".encodeToByteArray())
val back = ArchiveKodec.gzip.decompress(gz)

ArchiveKodec.sniff(gz) // -> ArchiveFormat.GZIP

val zipBytes = ZipWriter.write(
    listOf(ZipWriter.FileSpec("hello.txt", back)),
)
ArchiveKodec.open(ByteArrayRandomAccessSource(zipBytes)).use { reader ->
    for (entry in reader.entries()) {
        // Archives of the current directory may contain a root directory entry.
        if (PathGuard.isRootDirectory(entry)) continue
        // The reader returns names unchanged. Sanitize one before you join it to a path.
        println(PathGuard.sanitize(entry.name))
    }
}
```

## Two things to know first

**Everything is whole-array.** You pass a `ByteArray` and get a `ByteArray` back,
so the payload sits fully in memory. There is no streaming API.

**`PathGuard` is a function you call, not a guard the readers apply.** Every
reader returns the entry name exactly as the archive stored it, including a name
such as `../../etc/passwd`. Joining that to an output directory is the Zip-Slip
vulnerability, where an archive entry escapes the folder you meant to extract
into. Call `PathGuard.sanitize` on every name before you use it as a path.

For archives of the current directory, `PathGuard.isRootDirectory(entry)` lets
the loop skip directory entries such as `.` or `./`. It returns false for files,
empty names and unsafe names. Sanitize every remaining name: a false result is
not a safety check. The strict sanitizer still rejects names that resolve to the
root, and skipping a root directory entry does not change the output directory
or apply archive metadata to it.

## Modules

| Module | What it holds | Targets |
| --- | --- | --- |
| `archivekodec` | Codecs, archive readers, ZIP, TAR, ar and cpio writers, checksums, a sniffer and the safety checks. See the table below | All, see below |
| `archivekodec-io` | Four convenience adapters over kotlinx-io `RawSource`. No `RawSink` bridge and no seekable file handle | All, see below |

What `archivekodec` contains:

| Area | Contents |
| --- | --- |
| Codecs | DEFLATE, gzip, zlib, LZ4 and Snappy |
| Archives | ZIP reader and writer (ZIP64, STORE and DEFLATE), TAR reader and regular-file/directory writer, GNU ar reader and writer, and cpio reader and newc/crc writer |
| Checksums | CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32 |
| Safety | A magic sniffer, default entry-count limits on listings, output-size/ratio limits on decoding and entry reads, and an opt-in `PathGuard` for paths |

The default 10,000:1 compression-ratio ceiling is above the ratios reachable by
the current codecs, so it cannot reject their valid streams. Set a lower ratio
when that restriction is needed. The default 2 GiB output cap applies separately
to each decode/read; callers must enforce a cumulative budget across reads.

Both modules build for the same targets: Android, `jvm`, `js`, `wasmJs`,
`wasmWasi`, iOS, `macosArm64`, tvOS, watchOS, `linuxX64`, `linuxArm64`,
`mingwX64` and the four androidNative targets. The README lists them in full.

## Not implemented

`CodecId` names bzip2, LZMA, LZMA2, xz, zstd, brotli and PPMd, and the sniffer
recognizes several of them. None of them has an implementation, and
`ArchiveKodec.codec(id)` returns `null` for those ids. ZIP, TAR, GNU ar and cpio have writers. TAR writing currently covers regular files and directories.

## Where to go next

<div class="kite-cards" markdown>

<a class="kite-card" href="https://github.com/yuroyami/ArchiveKodec#readme">
<strong>README</strong>
<span>Install, per-format tables, targets, and the full list of limits.</span>
</a>

<a class="kite-card" href="api/">
<strong>API reference</strong>
<span>Every public type, generated from source.</span>
</a>

<a class="kite-card" href="https://github.com/yuroyami/ArchiveKodec/issues">
<strong>What is planned</strong>
<span>Every missing codec, container and API, filed as an issue you can follow.</span>
</a>

</div>
