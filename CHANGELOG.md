# Changelog

## Unreleased

- LZ4 frames verify the full descriptor checksum and every advertised block and
  content checksum. Block integrity is checked before decoding or copying;
  checksum mismatches and truncated fields throw `ArchiveFormatException` at the
  checksum field. Content integrity is checked before allocating the final output
  copy, reducing allocation when corrupted content is rejected.

- Expected core and adapter failures export through NSError/Swift throws with
  `ArchiveException` as the shared contract. Reader/codec interfaces, direct
  engines, wrappers, guards, writers, and source helpers declare their throwing
  behavior. This changes the Objective-C selectors and Swift signatures of
  formerly non-throwing operations; Apple consumers must rebuild and use `try`.
  JVM method descriptors remain compatible, while Java source callers must handle
  the now-declared checked archive exceptions.
- Unsupported readers/writer codecs use `ArchiveUnsupportedException`. Remaining
  generic ZIP/ar parser failures use typed format errors. Entry reads retain their
  original cause and attach source offset/name context, including rebasing codec
  offsets into the archive. IO adapters wrap source IO failures in
  `ArchiveIOException` while preserving the original cause.
- The IO framework explicitly exports its core and kotlinx-io API dependencies,
  giving standalone Swift consumers usable public types. `swiftConsumerTest`
  compiles and runs independent core/IO consumers and verifies NSError signatures.

- ZIP validates every non-null CRC-32, including zero. Mismatches report the
  entry name and payload offset through `ArchiveFormatException`.

- All implemented codecs enforce `maxCompressionRatio`, including stored and
  compressed LZ4 blocks under a cumulative frame policy. STORE and uncompressed
  TAR/ar/cpio payloads honor their 1:1 ratio before copying or reading data.
  Empty output remains valid under a zero ratio.

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
