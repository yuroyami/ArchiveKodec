# Limits

An archive or a compressed stream can ask for far more memory than its own size. A few
hundred bytes of bzip2 can expand to gigabytes, and a ZIP central directory can declare
millions of entries. `ArchiveLimits` caps what one call may produce, and every codec and
reader takes one.

## The settings

| Setting | Default | What it caps |
| --- | --- | --- |
| `maxUncompressedBytes` | 2 GiB | The output of one decode or one entry read |
| `maxCompressionRatio` | 10,000 | Output bytes for each input byte |
| `maxEntries` | 100,000 | The entries of one listing |
| `maxSegments` | 100,000 | The segments of one initramfs listing |
| `maxDecoderMemory` | 256 MiB | Memory that a header asks a decoder to allocate, and the phrases a HuffCdic decoder keeps |
| `rejectUnsafeNames` | `true` | Whether a listing refuses unsafe entry names |

A violation throws `DecompressionBombException`. `rejectUnsafeNames` is described in
[Entry names and Zip-Slip](entry-names.md).

## Build a policy

```kotlin
val limits = ArchiveLimits {
    maxUncompressedBytes = 64L * 1024 * 1024
    maxEntries = 10_000
}
val looser = limits.copy { maxEntries = 200_000 }
```

`ArchiveLimits.DEFAULT` holds the defaults, and `ArchiveLimits.UNBOUNDED` raises every
ceiling for trusted input and turns the name check off. Java and Swift use
`ArchiveLimits.Builder`, set its properties and call `build()`.

A policy is an immutable snapshot, so a builder that changes later cannot change it. Every
number must be zero or more, and a negative one throws `IllegalArgumentException` when the
policy is built. Zero means zero: `maxEntries = 0` allows no entries.

## One call at a time

Limits apply to each call, and separate calls do not add up. A reader uses its policy for
the listing and as the default for each read. To cap the total output of several reads, share
an `ExtractionBudget`:

```kotlin
val budget = ExtractionBudget(64L * 1024 * 1024)
for (entry in reader.entries()) {
    val bytes = reader.read(entry, budget = budget) // throws once the total passes 64 MiB
}
```

Each read under a budget lowers its output cap to what the budget has left, so it stops as
soon as the total would pass. A failed read charges nothing. A budget is mutable and is meant
for one extraction on one thread.

A policy passed to `read(entry, limits)` replaces the reader's policy for that read only. It
does not change the ceiling that the listing used.

## The compression ratio

Every codec checks the ratio before its output grows. ZIP checks the ratio of each entry, and
TAR, ar and cpio check their 1:1 ratio.

Holes in sparse TAR members and SquashFS files cost no input, so such a member's full size is
measured against the larger of its stored bytes and the size of the whole archive (the image's
used bytes for SquashFS). A 10 KB archive cannot expand to gigabytes of zeros under the default
ratio, while sparse files in ordinary archives still read.

The default ratio of 10,000:1 cannot reject a valid stream from DEFLATE (below 1,032:1), LZ4
(below 255:1), Snappy (below 22:1) or STORE (1:1). It still rejects a declared size that no
valid stream could reach. Deflate64, bzip2, zstd and brotli can pass it on input with long
runs of one byte. A 6-byte Deflate64 stream can hold 65,539 bytes, which is about 10,900:1.
Larger examples:

| 100 MiB of zeros | Compressed size | Ratio |
| --- | --- | --- |
| bzip2 `-9` | 113 bytes | about 928,000:1 |
| zstd `-19` | 3,243 bytes | about 32,000:1 |
| brotli | 93 bytes | about 1,128,000:1 |

Raise `maxCompressionRatio` to read such a file.

## Decoder memory

`maxDecoderMemory` caps the memory that a stream's header asks a decoder to allocate, and is
checked before that memory is allocated:

- the model of a PPMd stream (ZIP, 7z and RAR 2.9 PPMd blocks);
- the 4 MiB RAR 2.9 window and the RAR5 window;
- the bzip2 block table, 4 bytes for each byte of block size (3.6 MB at level 9);
- an LZMA literal table whose lc + lp is above 4 (up to 6 MiB);
- the dictionary window of a streamed xz, lzip or `.lzma` decode, and the window of a streamed
  zstd frame. A whole-array decode needs no window beyond its output, so it does not check them.

HuffCdic is the exception to "up front": it counts the phrases it decodes and keeps, across
all of a decoder's calls, and throws once they pass the limit.

A violation throws `DecompressionBombException`. [Memory](compression.md#memory) gives each
codec's figures.

## Entry counts

A listing counts entries as it parses and throws before it keeps one entry too many.
Directories and cpio hard-link records count. The ar symbol and name tables, TAR metadata
records and cpio trailers do not count. ZIP also checks the count that its directory declares
before loading it, and counts the actual records, so a wrong count cannot get past the
ceiling. A failed listing is not cached.

```kotlin
val limits = ArchiveLimits { maxEntries = 200_000 }
ArchiveKodec.open(source, limits).use { reader ->
    for (entry in reader.entries()) {
        val data = reader.read(entry)
    }
}
```

`maxSegments` applies only to `InitramfsReader` and counts empty segments too.

## What the limits do not cover

- Structural checks are always on. A ZIP member that overlaps the next one or the directory
  fails, even under `ArchiveLimits.UNBOUNDED`.
- No API opens an archive inside another archive, so there is no nesting limit. The one
  exception is the one compression layer that `ArchiveKodec.open` removes, and the same
  policy applies to it.
- A result must fit one `ByteArray`. An entry larger than that can be listed, but a read into
  a `ByteArray` fails with an `ArchiveException`. Unencrypted ZIP members that are stored,
  DEFLATE, Deflate64, bzip2, zstd or xz can be read into a sink instead, as [Reading archives](reading.md#read-into-a-sink)
  shows.
