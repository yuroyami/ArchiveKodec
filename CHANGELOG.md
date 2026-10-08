# Changelog

## Unreleased

- Binary readers and writers now share internal byte-order helpers, and zlib
  decoding and sniffing share header validation. The test fixtures share one hex
  decoder, with a registry check keeping codec support flags in sync. Removed
  unreachable ZIP64 member-size and offset branches from the buffered writer;
  entry-count ZIP64 end records remain supported. Public APIs are unchanged.

- CpioReader now rejects empty input and unknown or truncated header magic with
  `ArchiveFormatException`, including after valid records. Failed listings never
  expose or cache a partial result. The sniffer recognizes both byte orders of
  old binary cpio; listing that unsupported dialect throws
  `ArchiveUnsupportedException` naming the header offset. ASCII cpio trailers
  and their following block padding remain supported.

- ZIP, TAR, ar and cpio listings now enforce `ArchiveLimits.maxEntries` while
  parsing, with a default ceiling of 100,000 entries. ZIP checks both the declared
  count and actual directory records, including wrapped classic counts. Container
  metadata and end markers are excluded; directories and cpio hard-link records
  count as entries. Excess counts throw `DecompressionBombException` before an
  additional entry is retained, and failed listings are not cached. For larger
  trusted archives, pass a policy with a larger `maxEntries` to the new reader
  constructors or `ArchiveKodec.open` overloads. Existing overloads remain.
  `ArchiveReader.limits` exposes the reader policy and supplies the default for
  each `read`; an explicit read policy still overrides it. Output byte limits
  remain per call, with no archive-wide accumulation.

- Breaking binary change: `ArchiveKodec.VERSION` is a `@JvmStatic val` instead of a
  `const val`, so it reports the version of the jar that runs rather than one
  inlined at compile time. Kotlin source that reads it is unchanged, but it can no
  longer appear in constant expressions such as annotation arguments. Java callers
  use `ArchiveKodec.getVERSION()` instead of the removed static field. The value
  is generated from the Gradle `version` property, the only place the version is
  written. `checkReadmeVersion` fails when the README coordinates name another
  release.

- The JVM and Android classes are Java 8 bytecode (major version 52) instead of
  Java 21 (65), so they load on Java 8 through 20 as well. The JVM compilation uses
  `-Xjdk-release=1.8`. Both jars declare an `Automatic-Module-Name`
  (`io.github.yuroyami.archivekodec`, `io.github.yuroyami.archivekodec.io`).
  Language and API version are pinned to Kotlin 2.2, so consumers need Kotlin 2.1
  or newer. Building still needs JDK 21.

- Both artifacts now build for `wasmJs`, `wasmWasi`, `macosArm64`, `tvosArm64`,
  `tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`,
  `watchosSimulatorArm64`, `linuxX64`, `linuxArm64`, `mingwX64` and the four
  androidNative targets. `archivekodec-io` also gains `js`, so the two artifacts
  share one target matrix and can go in the same `commonMain`. That matrix covers
  every target KitePDF and ImageKodec declare. `macosX64` and `tvosX64` are not
  added, because Kotlin 2.4 deprecates them. No public API changes; the klib ABI
  references list the new targets.

- Breaking ZIP file model change for the next minor release: `ZipWriter.FileSpec`
  is an ordinary immutable class with fixed two/three-argument constructors, a
  builder action factory and `copy { ... }`. Use `ZipMethod.STORED` or `DEFLATED`
  instead of `CodecId`; the default remains deflated. The codec getter, generated
  field-list copy and component functions are removed. Payloads are snapshotted
  and the public data getter returns a copy. Equality/hash use content consistently,
  and descriptions show a bounded escaped name, byte count and method. Native
  construction/build/copy use a throwing argument-error bridge for future metadata
  validation. Existing encoded ZIP bytes and complete filename preflight are preserved.

- Breaking policy API change for the next minor release: `ArchiveLimits` is an
  ordinary immutable class with a private constructor. Use `ArchiveLimits { ... }`
  and `limits.copy { ... }` in Kotlin, or configure `ArchiveLimits.Builder` and call
  `build()` from Java/Swift. The field-list constructor, data-class copy and
  component functions are removed. Builder/factory/copy signatures remain fixed
  when settings are added. Every build takes an independent snapshot and rejects
  negative settings with a named `IllegalArgumentException`, exported through
  Swift throws. Default values and zero budgets keep their existing meaning.
  Deprecated gzip/zlib `maximumSize` overloads reject negative caps with the same
  catchable argument error.

- Breaking API changes for the next minor release: `ByteArrayBuilder`,
  `InflateError.fromPuff` and the raw `Lz4.blockCompress` / `blockDecompress`
  helpers are internal. Use a registered codec or the public LZ4 frame operations.
  `InflateError.SPACE_EXHAUSTED` is removed; output that cannot fit in a ByteArray
  throws the shared `ArchiveSizeException`. Rebuild callers and update code that
  referenced those helpers or the removed enum entry. Enum ordinals after that
  entry change; persist the documented error codes rather than ordinal positions.
  Direct `Deflate`, `Inflate`, `Gzip` and `Zlib` operations remain supported
  low-level public APIs. Both modules require strict explicit API declarations
  and committed ABI references for JVM, Android, JS and iOS.

- DEFLATE dynamic trees use optimal length-limited package-merge coding with
  symbol-coverage, length and code-space checks. This fixes oversubscribed trees
  that made raw DEFLATE, gzip, zlib and deflated ZIP output undecodable for skewed
  inputs. Compressed bytes can change; decoded content and public APIs are unchanged.

- ZIP, TAR, ar and cpio readers own and close their supplied source exactly once,
  clear cached metadata on close, and reject subsequent listing or reads with
  `ArchiveClosedException`. Cleanup failures leave the reader closed. Kotlin
  `ArchiveReader.use` and an `asAutoCloseable()` view support scoped cleanup with
  suppressed secondary errors while preserving the Swift throwing contract.

- ZIP writing uses the valid reproducible default timestamp 1980-01-01 00:00:00
  in both headers. The DOS date field is 0x0021 instead of the invalid zero month
  and day; output remains independent of the clock and timezone.

- ZIP end records must have a comment length that reaches EOF. False candidates
  inside comments are skipped, and multi-disk metadata in classic/ZIP64 end
  records, locators, or entries throws `ArchiveUnsupportedException` with context.
  Every central-directory entry sentinel must resolve through a complete ZIP64
  extra field in the specified order. Missing, truncated, duplicate, or out-of-range
  required fields throw `ArchiveFormatException` with the entry name and metadata offset. Resolved
  32-bit maximum values remain valid metadata.

- ZIP listing parses the whole central directory and rejects mismatched counts,
  incomplete records, unexplained trailing bytes, and overlap with end records.
  Classic counts may wrap modulo 65536; ZIP64 counts must match exactly. The exact
  classic maximum count is supported without ZIP64. Optional directory signature
  records are bounds-checked, and a present ZIP64 locator supplies the authoritative
  fields even when the classic fields are not sentinels.

- ZIP writing rejects names longer than 65535 UTF-8 bytes before output assembly
  or compression, with `ArchiveFormatException` carrying the entry name. Names
  at the exact byte limit remain supported. Both local and central headers now
  declare UTF-8, preserving accented, CJK, and non-BMP names in other ZIP readers.

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
