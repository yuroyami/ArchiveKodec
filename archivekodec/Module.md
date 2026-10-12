# Module archivekodec

Compression codecs, archive readers and writers, and checksums, in pure common Kotlin.

The codecs are DEFLATE, Deflate64, gzip, zlib, bzip2, LZMA, LZMA2, xz, zstd, brotli, PPMd,
LZ4 and Snappy, behind one `Codec` interface. `ArchiveReader` reads ZIP and ZIP64, TAR, ar,
cpio, 7z, RAR, ISO 9660, CAB, WIM and SquashFS, and writers exist for ZIP, TAR, ar and cpio.
The module also has CRC-32, CRC-32C, Adler-32, CRC-64/XZ and xxHash32, a format sniffer and
the `ArchiveLimits` checks. It does no file I/O and depends on `kotlin-stdlib` only.

Builds for Android, JVM, JS, wasmJs, wasmWasi, iOS, macOS (arm64), tvOS, watchOS, Linux,
mingwX64 and androidNative. `archivekodec-io` shares this target list.
