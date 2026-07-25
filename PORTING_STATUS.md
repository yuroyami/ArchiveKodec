# KiteArchive — porting status

Pure-Kotlin compression + archive toolkit for KMP. `kotlin-stdlib`-only core; kotlinx-io
adapter in a separate module. This file tracks what is implemented, what is next, and the
feasibility / legal call per format. The permissively-licensed sources consulted during
porting are listed in [NOTICE](NOTICE); the `reference/` checkout they live in is
git-ignored and not part of a clone.

## Legend

`✅ done` · `🟡 partial` · `⛔ roadmap` · `🚫 out of scope (legal / no clean reference)`

---

## Codecs

| Codec | Decode | Encode | Difficulty | Notes |
|---|---|---|---|---|
| Store | ✅ | ✅ | trivial | identity / ZIP method 0 |
| DEFLATE | ✅ | ✅ | easy | inflate = faithful `puff` port; encoder now does fixed **and dynamic** Huffman (zlib `gen_bitlen` length-limiter), picks the smaller per block; output verified decodable by stock `java.util.zip` / CPython zlib |
| zlib | ✅ | ✅ | trivial | CMF/FLG + Adler-32 |
| gzip | ✅ | ✅ | trivial | header flags + CRC-32 + ISIZE; multi-member decode TODO |
| CRC-32 / CRC-32C / Adler-32 / CRC-64 | ✅ | — | trivial | standard check values verified |
| LZ4 (block+frame) | ✅ | ✅ | easy | greedy hash block compressor + frame; decode verified against a frame from the `lz4` CLI, encode by round-trip; xxHash32 implemented (used for the frame header byte, no check-value test, and the optional block/content checksums are skipped rather than verified) |
| Snappy | ✅ | 🟡 | easy | decoder complete; encoder is valid all-literal (no matching yet) |
| bzip2 | ⛔ | ⛔ | moderate | BWT/MTF/RLE/Huffman; encode leans hard (suffix sort); ref `commons-compress`. ← next (W2) |
| range coder (shared) | ⛔ | ⛔ | moderate | unlocks LZMA + LZMA2 + PPMd |
| LZMA / LZMA2 | ⛔ | ⛔ | hard | port `reference/xz-java` (0BSD); do `.lzma`-alone first to validate the coder |
| FSE / Huff0 (shared) | ⛔ | ⛔ | moderate | the gate to zstd |
| zstd | ⛔ | ⛔ | hard | RFC 8878; ref `zstd/doc` + educational decoder; highest-value modern codec |
| brotli | ⛔ | ⛔ | hard | 120 KB static dict; decode-first; ref `brotli/java/org/brotli/dec` (MIT) |
| PPMd | ⛔ | ⛔ | hard | needs the shared range coder |

## Containers

| Format | Read | Write | Difficulty | Notes |
|---|---|---|---|---|
| TAR (v7/ustar/gnu) | ✅ | ⛔ | easy | long-name (GNU `L`) + base-256 done; pax `x`/`g` + sparse TODO |
| gzip / zlib / lz4 (single-stream) | ✅ | ✅ | — | gzip/zlib/lz4 done; xz/zstd follow their codec |
| ZIP (+ZIP64) | ✅ | ✅ | easy–moderate | central-directory reader + writer; STORE/DEFLATE; overlapping-header bomb check; verified both ways vs `java.util.zip` + CPython |
| ar | ✅ | ⛔ | easy | GNU short + `//` long-name table; BSD `#1/len` TODO |
| cpio | ✅ | ⛔ | easy | newc/crc + odc dialects; old binary TODO |
| 7z | ⛔ | ⛔ | hard | gated on LZMA2 + the solid-folder model |
| ISO9660 / SquashFS / CAB / WIM | ⛔ | ⛔ | moderate–hard | tier-2 |
| RAR4 / RAR5 | 🚫 | 🚫 | brutal | **extract-only, far future, license-encumbered; creation forbidden** |
| ACE / StuffIt / ALZ | 🚫 | 🚫 | — | proprietary; no clean reference |

## Security

| Guard | Status | Notes |
|---|---|---|
| Zip-Slip / path traversal (`PathGuard`) | 🟡 | implemented and tested, but **opt-in**: no reader calls it, so the caller must run `sanitize` on every entry name before joining it to a path |
| Decompression-bomb size cap (`ArchiveLimits.checkOutputSize`) | 🟡 | enforced by every codec and reader, but for DEFLATE/gzip/zlib it runs *after* full inflation — only LZ4 checks per block |
| Compression-ratio cap (`ArchiveLimits.checkRatio`) | 🟡 | `ZipReader.read` only; no other caller |
| Entry-count cap (`ArchiveLimits.maxEntries`) | ⛔ | field and `checkEntryCount` exist; nothing calls them |
| Nesting-depth cap (`ArchiveLimits.maxNestingDepth`) | ⛔ | field exists; there is no auto-extract path to enforce it on |
| Overlapping-local-header detection | ✅ | `ZipReader.parseCentralDirectory` rejects a duplicate local-header offset (Fifield bomb signature) |
| Symlink/hardlink-escape on extract | ⛔ | belongs to the future file-extraction API; `PathGuard` has no symlink logic |
| Encrypt-then-MAC verify-before-decompress | ⛔ | with the crypto layer |

## Crypto (planned — hybrid strategy)

Checksums stay 100% pure-Kotlin (done). AES/HMAC/PBKDF2 will go behind a `CryptoProvider`
interface backed by **whyoleg/cryptography-kotlin** (hardware-accelerated, constant-time
per platform) rather than a hand-rolled table-AES. Scope: ZipCrypto (decrypt-only,
INSECURE-flagged), WinZip-AES (full), 7z AES-256-CBC (confidentiality-only, headers
encrypted by default). RAR crypto = decrypt-only-someday at most.

## IO foundation

`:kitearchive` is `kotlin-stdlib`-only and operates on `ByteArray` + the `RandomAccessSource`
contract. Everything is whole-array: the payload is fully in memory, and the only
`RandomAccessSource` implementation is `ByteArrayRandomAccessSource`.

`:kitearchive-io` is currently four convenience adapters over kotlinx-io `RawSource`
(`readAllBytes`, `toRandomAccessSource`, and `Codec.compress`/`decompress` over a source).
There is no `RawSink` bridge, no `FileHandle` and no seeking — `toRandomAccessSource()`
drains the source into a `ByteArray`. A real seekable file source, the streaming
`Source`→`Sink` codec API, and the suspend, solid-folder-aware `extract(Set<ArchiveEntry>)`
contract (with `ProgressSink` + cancellation + `PasswordProvider`) are the next API milestone.

## Roadmap order

1. ✅ **Dynamic-Huffman DEFLATE encoder** (done — fixed+dynamic, zlib-interop verified).
2. ✅ **ZIP (+ZIP64) reader/writer** (done — STORE/DEFLATE, overlapping-header bomb check, JDK+CPython interop).
3. ✅ **ar · cpio · LZ4 · Snappy** (done — W1 complete).
4. **bzip2** decode-first (W2). ← next
5. **Range coder → `.lzma`-alone → `.xz` → 7z** decode-first (W2; port from 0BSD xz-java).
6. **FSE/Huff0 → zstd** decode-first (W3), then **brotli** decode.
7. Streaming + suspend extraction API; crypto layer (ZipCrypto/WinZip-AES/7z-AES).
8. Tier-2 containers (ISO9660, SquashFS, CAB, WIM); RAR5 extract-only (marquee, late).

## Tests

Golden-vector harness in `commonTest` (runs on every target via `kotlin.test`):
DEFLATE round-trips, dynamic-Huffman on skewed alphabets, gzip/zlib decode-conformance
against CPython output, checksum check values (incl. a large-input Adler-32 overflow
regression), TAR parse, magic sniffing, path-traversal rejection. A JVM-only
`ZlibInteropTest` round-trips our raw-deflate / gzip / zlib output **through the JDK's
native `java.util.zip`**, which validates the Adler-32 and CRC-32/ISIZE trailers our own
lenient inflate skips. ZIP is verified both directions against `java.util.zip` + CPython
zipfile (incl. a 70,000-entry ZIP64 round-trip); LZ4 decode against an `lz4` CLI frame;
ar/cpio/Snappy against CLI/hand vectors. **58 tests pass.** Not covered: xxHash32 has no
check-value test. Planned: Canterbury + Silesia corpora.
