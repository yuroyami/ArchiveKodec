# Changelog

## Unreleased

- DEFLATE, gzip and zlib enforce output and compression-ratio budgets before
  growing or copying output. Concatenated gzip members share one total budget
  while keeping separate DEFLATE history.
- gzip checks optional header CRC-16 and every member’s CRC-32/ISIZE. It decodes
  all members, accepts zero padding between/after members, and rejects other
  trailing data or damaged later members. The zlib fallback validates the same
  zlib integrity rules as direct decoding.
- zlib checks Adler-32 at the final DEFLATE block’s boundary and rejects bytes
  after its trailer. Raw whole-array DEFLATE also rejects trailing bytes.
- Direct `Inflate.inflate`, `Gzip.decompress` and `Zlib.decompress` accept
  `ArchiveLimits`; omitted policies use `ArchiveLimits.DEFAULT` (2 GiB output,
  10000:1 ratio). This raises the direct gzip/zlib default from 4 MiB to the shared
  default. Explicit byte budgets remain available through the policy.
- Existing gzip/zlib `maximumSize: Long` calls and JVM default-call bridges remain
  available as deprecated delegates. `Gzip.DEFAULT_MAXIMUM_SIZE` remains the old
  4 MiB value for explicit compatibility use, but is deprecated and no longer
  controls defaults. Zero-byte budgets accept empty output. Budget violations
  now consistently throw `DecompressionBombException`; malformed wrapper headers,
  trailers and checksum mismatches throw `ArchiveFormatException`.
- The JDK interop helper requires a completed stream and full input consumption,
  including the zlib trailer, and releases native inflater resources on failures.
