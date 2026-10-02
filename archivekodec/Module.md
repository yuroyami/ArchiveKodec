# Module archivekodec

Compression codecs, archive readers and a ZIP writer, in pure common Kotlin.

DEFLATE, gzip, zlib, LZ4 and Snappy behind one `Codec` interface; readers for
ZIP (with ZIP64), TAR, ar and cpio behind `ArchiveReader`; CRC-32, CRC-32C,
Adler-32, CRC-64/XZ and xxHash32; and a magic-byte format sniffer. Everything is
`ByteArray` in, `ByteArray` out, with no I/O and no dependency beyond
`kotlin-stdlib`.
