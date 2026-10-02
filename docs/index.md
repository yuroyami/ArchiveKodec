<div class="kite-hero" markdown>

# KiteArchive

KiteArchive implements compression codecs and archive readers in pure Kotlin, for
Kotlin Multiplatform. Gunzip a buffer or read a ZIP on iOS and in the browser, as
well as on the JVM.

<div class="kite-hero-actions" markdown>
[Get started](#a-first-example){ .kite-primary }
[API reference](api/)
[GitHub](https://github.com/yuroyami/KiteArchive)
</div>

</div>

There is no JNI, no cinterop and no native binary. The core artifact depends on
`kotlin-stdlib` and nothing else, so it builds anywhere Kotlin builds.

## A first example

```kotlin
import io.github.yuroyami.kitearchive.KiteArchive
import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.kitearchive.security.PathGuard

val gz = KiteArchive.gzip.compress("hello".encodeToByteArray())
val back = KiteArchive.gzip.decompress(gz)

KiteArchive.sniff(bytes)                            // -> ArchiveFormat.ZIP / TAR / GZIP / ...

val reader = KiteArchive.open(ByteArrayRandomAccessSource(zipBytes))
for (entry in reader.entries()) {
    // The reader returns names unchanged. Sanitize one before you join it to a path.
    println(PathGuard.sanitize(entry.name))
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

## Modules

| Module | What it holds | Targets |
| --- | --- | --- |
| `kitearchive` | Codecs, archive readers, a ZIP writer, checksums, a sniffer and the safety checks. See the table below | Android, iOS, JVM, JS |
| `kitearchive-io` | Four convenience adapters over kotlinx-io `RawSource`. No `RawSink` bridge and no seekable file handle | Android, iOS, JVM |

What `kitearchive` contains:

| Area | Contents |
| --- | --- |
| Codecs | DEFLATE, gzip, zlib, LZ4 and Snappy |
| Archives | ZIP reader and writer (ZIP64, STORE and DEFLATE), plus TAR, ar and cpio readers |
| Checksums | CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32 |
| Safety | A magic sniffer, plus the opt-in `PathGuard` and `ArchiveLimits` checks |

`kitearchive-io` has **no JS target**. It cannot go in the same `commonMain` as
the core in a project that builds for JS.

## Not implemented

`CodecId` names bzip2, LZMA, LZMA2, xz, zstd, brotli and PPMd, and the sniffer
recognizes several of them. None of them has an implementation, and
`KiteArchive.codec(id)` returns `null` for those ids. ZIP is the only container
with a writer.

## Where to go next

<div class="kite-cards" markdown>

<a class="kite-card" href="https://github.com/yuroyami/KiteArchive#readme">
<strong>README</strong>
<span>Install, per-format tables, targets, and the full list of limits.</span>
</a>

<a class="kite-card" href="api/">
<strong>API reference</strong>
<span>Every public type, generated from source.</span>
</a>

<a class="kite-card" href="https://github.com/yuroyami/KiteArchive/issues">
<strong>What is planned</strong>
<span>Every missing codec, container and API, filed as an issue you can follow.</span>
</a>

</div>
