# Changelog

## Unreleased

- ISO 9660 reader. `IsoReader` and `ArchiveFormat.ISO9660` read CD/DVD images, preferring
  Rock Ridge names, modes, times and symlinks, then Joliet, then plain names; relocated
  Rock Ridge directories appear at their real place, multi-extent files are joined, and
  associated files are skipped. `ArchiveKodec.open` and the sniffer recognise images by
  the `CD001` signature at byte 32769.

- Encoder options. `DeflateOptions` sets zlib's levels 0 to 9 (greedy at 1 to 3, lazy at
  4 to 9), strategies (filtered, Huffman-only, RLE, fixed), 9 to 15 window bits and a
  preset dictionary, for `Deflate.encode`, `Zlib.compress`, `Gzip.compress` and ZIP
  entries. zlib writes FDICT, and `Zlib.decompress` and `Inflate.inflate` take the
  dictionary to read it. `Lz4Options` sets the LZ4 block size, checksums, content size
  and linked blocks. `Codec.compress(data, options)` passes either through. The default
  is now zlib's level 6 with lazy matching, so default DEFLATE output changes; sizes
  match zlib's within bytes at levels 1, 6 and 9, and level 1 encodes about five times
  faster than the old fixed encoder.

- The DEFLATE encoder (also behind gzip, zlib and `ZipWriter`) now writes blocks of
  16,384 symbols, each stored, fixed or dynamic, whichever is smallest, and keeps its
  match chain in a ring over the 32 KiB window. Working memory no longer grows with the
  input (16 MiB of text allocates 9 MB instead of 111 MB), random data is stored rather
  than inflated, and mixed input compresses within 0.1% of zlib level 6. Output bytes
  change for inputs longer than one block.

- Encrypted archives. ZIP reads ZipCrypto and WinZip AES (AE-1 and AE-2, 128 to 256
  bits) and writes WinZip AES through `ZipWriter.write(files, ZipEncryption(...))`; the
  7z reader decrypts AES-256 data and encrypted headers. Passwords come from the new
  `ArchiveOpenOptions.password` (`ArchivePasswordProvider`), AES from
  `ArchiveOpenOptions.crypto` (`CryptoProvider`). WinZip AES tags are verified before
  any decompression. New `ArchiveEntry.encryption`, `ArchiveEncryption`,
  `ArchivePasswordException`, `ArchiveAuthenticationException`, and `ZipReader` and
  `SevenZipReader` constructors that take `ArchiveOpenOptions`.
- New artifact `archivekodec-crypto`: `CryptographyKotlinProvider`, backed by
  cryptography-kotlin, so the core stays free of hand-written AES.

- Added PPMd. `Codecs.PPMD` and `ArchiveKodec.ppmd` are var.I revision 1 in ZIP's
  method 98 framing, so ZIP PPMd entries now read; decoding supports the restart and
  cut-off restore methods. The 7z reader now decodes PPMd var.H folders. Both models
  are ported from the reference allocator, so memory exhaustion restarts or prunes at
  the same symbol as 7-Zip. New `ArchiveLimits.maxDecoderMemory` (default 256 MiB)
  caps the model memory a header can ask for.
- `LzOutput` now enforces output and ratio limits on single-byte writes below its
  first growth, which affected brotli literals and PPMd.

- Added brotli (RFC 7932): `Codecs.BROTLI` and `ArchiveKodec.brotli`. Decoding covers
  context modelling, block switching, metadata and uncompressed meta-blocks, and the
  static dictionary with all 121 transforms; the dictionary is embedded as raw DEFLATE
  and inflated on first use. The encoder writes valid streams without context
  modelling or dictionary references. Brotli has no magic bytes, so it is not sniffed.

- Added `ExtractionBudget`, an archive-wide output cap shared across reads:
  `reader.read(entry, budget = budget)` decodes under the bytes the budget has left
  and charges what it produced, so a loop over every entry cannot pass the total.

- GNU thin `ar` archives (`!<thin>`) are now sniffed and listed. Their members are
  read through the new `ExternalMemberResolver`, passed to `ArReader` or through
  `ArchiveOpenOptions.externalMembers`; without one, reading refuses clearly.

- Added Zstandard: `Codecs.ZSTD` and `ArchiveKodec.zstd`, on new FSE and Huff0 stages.
  Decoding covers raw, RLE and compressed blocks, every literal and sequence table
  mode with reuse across blocks, repeat offsets, skippable and concatenated frames,
  and verifies the xxHash64 content checksum; dictionary frames are refused. ZIP
  method 93 entries now read and `ArchiveKodec.decompress` detects zstd. The encoder
  writes valid frames with predefined tables, less tightly than `zstd -3`.

- Added `SevenZipReader`, also returned by `ArchiveKodec.open` for 7z archives. It
  decodes compressed headers and folders using copy, LZMA, LZMA2, Deflate,
  Deflate64, bzip2, delta and BCJ x86 coders, verifies folder and file CRCs, and
  caches the last decoded folder so a solid block is decoded once for sequential
  reads. AES, BCJ2, other branch filters and PPMd are refused by name. The xz
  decoder now also accepts the x86 BCJ filter.

- Added the LZMA family: `Codecs.LZMA` (`.lzma`), `Codecs.LZMA2` (raw chunks) and
  `Codecs.XZ`, with `ArchiveKodec.lzma`, `.lzma2` and `.xz`, on a shared range coder.
  xz decoding verifies CRC-32, CRC-64 and SHA-256 checks and the index, accepts the
  delta filter, multiple blocks and concatenated streams, and refuses BCJ filters by
  name. ZIP method 14 entries now read, and `ArchiveKodec.decompress` detects xz.
  The encoders write standard streams but compress less tightly than `xz -6`.

- Added bzip2: `Codecs.BZIP2` and `ArchiveKodec.bzip2` decode any block size,
  concatenated streams and randomised blocks with CRC verification, and encode at
  level 9. ZIP method 12 entries now read, and `ArchiveKodec.decompress` detects
  bzip2 streams. A `.tar.bz2` opens with `ArchiveKodec.open(ArchiveKodec.decompress(bytes))`.

- Added Deflate64 (PKWARE "enhanced deflate"): `Codecs.DEFLATE64`,
  `CodecId.DEFLATE64`, `ArchiveKodec.deflate64` and a default binding for ZIP
  method 9, so method-9 entries now read. Decoding supports the 64 KiB window and
  long matches; encoding emits DEFLATE-compatible codes that Deflate64 decoders
  accept. Unknown-method examples now use method 11.

- Entries now report `lastModifiedEpochSeconds` (UTC) from every reader, plus the
  new `unixMode` (Unix `st_mode` with type bits) and `comment` (ZIP entry comment).
  ZIP prefers the extended timestamp and reads DOS time as UTC; an empty TAR time
  is null rather than 0. `ZipWriter.FileSpec` gains `lastModifiedEpochSeconds`,
  `unixMode` and `comment`, and `ZipWriter.write(files, comment)` sets the archive
  comment. Specs without them still produce byte-identical archives. Entry and
  FileSpec equality include the new metadata.

- `TarReader` now applies pax extended headers: `x` records to the next member and
  `g` records to every later one, overriding `path`, `size` and `mtime`. Sparse
  members (GNU `S`, pax GNU sparse 0.0, 0.1 and 1.0) list their expanded size and
  read with zero-filled holes, instead of returning the compacted stored bytes.

- **Breaking:** built-in readers now run `PathGuard` while listing. With the
  default policy, `entries()` throws `ArchivePathException` for an empty,
  absolute, drive-letter, `..` traversal or control-character name; root
  directory entries such as `./` stay allowed and names keep their stored
  spelling. The new `ArchiveLimits.rejectUnsafeNames` (default true, false in
  `UNBOUNDED`) turns the check off for callers that apply their own policy.

- **Breaking:** `ArchiveEntry` construction is now fixed: a three-argument
  `(name, size, isDirectory)` constructor, `ArchiveEntry.Builder`, the
  `ArchiveEntry(name, size, isDirectory) { ... }` factory and `copy { ... }`.
  The field-list constructor, named-argument `copy` and `component1`–`component8`
  are removed, so future metadata can be added without breaking compiled callers.
  `toString()` now escapes and bounds the name. Migrate `entry.copy(name = x)` to
  `entry.copy { name = x }`.
- **Breaking:** implementing `Codec` or `ArchiveReader` in Kotlin now requires
  `@OptIn(ExperimentalArchiveImplementation::class)`, because members may be added
  to those interfaces. Calling them is unchanged. `RandomAccessSource` stays open.

- **Breaking:** `ArchiveEntry.dataOffset`, its constructor and `copy` parameter
  and `component9` are removed. Positions are private reader state. Built-in
  readers now only read entries they listed, or unchanged copies of them;
  hand-built entries, entries from another reader and changed copies throw
  `ArchiveFormatException` naming the entry before any payload read, instead of
  reading from an unchecked offset. Entry equality no longer includes a position.
  Built-in listings are read-only snapshots. Custom readers keep their own locators.

- ZIP entries expose their raw compression method through nullable `zipMethod`,
  preserved by copies without changing the existing entry constructor or copy
  signatures. Unknown methods now report `zip-method-N` codec identifiers instead
  of STORE. Custom codec bindings retain the original method number; encrypted
  entries remain marked and refused before payload decoding.

- **Breaking:** `ArchiveFormat` is now an open immutable identifier class. Recompile
  consumers, add fallback branches to dispatch, and replace enum enumeration/ordinal
  usage with registered descriptors.
  `id` is canonical lowercase ASCII; existing constants, uppercase `name` and
  diagnostic strings remain. Swift constants now use `ArchiveFormat.companion`.
- Added immutable `FormatRegistry` and `FormatSpec` registration, custom detection
  at arbitrary source offsets, and explicit format selection through `ArchiveKodec`.
  New descriptors run first; built-in probes share one header read. Reader factories
  receive immutable `ArchiveOpenOptions` for limits, names and codec bindings.
  Gzip opening retains one-layer decoding, the selected registry and source ownership.

- **Breaking:** `CodecId` is now an open immutable class, with canonical lowercase
  ASCII names and value equality. Existing constants and `displayName` remain;
  enum `entries`/`values`/`valueOf`/ordinals and the `implemented` flag are removed.
  Recompile consumers, add an `else` to identifier dispatch, and query registry
  availability instead. `name` and `toString()` now return the lowercase codec name.
- Added immutable `CodecRegistry.DEFAULT.with(codec, zipMethod)` registration and
  per-`ZipReader` injection. Same-id replacements update existing ZIP bindings;
  explicit method bindings support external codecs without changing global defaults.
  Custom decoding retains ZIP header, output-size, policy and CRC validation.

- Added `ArchiveKodec.open(bytes)`, gzip-wrapped container opening, detected stream
  decoding, codec lookups by name/format, and LZ4/Snappy accessors. Opening unwraps
  one gzip layer under the supplied policy, then retains that policy on the reader.
  Successful source opens transfer ownership through to reader close; failed opens
  leave the source with the caller. Automatic stream decoding supports gzip, zlib,
  LZ4 and framed Snappy; unsupported errors identify the detected format.

- Added Java static utility calls, constant fields, interface convenience overloads
  and default-argument overloads for builders, copies and codec helpers. A reader's
  `read(entry)` retains its configured limits. JVM helper classes are now
  `RandomAccessSources`, `ArchiveReaders` and `ArchiveKodecIo`. These static/field
  changes and class renames break JVM binary compatibility: recompile consumers
  and update Java imports/calls. Kotlin named arguments remain supported.

- Added immutable `GzipHeader` metadata with builders and owned extra-field
  snapshots, `Gzip.compress(data, header)` and first-member `Gzip.readHeader`.
  Names/comments use Latin-1; timestamp, OS, XFL, FTEXT, extra bytes and optional
  FHCRC are exposed. Header inspection validates framing and FHCRC without
  decoding payloads. Default compressed output remains byte-for-byte unchanged.

- Added an explicit `InitramfsReader` for complete uncompressed newc/crc buffers,
  exposing segment bounds and members, resetting hard links at trailers and
  reporting later malformed/unsupported segments with absolute offsets. Listings
  count entries globally and enforce the new `ArchiveLimits.maxSegments` ceiling,
  including empty segments. `CpioReader` retains single-archive behavior.

- Added `TarWriter` for regular files and directories, with ustar headers, prefix
  splitting, GNU long-name records, header checksums, block padding and two EOF
  blocks. Member descriptions own their payloads and expose stable builders; the
  output composes with the existing gzip codec for `.tar.gz`.

- Added `CpioWriter` for buffered newc and crc archives, with configurable Unix
  metadata, four-byte alignment, trailer records and owned member snapshots.
  Directories, symlinks, caller-described hard links and empty special records
  are supported; no odc, old binary or automatic filesystem extraction is added.

- Clarified that the default 10,000:1 compression-ratio ceiling cannot reject valid
  streams from the current codecs. Dense format-level fixtures cover defaults,
  custom ratio limits and per-call byte caps; default values remain unchanged.

- Added `ArWriter` for buffered GNU ar archives, with long-name tables, even-byte
  padding and configurable numeric metadata. Member descriptions own their payloads
  and use stable constructors/builders. Names are validated UTF-8 basenames; BSD,
  thin archives and linker symbol-index generation are outside this writer's scope.

- Decoders transfer exact-sized output buffers without a trimming copy. ZIP STORE
  reads return their owned payload buffer; DEFLATE and gzip use bounded size hints
  during growth. LZ4 blocks decode directly into the frame output, with growth
  capped by each frame's declared size and ratio budget. Limits, checksums and
  independent/linked block history remain enforced.

- DEFLATE decoding uses primary and secondary Huffman lookup tables, shared fixed
  tables and a wider bit buffer. Logical cursors preserve block/trailer boundaries;
  writes inside the checked output capacity avoid repeated ratio arithmetic.

- Added an unpublished JVM/Node benchmark module with fixed corpora, CPU-time
  sampling, JDK references, a documented baseline and a CI smoke comparison.

- DEFLATE and LZ4 use compact match tables for short inputs while retaining the
  original hash keys and match choices. Huffman symbol ordering now uses primitive
  arrays with the same frequency and symbol tie order, reducing temporary allocations.

- CRC-32, CRC-32C and CRC-64 now process eight bytes at a time using tables
  initialized once for each polynomial. Short tails and split updates retain
  their checksum values; CRC-64 keeps 32-bit arithmetic in its input loop.

- Adler-32 and CRC-64 no longer perform 64-bit arithmetic for each input byte.
  Adler-32 bounds signed 32-bit sums with shorter blocks; CRC-64 keeps its state
  and lookup tables in two 32-bit halves. Checksum values and streaming APIs are unchanged.

- DEFLATE, LZ4 and Snappy decoders now copy non-overlapping matches in bulk.
  Overlapping matches keep their forward-copy semantics, including matches
  spanning LZ4 frame history and the current block; LZ4 single-byte runs use a fill.

- Added explicit Snappy framed encoding and decoding through `SNAPPY_FRAMED`
  and `Snappy.frameCompress`/`frameDecompress`, with CRC-32C validation, chunk and
  combined output limits, concatenation and skippable chunks. The sniffer now
  recognizes the framed identifier; `SNAPPY` codec APIs continue to handle raw blocks.

- LZ4 encoding now increases its search step after repeated misses, compresses
  frame blocks directly from input slices, and extends matches four bytes at a
  time. Block independence and the required final literals are preserved.

- Snappy compression now finds repeated sequences and emits back-references,
  including all three offset encodings and split long matches. Its bounded hash
  table uses at most 64 KiB; raw-block APIs and decoding limits are unchanged.

- Public metadata, format and codec identifiers now have individual API descriptions.
  Reader, codec, wrapper and IO entry points document their current failure contracts,
  and Dokka reports undocumented public declarations. No API or runtime behavior changed.

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
- Existing gzip/zlib `maximumSize: Long` source calls remain available as deprecated
  delegates. Their JVM default-call bridges now use the static signatures described
  above and require recompilation. `Gzip.DEFAULT_MAXIMUM_SIZE` remains the old
  4 MiB value for explicit compatibility use, but is deprecated and no longer
  controls defaults. Zero-byte budgets accept empty output. Budget violations
  now consistently throw `DecompressionBombException`; malformed wrapper headers,
  trailers and checksum mismatches throw `ArchiveFormatException`.
- The JDK interop helper requires a completed stream and full input consumption,
  including the zlib trailer, and releases native inflater resources on failures.
