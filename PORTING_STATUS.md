# KiteArchive format status

This file lists the support level for every codec and container in KiteArchive.
It also records what comes next, and why some formats are absent. For install
instructions, targets and limits, read the [README](README.md).

## Legend

| Mark | Meaning |
|---|---|
| ✅ | Implemented and tested |
| 🟡 | Partly implemented: read the note |
| ⛔ | Planned, no implementation yet |
| 🚫 | Out of scope: licensing, or no clean reference to work from |

## Codecs

| Codec | Decode | Encode | Difficulty | Notes |
|---|---|---|---|---|
| Store | ✅ | ✅ | trivial | Identity. ZIP method 0 |
| DEFLATE | ✅ | ✅ | easy | The encoder does fixed **and dynamic** Huffman and picks the smaller of the two per block. The JDK's `java.util.zip` and CPython zlib both decode our output |
| zlib | ✅ | ✅ | trivial | CMF and FLG header, Adler-32 trailer |
| gzip | ✅ | ✅ | trivial | Header flags, CRC-32 and ISIZE. Decode reads the first member only |
| CRC-32, CRC-32C, Adler-32, CRC-64 | ✅ | not applicable | trivial | Standard check values verified |
| LZ4 (block and frame) | ✅ | ✅ | easy | Greedy hash block compressor plus frame support. The suite decodes a frame from the `lz4` CLI, and checks the encoder by round trip. xxHash32 exists and supplies the frame header byte. It has no check-value test, and the frame decoder skips the optional block and content checksums instead of verifying them |
| Snappy | ✅ | 🟡 | easy | Decoder complete. The encoder emits literals only, with no matching yet |
| bzip2 | ⛔ | ⛔ | moderate | Needs the Burrows-Wheeler transform, move-to-front coding, run-length encoding and Huffman coding. The encoder also needs a suffix sort. This is the next piece of work |
| Range coder (shared) | ⛔ | ⛔ | moderate | Needed by LZMA, LZMA2 and PPMd |
| LZMA and LZMA2 | ⛔ | ⛔ | hard | Plan: do `.lzma` alone first, to validate the coder |
| FSE and Huff0 (shared) | ⛔ | ⛔ | moderate | Needed by zstd |
| zstd | ⛔ | ⛔ | hard | RFC 8878 |
| brotli | ⛔ | ⛔ | hard | Decode first. Needs the 120 KB static dictionary |
| PPMd | ⛔ | ⛔ | hard | Needs the shared range coder |

## Containers

| Format | Read | Write | Difficulty | Notes |
|---|---|---|---|---|
| TAR (v7, ustar, GNU) | ✅ | ⛔ | easy | GNU `L` long names and base-256 sizes are done. pax `x` and `g` headers and sparse files are not |
| gzip, zlib, lz4 (single stream) | ✅ | ✅ | easy | Done. xz and zstd follow their codec |
| ZIP (and ZIP64) | ✅ | ✅ | easy to moderate | Central-directory reader and writer, STORE and DEFLATE. Rejects an overlapping local header. Verified both ways against `java.util.zip` and CPython |
| ar | ✅ | ⛔ | easy | GNU short names and the `//` long-name table. BSD `#1/len` is not handled |
| cpio | ✅ | ⛔ | easy | newc, crc and odc dialects. The old binary dialect is not handled |
| 7z | ⛔ | ⛔ | hard | Blocked on LZMA2 and on solid folders, where several files share one compressed block |
| ISO9660, SquashFS, CAB, WIM | ⛔ | ⛔ | moderate to hard | Planned, with no date |
| RAR4 and RAR5 | 🚫 | 🚫 | very hard | **Extract only, far future, license-encumbered. Creating RAR archives is forbidden** |
| ACE, StuffIt, ALZ | 🚫 | 🚫 | not applicable | Proprietary, with no clean reference |

## Security

Two terms used in this table. A decompression bomb is a small file that expands
into a very large one. In a Zip-Slip attack, an archive entry name escapes the
folder you extract into. The entry then overwrites a file somewhere else on disk.

| Guard | Status | Notes |
|---|---|---|
| Zip-Slip and path traversal (`PathGuard`) | 🟡 | Implemented and tested, but **opt-in**. No reader calls it, so you must run `sanitize` on every entry name before you join it to a path |
| Decompression-bomb size cap (`ArchiveLimits.checkOutputSize`) | 🟡 | Every codec and reader enforces it. For DEFLATE, gzip and zlib it runs *after* full inflation, so the data is already in memory. LZ4 is the only codec that checks per block |
| Compression-ratio cap (`ArchiveLimits.checkRatio`) | 🟡 | Called by `ZipReader.read` only |
| Entry-count cap (`ArchiveLimits.maxEntries`) | ⛔ | The field and `checkEntryCount` exist. Nothing calls them |
| Nesting-depth cap (`ArchiveLimits.maxNestingDepth`) | ⛔ | The field exists. There is no auto-extract path to enforce it on |
| Overlapping local-header detection | ✅ | `ZipReader.parseCentralDirectory` rejects a duplicate local-header offset. That is how one well-known ZIP bomb works: many entries point at the same compressed data |
| Symlink and hardlink escape on extract | ⛔ | Belongs to the future file-extraction API. `PathGuard` has no symlink logic |
| Verify the authentication tag before decompressing | ⛔ | Arrives with the crypto layer |

## Crypto (planned)

Checksums stay pure Kotlin, and they are done. AES, HMAC and PBKDF2 will sit
behind a `CryptoProvider` interface. The plan is to back that interface with
**whyoleg/cryptography-kotlin** rather than a hand-written table-based AES. That
library uses each platform's hardware acceleration, and runs in constant time, so
its timing does not leak key material.

Planned scope: ZipCrypto (decrypt only, flagged INSECURE), WinZip-AES (full), and
7z AES-256-CBC (confidentiality only, with headers encrypted by default). RAR
crypto would be decrypt-only at most, and is not scheduled.

## IO foundation

`:kitearchive` depends on `kotlin-stdlib` only. It operates on `ByteArray` and on
the `RandomAccessSource` contract. Everything is whole-array: the payload sits
fully in memory, and `ByteArrayRandomAccessSource` is the only implementation of
`RandomAccessSource`.

`:kitearchive-io` is four convenience adapters over kotlinx-io `RawSource`:
`readAllBytes`, `toRandomAccessSource`, and `Codec.compress` and
`Codec.decompress` over a source. There is no `RawSink` bridge, no `FileHandle`
and no seeking, because `toRandomAccessSource()` drains the source into a
`ByteArray`.

Three things make up the next API milestone. The first is a real seekable file
source. The second is a streaming `Source` to `Sink` codec API. The third is a
suspend `extract(Set<ArchiveEntry>)` contract with `ProgressSink`, cancellation
and `PasswordProvider`, which understands solid folders.

## What comes next

1. bzip2, decode first. This is the next piece of work.
2. The shared range coder, then `.lzma` alone, then `.xz`, then 7z. Decode first.
3. FSE and Huff0, then zstd decode, then brotli decode.
4. The streaming and suspend extraction API, then the crypto layer (ZipCrypto,
   WinZip-AES and 7z-AES).
5. Further containers: ISO9660, SquashFS, CAB and WIM. RAR5 extract-only last.

## Tests

**58 tests pass.**

`commonTest` holds a golden-vector harness that runs on every target through
`kotlin.test`. It covers:

- DEFLATE round trips, and dynamic Huffman on skewed alphabets.
- gzip and zlib decode conformance against CPython output.
- A ZIP produced by CPython `zipfile`.
- An LZ4 frame produced by the `lz4` CLI.
- Snappy, ar and cpio hand-written test vectors.
- Checksum check values, including a large-input Adler-32 overflow regression.
- TAR parsing, magic sniffing and path-traversal rejection.

Two JVM-only suites cover interoperability. `ZlibInteropTest` feeds our raw
DEFLATE, gzip and zlib output to **the JDK's `java.util.zip`**. That check
validates the Adler-32 and CRC-32/ISIZE trailers, which our own lenient inflate
skips. `ZipInteropTest` round-trips ZIPs in both directions against
`java.util.zip`, including a 70,000-entry ZIP64 archive.

Not covered: xxHash32 has no check-value test. Planned: the Canterbury and
Silesia corpora.
