# KiteArchive — porting status

Pure-Kotlin compression + archive toolkit for KMP. `kotlin-stdlib`-only core; kotlinx-io
adapter in a separate module. This file tracks what is implemented, what is next, and the
feasibility / legal call per format. See `reference/REFERENCES.md` for the clean-room
porting sources.

## Legend

`✅ done` · `🟡 partial` · `⛔ roadmap` · `🚫 out of scope (legal / no clean reference)`

---

## Codecs

| Codec | Decode | Encode | Difficulty | Notes |
|---|---|---|---|---|
| Store | ✅ | ✅ | trivial | identity / ZIP method 0 |
| DEFLATE | ✅ | 🟡 | easy | inflate = faithful `puff` port; encoder is fixed-Huffman only — **dynamic-Huffman encoder is the #1 next task** |
| zlib | ✅ | ✅ | trivial | CMF/FLG + Adler-32 |
| gzip | ✅ | ✅ | trivial | header flags + CRC-32 + ISIZE; multi-member decode TODO |
| CRC-32 / CRC-32C / Adler-32 / CRC-64 | ✅ | — | trivial | standard check values verified |
| LZ4 (block+frame) | ⛔ | ⛔ | easy | no entropy stage — cheapest modern win; ref `reference/lz4` |
| bzip2 | ⛔ | ⛔ | moderate | BWT/MTF/RLE/Huffman; encode leans hard (suffix sort); ref `commons-compress` |
| range coder (shared) | ⛔ | ⛔ | moderate | unlocks LZMA + LZMA2 + PPMd |
| LZMA / LZMA2 | ⛔ | ⛔ | hard | port `reference/xz-java` (0BSD); do `.lzma`-alone first to validate the coder |
| FSE / Huff0 (shared) | ⛔ | ⛔ | moderate | the gate to zstd |
| zstd | ⛔ | ⛔ | hard | RFC 8878; ref `zstd/doc` + educational decoder; highest-value modern codec |
| brotli | ⛔ | ⛔ | hard | 120 KB static dict; decode-first; ref `brotli/java/org/brotli/dec` (MIT) |
| Snappy | ⛔ | ⛔ | easy | ref `reference/snappy` |
| PPMd | ⛔ | ⛔ | hard | needs the shared range coder |

## Containers

| Format | Read | Write | Difficulty | Notes |
|---|---|---|---|---|
| TAR (v7/ustar/gnu) | ✅ | ⛔ | easy | long-name (GNU `L`) + base-256 done; pax `x`/`g` + sparse TODO |
| gzip / zlib / xz / lzma / zstd / lz4 (single-stream) | 🟡 | 🟡 | — | gzip/zlib done; others follow their codec |
| ZIP (+ZIP64) | ⛔ | ⛔ | easy–moderate | central-directory reader stub in place; the seekable-source contract is built for it |
| ar / cpio | ⛔ | ⛔ | easy | ref `commons-compress` |
| 7z | ⛔ | ⛔ | hard | gated on LZMA2 + the solid-folder model |
| ISO9660 / SquashFS / CAB / WIM | ⛔ | ⛔ | moderate–hard | tier-2 |
| RAR4 / RAR5 | 🚫 | 🚫 | brutal | **extract-only, far future, license-encumbered; creation forbidden** |
| ACE / StuffIt / ALZ | 🚫 | 🚫 | — | proprietary; no clean reference |

## Security (default-on)

| Guard | Status | Notes |
|---|---|---|
| Zip-Slip / path traversal (`PathGuard`) | ✅ | rejects `..`, absolute, drive-letter, backslash, control/NUL |
| Decompression-bomb limits (`ArchiveLimits`) | ✅ | uncompressed-size + ratio + entry-count + nesting caps; enforced during decode |
| Overlapping-local-header detection | ⛔ | for the ZIP reader (Fifield bomb signature) |
| Symlink/hardlink-escape on extract | ⛔ | belongs to the future file-extraction API |
| Encrypt-then-MAC verify-before-decompress | ⛔ | with the crypto layer |

## Crypto (planned — hybrid strategy)

Checksums stay 100% pure-Kotlin (done). AES/HMAC/PBKDF2 will go behind a `CryptoProvider`
interface backed by **whyoleg/cryptography-kotlin** (hardware-accelerated, constant-time
per platform) rather than a hand-rolled table-AES. Scope: ZipCrypto (decrypt-only,
INSECURE-flagged), WinZip-AES (full), 7z AES-256-CBC (confidentiality-only, headers
encrypted by default). RAR crypto = decrypt-only-someday at most.

## IO foundation

`:kitearchive` is `kotlin-stdlib`-only and operates on `ByteArray` + the `RandomAccessSource`
contract. `:kitearchive-io` adds kotlinx-io `RawSource`/`RawSink` bridges. The streaming
`Source`→`Sink` codec API and the suspend, solid-folder-aware `extract(Set<ArchiveEntry>)`
contract (with `ProgressSink` + cancellation + `PasswordProvider`) are the next API milestone.

## Roadmap order

1. **Dynamic-Huffman DEFLATE encoder** (closes the one gap in the shipped codec).
2. **ZIP (+ZIP64) reader/writer** over the seekable source — STORE/DEFLATE; overlapping-header bomb check.
3. **LZ4** (frame+block), **bzip2** (decode-first).
4. **Range coder → `.lzma`-alone → `.xz` → 7z**.
5. **FSE/Huff0 → zstd** (decode-first), then **brotli** decode.
6. Streaming + suspend extraction API; crypto layer (ZipCrypto/WinZip-AES/7z-AES).
7. Tier-2 containers (ISO9660, SquashFS, CAB, WIM); RAR5 extract-only (marquee, late).

## Tests

Golden-vector harness in `commonTest` (runs on every target via `kotlin.test`):
DEFLATE round-trips, gzip/zlib decode-conformance against CPython output, checksum check
values, TAR parse, magic sniffing, path-traversal rejection. Planned: Canterbury + Silesia
round-trips and real-CLI decode-conformance (downloaded by a Gradle task, not committed).
