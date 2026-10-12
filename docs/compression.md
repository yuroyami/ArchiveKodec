# Compression

Every codec takes a whole `ByteArray` and returns one, with the same calls on every target.
The codecs hang off the `ArchiveKodec` object, and the same instances are fields of `Codecs`.

```kotlin
val gz = ArchiveKodec.gzip.compress(bytes)       // a gzip member
val z = ArchiveKodec.zlib.compress(bytes)        // a zlib stream, as in PNG IDAT and PDF FlateDecode
val raw = ArchiveKodec.deflate.compress(bytes)   // bare RFC 1951
val back = ArchiveKodec.gzip.decompress(gz)
```

All 15 accessors are non-null: `store`, `deflate`, `deflate64`, `bzip2`, `lzma`, `lzma2`,
`xz`, `zstd`, `brotli`, `ppmd`, `gzip`, `zlib`, `lz4`, `snappy` and `snappyFramed`.
[Format support](formats.md#codecs) lists what each one decodes and encodes.

## Find a codec by name

```kotlin
ArchiveKodec.codec("lz4")                  // the same instance as ArchiveKodec.lz4
ArchiveKodec.codec(ArchiveFormat.GZIP)     // the same instance as ArchiveKodec.gzip
```

A lookup by id, format or name ignores case and returns `null` when no codec matches.
Names are codec ids and not HTTP aliases, so `deflate` means raw DEFLATE and `zlib` means
the RFC 1950 wrapper.

`CodecId` is an open value: `CodecId("zstd") == CodecId.ZSTD`. Its name is lowercase ASCII.
To ask whether a codec exists, compare `CodecRegistry.DEFAULT.byId(id)` with `null`, or read
the registry's `implemented` list.

## Detect the format

`ArchiveKodec.decompress(bytes)` sniffs the first bytes and decodes gzip, zlib, bzip2, xz,
zstd, LZ4 or framed Snappy:

```kotlin
val detected = ArchiveKodec.decompress(compressedBytes)
```

It decodes one layer and keeps each codec's rules for concatenated members and checksums. An
archive, an unknown stream or a stream with no decoder throws `ArchiveUnsupportedException`
that names the detected format. Raw DEFLATE, raw Snappy, LZMA and brotli have no reliable
signature, so they need an explicit codec.

## Encoder options

`DeflateOptions` sets the level (0 to 9, default 6), the strategy, the window bits and a
preset dictionary. `Deflate.encode`, `Zlib.compress`, `Gzip.compress` and the
`deflateOptions` of `ZipWriter.FileSpec` take it. `Lz4Options` sets the block size, the
checksums, the content size and linked blocks for `Lz4.frameCompress`.

```kotlin
val fast = Deflate.encode(bytes, DeflateOptions { level = 1 })
val primed = Zlib.compress(bytes, DeflateOptions { dictionary = sharedDictionary })
val unprimed = Zlib.decompress(primed, ArchiveLimits.DEFAULT, sharedDictionary)
val frame = Lz4.frameCompress(bytes, Lz4Options { blockSize = Lz4BlockSize.KB_64; contentChecksum = true })
```

`Codec.compress(data, options)` takes the options class of that codec and throws
`IllegalArgumentException` for any other. The DEFLATE levels follow zlib's table, and the
output is within a few bytes of zlib's at the same level. The output is the same for the same
input, but a later fix to the encoder can change it.

## Stream the output

A decode into a `ByteSink` writes the output in chunks instead of returning one array.
DEFLATE, Deflate64, zlib and gzip hold about 1 MiB of output at a time, so their output can
be larger than memory. The other codecs decode in memory and then write.

```kotlin
val written: Long = ArchiveKodec.gzip.decompress(gzBytes, ByteSink { chunk, offset, length ->
    out.write(chunk, offset, length)
}, ArchiveLimits { maxUncompressedBytes = 16L shl 30 })
```

The limits apply before every write, so raise `maxUncompressedBytes` for an output above its
2 GiB default. `archivekodec-io` adds the same call for kotlinx-io sources and sinks, as
[Files and extraction](files.md#kotlinx-io-adapters) shows.

## Limits on a decode

Every `decompress` takes an `ArchiveLimits`, which defaults to `ArchiveLimits.DEFAULT`. The
direct calls such as `Inflate.inflate`, `Gzip.decompress` and `Zlib.decompress` take one too.

```kotlin
val limits = ArchiveLimits {
    maxUncompressedBytes = 8L * 1024 * 1024
    maxCompressionRatio = 100
}
val decoded = Gzip.decompress(bytes, limits)
val tighter = limits.copy { maxUncompressedBytes = 1024 }
```

A violation throws `DecompressionBombException`. [Limits](limits.md) describes each setting
and what it does not cover.

## gzip and zlib checks

gzip checks the optional header CRC-16, the CRC-32 and ISIZE of each member, then joins all
members under one output budget. It accepts zero padding between and after members and
rejects any other trailing bytes. zlib checks Adler-32 and needs exactly one complete stream.
Raw DEFLATE also rejects bytes after its final block. A failed check throws
`ArchiveFormatException`.

## gzip header metadata

`Gzip` reads and writes the optional fields of a gzip member header:

```kotlin
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

`Gzip.compress(bytes)` without a header writes reproducible output: MTIME 0, OS 255
(unknown), XFL 0 and no optional fields. The rules for the fields:

- `name` and `comment` are ISO 8859-1 text without NUL. `null` leaves the field out, and an
  empty string writes an empty field. The name is stored as given and is not made safe for
  a file system.
- `mtimeEpochSeconds` must fit an unsigned 32-bit value. `os` and `extraFlags` must fit one
  unsigned byte. `extraFlags` is informational and does not select a level.
- `extra` holds up to 65,535 raw bytes, without XLEN.
- `isText` sets FTEXT and does not convert the payload.
- `hasHeaderCrc` writes FHCRC as the low 16 bits of the header's CRC-32.

`readHeader` reads only the header of the first member. It returns `null` when the first two
bytes are not the gzip magic, and throws `ArchiveFormatException` for a damaged header or a
bad header CRC. It does not decode or check the payload, so use `Gzip.decompress` for that.

## Other codec notes

- `Codecs.SNAPPY` is raw Snappy blocks and `Codecs.SNAPPY_FRAMED` is the standard `.sz`
  framing. The `Snappy` object also has `frameCompress` and `frameDecompress`.
- Brotli's 120 KB static dictionary ships in the core as 58 KB of raw DEFLATE. It is inflated
  the first time a stream uses it, so a process that never decodes brotli never pays for it.
- `Codecs.PPMD` is PPMd var.I revision 1 in the framing of ZIP method 98. The 7z reader
  decodes 7z's var.H itself. Both allocate the model memory that the header names, and
  `ArchiveLimits.maxDecoderMemory` (256 MiB by default) caps it before the allocation.

## Checksums

```kotlin
Crc32.of(bytes)        // 0xCBF43926 for "123456789"
Crc32c.of(bytes)
Adler32.of(bytes)
Crc64.of(bytes)        // CRC-64/XZ
XxHash32.hash(bytes)
```

`Crc32`, `Crc32c`, `Adler32` and `Crc64` implement `Checksum`, so they also work in steps
with `update`, `value` and `reset`. `XxHash32` is one call only. Both `update` and `hash`
take an `offset` and a `length`, and a missing `length` means the rest of the array:

```kotlin
val checksum = Crc32()
checksum.update(bytes, offset = 3)                       // bytes[3] to the end
checksum.update(bytes, offset = bytes.size, length = 0)  // a valid empty slice
val suffixHash = XxHash32.hash(bytes, offset = 3)
```

A negative or out-of-bounds slice throws `IllegalArgumentException` before the checksum
changes. A checksum instance holds mutable state, so use one per thread.
