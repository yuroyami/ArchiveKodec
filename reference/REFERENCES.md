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
| AES for WinZip AES and 7z AES | [BearSSL](https://bearssl.org/gitweb/?p=BearSSL;a=tree;f=src/symcipher), remote | `aes_ct.c`, `aes_ct_enc.c`, `aes_ct_dec.c`, `aes_ct_cbcdec.c`: the bitsliced AES that `ConstantTimeAes.kt` is ported from | MIT |

LZW, PackBits, PalmDOC and HUFF/CDIC are written from their format descriptions, with no
reference tree: LZW from ISO 32000-1 (7.4.4), TIFF 6.0 (section 13) and GIF89a (appendix F),
PackBits from TIFF 6.0 (section 9) and ISO 32000-1 (7.4.5), PalmDOC and HUFF/CDIC from the
MOBI format description on the MobileRead wiki. HUFF/CDIC moved here from KitePDF's MOBI
reader, by the same author. The JDK's ImageIO GIF and TIFF plugins check LZW and PackBits in
both directions (`ImageIoOracleTest`).

PPMd is ported from `Ppmd7.c` and `Ppmd8.c` of Igor Pavlov's LZMA SDK, which are in the public
domain. That tree is not kept under `reference/`.

## RAR, ACE, StuffIt and ALZ

The UnRAR license forbids using its source to build a RAR-compatible archiver, so no RAR or
UnRAR source is fetched here. ArchiveKodec reads the RAR container from RARLAB's published
format description, and it will never create RAR archives. Never add RAR, ACE, StuffIt or ALZ
source to this folder. ACE, StuffIt and ALZ are proprietary with no clean reference, so they
are out of scope.

Never read UnRAR source, or code that carries the unRAR restriction, such as the RAR decoders
in 7-Zip and p7zip.

### The one exception: libarchive's RAR4 reader, at v3.5.2

The owner approved one RAR4 decompression reference in
[#154](https://github.com/yuroyami/ArchiveKodec/issues/154):
`libarchive/archive_read_support_format_rar.c` (BSD-2, Copyright 2011 Andres Mejia), **at
libarchive v3.5.2**, the last release before commit `d7fec62` (January 2022). Read that
revision only.

Why it qualifies, from libarchive's
[issue 151](https://github.com/libarchive/libarchive/issues/151), where the project discussed
RAR support:
- **No UnRAR readers.** In 2009 its maintainer barred anyone who had read UnRAR source from
  working on its RAR support, and kept the code BSD-licensed.
- **Its source was The Unarchiver.** The 2011 patch drew on The Unarchiver, which, the patch's
  author wrote, its author implemented from scratch and gave permission to relicense as BSD.
- **What it covers:** LZSS and PPMd (variant H, through libarchive's copy of the public-domain
  LZMA SDK).

Why later revisions are excluded:
- **Unclear provenance.** `d7fec62` ([pull request
  1503](https://github.com/libarchive/libarchive/pull/1503)) added RAR filter support. Its
  author described the code as moved from unarr, which is LGPL and derived from The
  Unarchiver. The VM part was removed before the merge, but nothing records where the
  remaining filter code came from.
- **What that leaves out:** RAR4 filters (x86, delta, audio, RGB and Itanium) have no approved
  reference yet. Until they do, RAR4 members that use a filter are refused.

### RAR5 decompression

`codec/rar/Rar5Unpacker.kt` (commit `23a034d`) was written in an earlier AI-assisted session,
and no reference was recorded for it. RARLAB's description covers the container, not the
compression algorithm. libarchive's RAR5 reader,
`archive_read_support_format_rar5.c` (BSD-2, Copyright 2018 Grzegorz Antoniak), does **not**
qualify as a reference. In its [pull request 1061](https://github.com/libarchive/libarchive/pull/1061)
(September 2018) the author wrote that its block decompression "was written according to
`libunrar`'s structures", and do not read it.

The decoder's provenance therefore stays undocumented. It is kept, disclosed in `NOTICE`, for
three reasons:
- **It never writes RAR.** The UnRAR license forbids building a RAR-compatible archiver, and
  ArchiveKodec only reads.
- **It is checked against `unrar`.** It decodes byte for byte the same as `unrar x`.
- **It can be left out.** In the approved module layout, RAR is its own artifact. A consumer
  that needs a strict provenance policy leaves that artifact out.

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
