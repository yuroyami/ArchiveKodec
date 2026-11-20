# Compression

Every codec takes a whole `ByteArray` and returns one, with the same calls on every target.
The codecs hang off the `ArchiveKodec` object, and the same instances are fields of `Codecs`.

```kotlin
val gz = ArchiveKodec.gzip.compress(bytes)       // a gzip member
val z = ArchiveKodec.zlib.compress(bytes)        // a zlib stream, as in PNG IDAT and PDF FlateDecode
val raw = ArchiveKodec.deflate.compress(bytes)   // bare RFC 1951
val back = ArchiveKodec.gzip.decompress(gz)
```

All 18 accessors are non-null: `store`, `deflate`, `deflate64`, `bzip2`, `lzma`, `lzma2`,
`xz`, `zstd`, `brotli`, `ppmd`, `gzip`, `zlib`, `lz4`, `snappy`, `snappyFramed`, `lzw`,
`packBits` and `palmDoc`.
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

## Stream both sides

A `ByteSource` is the input side of a `ByteSink`: decoders pull compressed bytes from it as
they need them. With a source and a sink, DEFLATE, zlib, gzip, bzip2, xz and zstd hold neither the input nor
the output whole, so both can be larger than memory. The ratio limit counts the compressed
bytes read so far.

```kotlin
val input = FileInputStream(path)
ArchiveKodec.gzip.decompress(ByteSource { b, offset, length -> input.read(b, offset, length) },
    ByteSink { b, offset, length -> out.write(b, offset, length) }, limits)
```

To compress a stream, open an `EncoderSession` with `codec.encoder(sink)`, `write` the input
in pieces of any size and `finish` it. DEFLATE, zlib and gzip encode 1 MiB at a time, each
piece primed with the 32 KiB before it, so matches still reach back across pieces and the
output is a little larger than a whole-array encode. `flush` makes everything written so far
decodable from what the sink has, as zlib's sync flush does. bzip2 writes each full block as
its own stream, which every bzip2 decoder joins, and xz writes blocks of 2 MiB, as `xz -T`
does. zstd, LZ4 and lzip write each 1 to 4 MiB
of input as a complete frame or member, which their decoders join. The xz, zstd, lzip and `.lzma`
decoders keep a window of the dictionary or the frame's window, which `maxDecoderMemory` bounds, and
the LZ4 decoder keeps one block, at most 4 MiB in a standard frame and 8 MiB in a legacy
frame, and 64 KiB of history; Unix compress needs only
its code table and lzop one block; an xz
block with a branch filter is held whole until its end. The other codecs keep the input and compress
it at `finish`, until they stream too.

```kotlin
val gzip = ArchiveKodec.gzip.encoder(ByteSink { b, offset, length -> out.write(b, offset, length) })
for (chunk in chunks) gzip.write(chunk)
gzip.finish()
```

`TarStreamWriter` writes a TAR entry by entry from sources, `TarStreamReader` reads one
forward from a source, and `TarStreamSink` reads one from bytes pushed into it, such as a
decoder's output, so a `.tar.gz` of any size goes through a fixed amount of memory in either
direction. Tests write and read a `.tar.gz`, a `.tar.bz2` and a `.tar.xz` larger than a 32 MiB JVM heap
through that heap.

```kotlin
ArchiveKodec.gzip.decompress(fileSource, TarStreamSink(object : TarStreamVisitor {
    override fun onEntry(entry: ArchiveEntry) { /* open the output for entry */ }
    override fun onData(entry: ArchiveEntry, bytes: ByteArray, offset: Int, length: Int) { /* write */ }
}), limits)
```

`ArStreamWriter` and `ArStreamReader` do the same for Unix `ar`. The writer stores names longer
than 16 bytes inline, as BSD and macOS `ar` do and GNU `ar` reads, since a GNU name table would
have to come before every member. There is no push-side `ar` reader yet, so a compressed `ar`
is decoded to a file before `ArStreamReader` reads it.

`CpioStreamWriter` and `CpioStreamReader` do the same for cpio. The writer writes newc or odc;
the crc dialect puts each body's sum in the header before the body, which a stream cannot know
yet, so `CpioWriter` writes it. The reader reports each record with the body it stores, so the
earlier links of a newc hard-link group, whose data comes with the last link, read as empty.

`ZipStreamWriter` writes a ZIP the same way, entry by entry from sources, with a data descriptor
after each file and ZIP64 where sizes or offsets need it; [Writing](writing.md#large-zip-archives)
shows it. A ZIP is read with `ZipReader`, which needs a seekable source.

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

## Lenient and partial decodes

Some writers do not follow the format to the letter: a zlib stream with a wrong Adler-32,
padding after the stream, or a file that was cut short. `InflateOptions` relaxes the checks
of raw DEFLATE and zlib one at a time. gzip takes limits only.

| Setting | Default | Effect |
| --- | --- | --- |
| `limits` | `ArchiveLimits.DEFAULT` | The output and ratio ceilings. They always throw. |
| `dictionary` | `null` | The preset dictionary of the stream. |
| `sizeHint` | 0 | The output size that you expect. An output of exactly this size needs no final copy. It is not a limit. |
| `allowTrailingData` | `false` | Ignore the bytes after the stream. |
| `verifyChecksum` | `true` | zlib only. `false` leaves the Adler-32 unread, so it can be wrong, cut short or missing. |
| `keepPartialOutput` | `false` | A damaged or cut-short stream gives the output decoded before the damage. |
| `onBlock` | `null` | Runs before each DEFLATE block with the input and output counts. Throw from it to stop the decode. |
| `stopAfterBytes` | -1 | Stop once the output has this many bytes, and return them. `isStopped` is then true, and the checksum and trailing data are not checked. |
| `usePlatformInflater` | `false` | On the JVM and Android, decode with `java.util.zip.Inflater` first. See below. |

A call with options returns an `InflateResult`. It holds the `output`, the `inputConsumed`
count of bytes that the stream took, and the `error` that `keepPartialOutput` kept.

```kotlin
// A stream inside a larger array: read it in place, then find what follows it.
val found = Inflate.inflate(file, start, file.size - start, InflateOptions { allowTrailingData = true })
val next = start + found.inputConsumed

// A zlib stream from a careless writer: take what can be decoded.
val lenient = InflateOptions {
    verifyChecksum = false
    allowTrailingData = true
    keepPartialOutput = true
}
val result = Zlib.decompress(stream, lenient)
if (!result.isComplete) println("damaged stream: ${result.error}")
```

`keepPartialOutput` covers damage only: `InflateException` and `ArchiveFormatException`. A
limit, a missing dictionary and an output too large for one array still throw. The ratio
limit compares the output with the length of the range, so keep the range close to the
stream.

## Platform zlib on the JVM and Android

`java.util.zip` runs the platform's native zlib, which inflates several times faster than the
pure decoder. Ask for it per call:

```kotlin
val fast = Zlib.decompress(stream, InflateOptions { usePlatformInflater = true })
val packed = Zlib.compress(data, DeflateOptions { usePlatformDeflater = true })
```

The inflate result is the same as without the setting. zlib and the pure decoder give the
same bytes for every valid stream. Damage, a limit, trailing data, a dictionary or `onBlock`
send the call to the pure decoder, which then gives its usual result or error.

The deflate output differs from the pure encoder's, and any DEFLATE decoder reads both. A
dictionary, a window below 15 bits and the RLE and FIXED strategies use the pure encoder.
Other platforms ignore both settings.

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

## Image and e-book codecs

These codecs serve image and document formats. Each takes an expected size, can keep the bytes
before damage, and returns a `DecodeResult` with the `output`, the `inputConsumed` count and
the kept `error`.

```kotlin
val pdf = Lzw.decode(stream)                                         // PDF LZWDecode, TIFF LZW
val gif = Lzw.decode(data, LzwOptions.gif(minimumCodeSize).copy { expectedSize = pixels }).output
val tiff = PackBits.decode(strip, PackBitsOptions { expectedSize = rowBytes * rows }).output
val runLength = PackBits.decode(stream, PackBitsOptions.PDF_RUN_LENGTH).output
val text = PalmDoc.decode(record)                                    // a PalmDOC or MOBI text record
val mobi = HuffCdic(huffRecord, cdicRecords).decode(record, 0, record.size - trailingEntries)
```

- `LzwOptions` picks the bit order, the literal size and early change. The decoder accepts data
  with or without a leading clear code and without the end code, as many PDF writers leave it
  out. `LzwOptions.TIFF_OLD_STYLE` reads the old-style TIFF LZW that libtiff still reads.
- `PackBitsOptions.variant` decides what the length byte 128 means: nothing in TIFF, the end
  of the data in PDF.
- A `HuffCdic` decoder keeps the phrases it has decoded, so use one from one thread at a time.
- `HuffmanCode.lengths(frequencies, maxLength)` and `HuffmanCode.canonicalCodes(lengths)` build
  the length-limited canonical Huffman codes of DEFLATE and lossless WebP.

## Compression ratios

The encoders are correct but not tuned to match the reference tools; matching them is a goal
for 1.0. The table gives the total compressed size of the 12 files of the
[Silesia Corpus](https://sun.aei.polsl.pl//~sdeor/index.php?page=silesia), 211,938,580 bytes,
each file compressed on its own. Ratio is input bytes over output bytes. The last column is
ArchiveKodec's size over the reference's, so 1.100 means 10% larger.

Measured on 2026-11-20 at commit `e0e70ab` (0.2.0-SNAPSHOT) on OpenJDK 21.0.13. The encoders
are common Kotlin, so the sizes do not depend on the platform. The references are gzip 1.12,
zlib 1.3 (through Python's `zlib` module), bzip2 1.0.8, XZ Utils 5.4.5, lzip 1.24.1, zstd
1.5.5, lz4 1.9.4, lzop 1.04 with LZO 2.10, ncompress 5.0 and 7-Zip 23.01. `xz` ran with `-T1`,
so it wrote one block. `encoder(sink)` is the streaming `EncoderSession`, written in 64 KiB
pieces. The ZIP rows count the member data only.

| Codec | ArchiveKodec | Bytes | Ratio | Reference | Bytes | ArchiveKodec / reference |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| gzip | default (level 6) | 68,190,490 | 3.108 | `gzip -6` (default) | 68,227,893 | 0.999 |
| gzip | level 1 | 77,233,473 | 2.744 | `gzip -1` | 77,366,636 | 0.998 |
| gzip | level 2 | 74,979,144 | 2.827 | `gzip -2` | 75,111,953 | 0.998 |
| gzip | level 3 | 72,939,378 | 2.906 | `gzip -3` | 73,039,598 | 0.999 |
| gzip | level 4 | 70,974,705 | 2.986 | `gzip -4` | 71,045,043 | 0.999 |
| gzip | level 5 | 69,125,619 | 3.066 | `gzip -5` | 69,143,908 | 1.000 |
| gzip | level 7 | 67,904,536 | 3.121 | `gzip -7` | 67,930,298 | 1.000 |
| gzip | level 8 | 67,653,179 | 3.133 | `gzip -8` | 67,678,859 | 1.000 |
| gzip | level 9 | 67,605,824 | 3.135 | `gzip -9` | 67,631,918 | 1.000 |
| gzip | `encoder(sink)` | 68,192,557 | 3.108 | `gzip -6` (default) | 68,227,893 | 0.999 |
| zlib | default (level 6) | 68,190,346 | 3.108 | zlib, level 6 | 68,220,487 | 1.000 |
| deflate | default (level 6) | 68,190,274 | 3.108 | zlib, raw, level 6 | 68,220,415 | 1.000 |
| deflate64 | default | 66,531,775 | 3.186 | `7z a -tzip -mm=Deflate64` (level 5) | 63,809,169 | 1.043 |
| bzip2 | default (level 9) | 54,512,292 | 3.888 | `bzip2 -9` (default) | 54,506,769 | 1.000 |
| bzip2 | level 1 | 60,503,814 | 3.503 | `bzip2 -1` | 60,509,041 | 1.000 |
| bzip2 | `encoder(sink)` | 54,570,380 | 3.884 | `bzip2 -9` (default) | 54,506,769 | 1.001 |
| lzma | default | 59,282,137 | 3.575 | `xz --format=lzma -6` (default) | 49,221,276 | 1.204 |
| lzma | default | 59,282,137 | 3.575 | `xz --format=lzma -9` | 48,780,457 | 1.215 |
| lzma2 | default | 59,573,375 | 3.558 | `xz --format=raw --lzma2=preset=6` | 49,232,609 | 1.210 |
| lzma2 | default | 59,573,375 | 3.558 | `xz --format=raw --lzma2=preset=9` | 48,794,742 | 1.221 |
| xz | default | 59,574,112 | 3.558 | `xz -6` (default) | 49,233,340 | 1.210 |
| xz | default | 59,574,112 | 3.558 | `xz -9` | 48,795,480 | 1.221 |
| xz | `encoder(sink)` | 59,767,652 | 3.546 | `xz -6` (default) | 49,233,340 | 1.214 |
| lzip | default | 59,282,366 | 3.575 | `lzip -6` (default) | 49,787,570 | 1.191 |
| lzip | default | 59,282,366 | 3.575 | `lzip -9` | 48,335,267 | 1.226 |
| lzip | `encoder(sink)` | 59,494,583 | 3.562 | `lzip -6` (default) | 49,787,570 | 1.195 |
| zstd | default | 73,809,642 | 2.871 | `zstd -3` (default) | 66,547,664 | 1.109 |
| zstd | default | 73,809,642 | 2.871 | `zstd -1` | 73,448,492 | 1.005 |
| zstd | default | 73,809,642 | 2.871 | `zstd -19` | 53,024,573 | 1.392 |
| zstd | `encoder(sink)` | 76,821,374 | 2.759 | `zstd -3` (default) | 66,547,664 | 1.154 |
| lz4 | default | 98,396,721 | 2.154 | `lz4 -1` (default) | 100,934,427 | 0.975 |
| lz4 | default | 98,396,721 | 2.154 | `lz4 -9` | 77,992,601 | 1.262 |
| lz4 | `linkedBlocks = true` | 98,349,451 | 2.155 | `lz4 -1 -BD` | 100,883,728 | 0.975 |
| lz4 | `encoder(sink)` | 98,397,216 | 2.154 | `lz4 -1` (default) | 100,934,427 | 0.975 |
| lzop | default | 101,158,272 | 2.095 | `lzop -3` (default) | 101,036,657 | 1.001 |
| lzop | default | 101,158,272 | 2.095 | `lzop -1` | 101,914,918 | 0.993 |
| lzop | default | 101,158,272 | 2.095 | `lzop -9` | 76,317,298 | 1.325 |
| compress | default | 88,446,916 | 2.396 | `compress -b16` (default) | 93,573,664 | 0.945 |
| ppmd | default | 50,159,523 | 4.225 | `7z a -tzip -mm=PPMd:o=6:mem=16m` | 50,159,523 | 1.000 |
| ppmd | default | 50,159,523 | 4.225 | `7z a -tzip -mm=PPMd` (level 5) | 50,286,473 | 0.997 |
| brotli | default | 63,476,553 | 3.339 | none |  |  |
| snappy | default | 97,121,259 | 2.182 | none |  |  |
| snappy-framed | default | 100,156,136 | 2.116 | none |  |  |
| lzw | default | 107,337,405 | 1.975 | none |  |  |
| packbits | default | 201,554,993 | 1.052 | none |  |  |
| palmdoc | 4,096-byte records | 117,958,837 | 1.797 | none |  |  |

- **Within 0.5% of the reference:** DEFLATE, zlib and gzip at every level are 0.0% to 0.2%
  smaller than GNU gzip and zlib at the same level. bzip2 at levels 1 and 9 is within 0.01%
  of `bzip2`. PPMd writes the same number of bytes as 7-Zip with the same order and memory,
  50,159,523. lzop is 0.1% larger than `lzop -3`, and zstd 0.5% larger than `zstd -1`.
- **Smaller than the reference in total:** LZ4 is 2.5% smaller than `lz4 -1` (per file, 0.868 to
  1.098 of its size) and 26.2% larger than `lz4 -9`. Unix compress is 5.5% smaller than
  ncompress in total, but per file it is 0.812 to 1.242 of ncompress's size: mozilla is
  6,389,388 bytes smaller, and the other 11 files together are 1,262,640 bytes larger.
- **Clearly weaker:** the LZMA encoder, which `.lzma`, LZMA2, xz and lzip share, is 19.1% to
  21.0% larger than `xz -6` and `lzip -6`, and 21.5% to 22.6% larger than `-9`. Per file, xz is
  1.143 to 1.390 of `xz -6`. zstd is 10.9% larger than `zstd -3`, its default, and 39.2% larger than
  `zstd -19`. Deflate64 is 4.3% larger than 7-Zip's. The zstd session writes a frame per 1 MiB,
  which costs 4.1% against one frame; the bzip2, xz and lzip sessions cost 0.1% to 0.4%, and the
  gzip and LZ4 sessions less than 0.01%.
- **No reference tool here:** brotli, Snappy, LZW, PackBits and PalmDOC. There is no brotli
  command-line tool on the machine, so brotli's 63,476,553 bytes have no comparison; its encoder
  writes no context modeling and no dictionary references.
- **No encoder at all (decode only):** HUFF/CDIC, and the methods that only appear inside
  archives the library reads but does not write: LZX (CAB, WIM), XPRESS (WIM), RAR 2.9 and RAR5
  compression, PPMd var.H (7z) and the branch filters. [Format support](formats.md) lists them.

Each tool decoded ArchiveKodec's output of every row of its codec, and ArchiveKodec decoded each
tool's output, for every file: 768 decodes, all byte-exact. For Deflate64 and PPMd the
streams went through a ZIP member, which 7-Zip extracted and `ArchiveKodec.open` read. zlib and
raw DEFLATE were checked against Python's `zlib`.

To reproduce the table, with the reference tools on `PATH`:

```sh
python3 scripts/compression-ratios.py --work /tmp/ratios
```

The script downloads the corpus with `scripts/fetch-silesia.sh` and checks each file's SHA-256.
It writes `ratios.md` with this table and `ratios.json` with the size of every file, and exits
with an error if any decode differs. It measures no time.

## Memory

A whole-array call, `compress(bytes)` or `decompress(bytes)`, holds the input and the output.
The output grows by doubling and is copied to its exact size at the end, so for a moment it is
held about twice. The encoders add the working arrays below. The calls that stream, described in
[Stream both sides](#stream-both-sides), keep the windows in the last column instead.

From the code:

| Codec | Encoder working memory | `decompress(source, sink)` keeps |
| --- | --- | --- |
| DEFLATE, zlib, gzip | 128 KiB hash heads, up to 128 KiB of chains, two 16,384-entry token buffers; a session adds a 1 MiB piece and 32 KiB of history | The 32 KiB window and about 1 MiB of output |
| Deflate64 | As DEFLATE, with a 64 KiB window | (whole input) |
| bzip2 | One block of level × 100,000 bytes and about 26 bytes per block byte to sort and code it, 23 MB at level 9 | One block and its table, which `maxDecoderMemory` caps |
| LZMA, LZMA2, xz, lzip | 4 bytes per input byte of match chains and 512 KiB of heads; the dictionary is the input size rounded up, at most 64 MiB; a session encodes 2 MiB blocks (xz) or members (lzip) | The dictionary, or the declared size of a `.lzma` stream if smaller |
| zstd | 4 bytes per input byte of match chains; `compress` writes one single-segment frame, whose window is the whole input | The frame's window: the whole content of a single-segment frame |
| brotli | 4 bytes per input byte of match chains; a 4 MiB window | (whole input) |
| PPMd | A model of 1 MiB per 64 KiB of input, from 1 to 16 MiB | (whole input) |
| LZ4 | 256 KiB of hash table; blocks up to 4 MiB; a session encodes 4 MiB frames | One block, at most 4 MiB (8 MiB in a legacy frame), and 64 KiB of history |
| lzop | 64 KiB of hash heads; 256 KiB blocks | One block, at most 64 MiB |
| Unix compress | A hash map of up to 65,536 codes | The 65,536-entry code table and 64 KiB of output |
| Snappy, LZW, PackBits | A hash table of at most 64 KiB (Snappy, LZW), or none (PackBits) | (whole input) |
| PalmDOC | 4 bytes per input byte of match chains, 16 KiB of heads | (whole input) |

`(whole input)` means the codec has no streaming decoder, so `decompress(source, sink)` reads
the whole input and decodes it in memory. `ArchiveLimits.maxDecoderMemory`, 256 MiB by default,
caps the PPMd model, the bzip2 block table and the streaming windows before they are allocated;
[Limits](limits.md#decoder-memory) lists what it covers. A whole-array decode checks no window,
because it keeps the whole output anyway.

Measured: the smallest JVM heap (`-Xmx`, in MiB) at which one call on the largest Silesia file,
mozilla (51,220,480 bytes), succeeded in a child JVM. Each figure is a step of 16, 24, 32, 48,
64, 96, 128, 192, 256, 384, 512, 768, 1,024 or 1,536 MiB; the step below it failed. The
decodes read ArchiveKodec's own default output, and for `decompress(source, sink)` also the
reference tool's default output. Same commit, OpenJDK 21.0.13, default garbage collector.

| Codec | `compress` | `decompress` | `encoder(sink)` | `decompress(source, sink)`, tool's stream | `decompress(source, sink)`, own stream |
| --- | ---: | ---: | ---: | ---: | ---: |
| gzip | 128 | 128 | 16 | 16 | 16 |
| zlib | 128 | 192 | 16 | 16 | 16 |
| deflate | 128 | 192 | 16 | 16 | 16 |
| deflate64 | 128 | 192 |  |  |  |
| bzip2 | 128 | 192 | 32 | 16 | 16 |
| lzma | 384 | 192 |  | 16 | 64 |
| lzma2 | 384 | 192 |  |  |  |
| xz | 384 | 192 | 24 | 16 | 96 |
| lzip | 384 | 192 | 24 | 16 | 96 |
| zstd | 384 | 192 | 16 | 16 | 64 |
| lz4 | 128 | 192 | 24 | 16 | 16 |
| lzop | 128 | 192 |  | 16 | 16 |
| compress | 128 | 192 |  | 16 | 16 |
| ppmd | 128 | 192 |  |  |  |
| brotli | 384 | 192 |  |  |  |
| snappy | 128 | 96 |  |  |  |
| snappy-framed | 128 | 192 |  |  |  |
| lzw | 128 | 192 |  |  |  |
| packbits | 192 | 192 |  |  |  |

- The encoders with 4 bytes of chains per input byte, LZMA, zstd and brotli, need 384 MiB for
  a 49 MiB input; the others need 128 to 192 MiB, two to four times the input.
- The streaming decoders read the tools' streams of mozilla in 16 MiB. Their own streams need
  more because `compress` sizes the window to the input: 49 MiB for `.lzma` and zstd, and a
  64 MiB dictionary for xz and lzip. An `encoder(sink)` stream has small frames or blocks.
- zstd's `compress` writes one frame whose window is the whole input. The `zstd` command-line
  decoder refuses a window above 128 MiB unless given `--memory` or `--long`, and the streaming
  decoder here refuses one above `maxDecoderMemory`. Measured: `zstd -d` refused `compress`'s
  frame of the 211,938,580-byte concatenated corpus ("Window size larger than maximum") and
  decoded it with `--memory=256MB`. Use `encoder(sink)`, which writes 1 MiB frames, for large
  zstd output.

To reproduce the measured table:

```sh
python3 scripts/compression-ratios.py --work /tmp/ratios --memory
```

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
