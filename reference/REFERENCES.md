# ArchiveKodec — porting references

These trees are **for study and porting only**. They are **git-ignored and
never distributed** (the source trees under `reference/` are ignored; this notes file is tracked, and nothing there ships in the
published artifact). Every reference here is under a **permissive license** (zlib / 0BSD /
BSD / MIT / Apache-2.0) so reading and re-implementing from it is legally clean for an
Apache-2.0 library. Where a per-file copyright applies to a near-1:1 port, retain the
original notice in the ported `.kt` header (the Kite-lineage convention).

> **Deliberately NOT fetched: RAR / UnRAR.** The UnRAR license forbids using its source to
> build a RAR-compatible archiver, and the RAR compression algorithm is documented *only*
> in that restricted source. RAR creation is permanently out of scope; a RAR5 *extractor*
> would be a far-future, clean-room, license-paragraph-encumbered effort and is not seeded
> here on purpose. Same for ACE / StuffIt / ALZ (proprietary, no clean reference).

| ArchiveKodec target | Reference tree | Key files | License |
|---|---|---|---|
| **DEFLATE / inflate** (RFC 1951) | `zlib/contrib/puff` | `puff.c`, `puff.h` — canonical, deliberately-simple inflate reference (already the basis of the ported `Inflate.kt`) | zlib |
| DEFLATE encoder, gzip, zlib | `zlib` | `deflate.c`, `trees.c`, `gzlib.c`, `adler32.c`, `crc32.c` | zlib |
| DEFLATE length-limited coding | [Zopfli](https://github.com/google/zopfli/blob/df1517dd8518113a245e4d203a04b8a0e02ab493/src/zopfli/katajainen.c) (remote reference) | `katajainen.c` — bounded package-merge and independent weighted-cost answers; the Kotlin list-based builder is an independent implementation | Apache-2.0 |
| **LZMA / LZMA2 / .xz** | `xz-java/src/org/tukaani/xz` | `lzma/LZMADecoder.java`, `lzma/LZMAEncoder*.java`, `rangecoder/*`, `lzma/Optimum.java`, `BlockInputStream.java`, `XZInputStream.java`, `DeltaCoder.java`, BCJ `*Options.java` | **0BSD / public domain** (frictionless) |
| **bzip2** (BWT) | `commons-compress/.../compressors/bzip2` | `BZip2CompressorInputStream.java` (decode), `BZip2CompressorOutputStream.java` + `BlockSort.java` (encode) | Apache-2.0 |
| **zstd** (FSE/Huff0) | `zstd/doc` + `zstd/lib` | `doc/zstd_compression_format.md` (RFC 8878 text), `doc/educational_decoder/zstd_decompress.c` (smallest correct decoder) | BSD-3 |
| **LZ4** (block + frame) | `lz4/lib` | `lz4.c`, `lz4frame.c`, `lz4.h` + `doc/*_format.md` | BSD-2 |
| **brotli** | `brotli/java/org/brotli/dec` | pure-Java decoder (`Decoder.java`, `BitReader.java`, `Dictionary.java`) — clean MIT reference | MIT |
| **Snappy** | `snappy` | `snappy.cc`, `snappy-internal.h` + `format_description.txt` | BSD-3 |
| **ZIP / TAR / ar / cpio / 7z framework** | `commons-compress/.../archivers` | `zip/*`, `tar/*`, `ar/*`, `cpio/*`, `sevenz/*` (note: 7z LZMA itself comes from xz-java) | Apache-2.0 |
| CRC32 / CRC32C / Adler32 / CRC64 | `zlib` + `xz-java` | `crc32.c`, `adler32.c`, `xz-java/.../check/CRC64.java` | zlib / 0BSD |

## Refresh

```bash
cd reference
for d in zlib xz-java commons-compress lz4 zstd brotli snappy; do
    git -C "$d" pull --ff-only --depth 1
done
```

## Porting discipline

1. **Read the reference, write Kotlin from understanding** — don't transliterate line-for-line
   from Apache/BSD sources into Apache-2.0 unless you also carry the notice; for every altered source port, retain its original notice. The puff
   translation uses the zlib license, not a public-domain dedication.
2. **Golden-test against the reference's own output** — decode bytes produced by the real CLI
   (`gzip`/`xz`/`zstd`/`lz4`/`bzip2`) and assert exact round-trips. See `PORTING_STATUS.md`.
3. **Never touch RAR/ACE/StuffIt sources.** Not seeded here; keep it that way.
