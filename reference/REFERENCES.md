# ArchiveKodec porting references

The source trees under `reference/` are for study and porting only. Git ignores them, so they
are never distributed and nothing in them ships in a published artifact. Only this file is
tracked.

Every tree is under a permissive license (zlib, 0BSD, BSD, MIT or Apache-2.0), so reading it
and writing Kotlin from it is clean for an Apache-2.0 library. When a Kotlin file is a close
port of a file with its own copyright, keep the original notice in the header of the Kotlin
file.

| ArchiveKodec part | Reference tree | Key files | License |
| --- | --- | --- | --- |
| DEFLATE inflate (RFC 1951) | `zlib/contrib/puff` | `puff.c`, `puff.h`: the small reference inflater that `Inflate.kt` is ported from | zlib |
| DEFLATE encoder, gzip, zlib | `zlib` | `deflate.c`, `trees.c`, `gzlib.c`, `adler32.c`, `crc32.c` | zlib |
| DEFLATE length-limited codes | [Zopfli](https://github.com/google/zopfli/blob/df1517dd8518113a245e4d203a04b8a0e02ab493/src/zopfli/katajainen.c), remote | `katajainen.c`: package-merge and weighted-cost answers. The Kotlin builder is an independent implementation | Apache-2.0 |
| LZMA, LZMA2, xz | `xz-java/src/org/tukaani/xz` | `lzma/LZMADecoder.java`, `lzma/LZMAEncoder*.java`, `rangecoder/*`, `lzma/Optimum.java`, `BlockInputStream.java`, `XZInputStream.java`, `DeltaCoder.java`, the BCJ `*Options.java` | 0BSD |
| bzip2 | `commons-compress/.../compressors/bzip2` | `BZip2CompressorInputStream.java` (decode), `BZip2CompressorOutputStream.java` and `BlockSort.java` (encode) | Apache-2.0 |
| zstd | `zstd/doc` and `zstd/lib` | `doc/zstd_compression_format.md` (the RFC 8878 text), `doc/educational_decoder/zstd_decompress.c` | BSD-3 |
| LZ4 (block and frame) | `lz4/lib` | `lz4.c`, `lz4frame.c`, `lz4.h` and `doc/*_format.md` | BSD-2 |
| brotli | `brotli/java/org/brotli/dec` | The Java decoder: `Decoder.java`, `BitReader.java`, `Dictionary.java` | MIT |
| Snappy | `snappy` | `snappy.cc`, `snappy-internal.h` and `format_description.txt` | BSD-3 |
| ZIP, TAR, ar, cpio, 7z | `commons-compress/.../archivers` | `zip/*`, `tar/*`, `ar/*`, `cpio/*`, `sevenz/*`. The LZMA of 7z comes from xz-java | Apache-2.0 |
| CRC-32, CRC-32C, Adler-32, CRC-64 | `zlib` and `xz-java` | `crc32.c`, `adler32.c`, `xz-java/.../check/CRC64.java` | zlib, 0BSD |

PPMd is ported from `Ppmd7.c` and `Ppmd8.c` of Igor Pavlov's LZMA SDK, which are in the public
domain. That tree is not kept under `reference/`.

## RAR, ACE, StuffIt and ALZ

The UnRAR license forbids using its source to build a RAR-compatible archiver, so no RAR or
UnRAR source is fetched here. ArchiveKodec reads RAR from RARLAB's published format
description, and it will never create RAR archives. Never add RAR, ACE, StuffIt or ALZ source
to this folder. ACE, StuffIt and ALZ are proprietary with no clean reference, so they are out
of scope.

## Refresh

```bash
cd reference
for d in zlib xz-java commons-compress lz4 zstd brotli snappy; do
    git -C "$d" pull --ff-only --depth 1
done
```

## Porting rules

1. Read the reference and write Kotlin from understanding. Do not copy Apache or BSD source
   line by line into an Apache-2.0 file unless you also carry its notice. Every altered port
   keeps its original notice. The `puff` translation uses the zlib license, not a
   public-domain dedication.
2. Test against the output of the real tools. Decode bytes that `gzip`, `xz`, `zstd`, `lz4`
   and `bzip2` produced, and assert exact round trips.
3. Never read RAR, ACE or StuffIt sources.
