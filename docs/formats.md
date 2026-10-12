# Format support

## Codecs

| Codec | Decode | Encode |
| --- | --- | --- |
| STORE | Yes | Yes |
| DEFLATE (RFC 1951) | Yes, with an optional preset dictionary | zlib's levels 0 to 9 and strategies, 9 to 15 window bits, preset dictionaries |
| Deflate64 (ZIP method 9) | Yes (64 KiB window, long matches) | DEFLATE-compatible codes with a 32 KiB window |
| zlib (RFC 1950) | Yes, with FDICT when you pass the dictionary | Yes, with FDICT |
| gzip (RFC 1952) | All members, with header and trailer checks | Yes |
| bzip2 | Any level, concatenated streams, randomized blocks, CRC checks | Level 9 |
| LZMA (`.lzma`) | Known size or end marker | Greedy and lazy parser, no end marker |
| LZMA2 (raw chunks) | Yes | LZMA chunks, stored when incompressible |
| xz | CRC-32, CRC-64 and SHA-256 checks, delta and x86 BCJ filters, the index, concatenated streams | One LZMA2 block with CRC-64 |
| zstd (RFC 8878) | All block, literal and table modes, skippable and concatenated frames, xxHash64 checks | Predefined tables, raw literals |
| brotli (RFC 7932) | Context modeling, block switching, the static dictionary with all 121 transforms | One prefix code for each category, no dictionary references |
| PPMd var.I rev. 1 (ZIP method 98) | Restart and cut-off restore | Order 6, up to 16 MiB of model memory |
| LZ4 (frame and block) | Yes | 64 KiB to 4 MiB blocks, block and content checksums, content size, linked blocks |
| Snappy (raw block) | Yes | Greedy matching |
| Snappy (framed `.sz`) | CRC-32C checks | Yes, with stored chunks when incompressible |

Refused on decode: zstd dictionaries, xz BCJ filters other than x86, PPMd's freeze restore and
supplied LZ4 dictionaries.

## Archives

| Format | Read | Write |
| --- | --- | --- |
| ZIP and ZIP64 | STORE, DEFLATE, Deflate64, bzip2, LZMA, zstd and PPMd | STORE and DEFLATE |
| TAR (v7, ustar, GNU, pax) | Yes | ustar with GNU long names, regular files and directories |
| 7z | Copy, LZMA, LZMA2, PPMd, Deflate, Deflate64, bzip2, delta and BCJ x86, compressed headers, solid folders decoded once | No |
| ar (GNU, BSD, COFF, GNU thin) | Yes, thin members through an `ExternalMemberResolver` | GNU |
| cpio (newc, crc, odc) | Yes | newc and crc |
| ISO 9660 (Joliet, Rock Ridge) | Multi-extent files, Rock Ridge symbolic links read as their target | No |
| CAB | Stored, MSZIP and LZX folders, each decoded once | No |
| WIM | Uncompressed, XPRESS and LZX, deduplicated resources with SHA-1 checks, several images as `1/`, `2/` | No |
| SquashFS 4.0 | gzip, LZMA, LZO, xz with the x86 BCJ filter, LZ4 and zstd, fragments, sparse blocks, symbolic links read as their target, devices and FIFOs listed | No |
| RAR4 and RAR5 | Stored members of both, and RAR5 compressed members (all methods, solid archives, delta, x86 and ARM filters), with CRC checks | No |

Refused on read:

- ZIP: split archives, PKWARE strong encryption, compressed patched data (bit 5) and masked
  local headers (bit 13).
- CAB: Quantum folders and files that span cabinets. They are listed, but a read fails.
- WIM: LZMS and solid `.esd` images.
- RAR: RAR4 compression, encrypted members, multi-volume members and encrypted headers.
- cpio: the old binary dialect is recognized, but a listing fails.

ArchiveKodec reads RAR from RARLAB's published format description and will never create RAR
archives. ACE, StuffIt and ALZ are proprietary with no clean reference, so they are out of
scope.

## Sniffing

```kotlin
ArchiveKodec.sniff(header)     // ArchiveFormat.ZIP, TAR, GZIP and so on
ArchiveKodec.sniff(source)     // reads the first 512 bytes of a RandomAccessSource
```

The sniffer recognizes ZIP, TAR, ar, cpio, gzip, zlib, bzip2, xz, zstd, LZ4, 7z, RAR,
ISO 9660, CAB, WIM, SquashFS and framed Snappy. It never returns brotli or LZMA, because raw
Snappy and brotli have no signature and an LZMA header is not reliable.

- A match is evidence of the format, not a full check. The reader or codec checks the rest.
- ISO 9660 keeps its `CD001` signature at byte 32769. A source sniff reads those five bytes
  only when the first 512 bytes are all zero or end in an MBR boot signature, as ISO images
  do.
- A complete TAR header with a valid checksum wins over short signatures, also when a member
  name looks like another format. A complete zero record is an empty TAR.
- ZIP needs a complete four-byte signature. bzip2 needs a level digit and a complete first
  block or end marker.
- zlib checks the header and rejects a reserved first block type.
- LZ4 shares its skippable-frame magic with zstd, so a skippable prefix alone does not name
  the format.

Sniffing does not close the source. `open` adds the ZIP footer search for an unknown header.

## Format notes

### ZIP

- ZIP and ZIP64 archives on one volume are read, with a launcher or self-extracting prefix
  before them. The end-record comment must reach the end of the file.
- Every listing compares each local header with the central directory: name bytes, method
  and flags. A contradiction throws `ArchiveFormatException`.
- Each member's header, name, extra fields and data must sit in its own range before the
  directory. An overlap throws `ArchiveFormatException`, and a duplicate offset throws
  `DecompressionBombException`. These checks stay on under `ArchiveLimits.UNBOUNDED`.

### TAR

- A pathname prefix is read only from a header with the complete six-byte `ustar\0` marker.
  A GNU long-name record replaces the base name and the prefix.
- Every nonzero header checksum is checked, as an unsigned sum or as the old signed sum. An
  archive can end without its two zero blocks, and an empty source is an empty archive.
- pax `x` records apply to the next member and `g` records to every later member. `path`,
  `size` and `mtime` replace the ustar fields, and names are UTF-8 unless
  `hdrcharset=BINARY`. A pax header above 16 MiB is refused.
- Sparse members (GNU `S`, and pax GNU sparse 0.0, 0.1 and 1.0) list their full `size`, with
  `compressedSize` for the stored bytes, and read with zero-filled holes. Holes count toward
  `maxUncompressedBytes` but not toward the ratio.

### cpio

- Hard links share the last nonempty stored body of their group, and every alias reports the
  full size.
- A read of a `crc` record checks the unsigned byte sum of its body modulo 2^32. That sum is
  not `ArchiveEntry.crc32`.
- Empty input, or an unknown magic before the trailer, throws `ArchiveFormatException`.

### ar

- GNU and COFF long names resolve through the `//` table. A GNU name ends with `/\n`, and a
  COFF name ends with NUL and keeps a stored trailing slash.
- A BSD `#1/n` name takes the first `n` bytes of the member, and the reader removes them from
  the payload. The BSD symbol index (`__.SYMDEF` and its variants) is skipped when it comes
  first.

### LZ4 frames

- The decoder reads every concatenated frame and all 16 skippable-frame types. The legacy
  frame format is refused.
- It checks the descriptor checksum, each block checksum before the block is used and the
  content checksum before it returns.
- The output cap covers all frames together, and the ratio applies to each frame.
- A block that needs an external dictionary throws `ArchiveUnsupportedException` with the
  dictionary id, when the frame has one.

### Snappy

The framed decoder accepts concatenated streams, repeated stream identifiers and skippable
chunks, and rejects reserved chunk types that cannot be skipped. The framed encoder writes
chunks of up to 64 KiB with masked CRC-32C checksums.
