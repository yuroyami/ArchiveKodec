package io.github.yuroyami.kitearchive.archive.zip

import io.github.yuroyami.kitearchive.archive.ArchiveEntry
import io.github.yuroyami.kitearchive.archive.ArchiveFormat
import io.github.yuroyami.kitearchive.archive.ArchiveReader
import io.github.yuroyami.kitearchive.archive.RandomAccessSource
import io.github.yuroyami.kitearchive.security.ArchiveLimits

/**
 * ZIP reader — **scaffold stub**. The implementation plan (PORTING_STATUS.md):
 *  1. Scan BACKWARDS from EOF for the End-Of-Central-Directory record (variable comment).
 *  2. Read the central directory as the source of truth (local headers can lie when a
 *     data descriptor / bit-3 flag is set).
 *  3. Handle ZIP64 sentinels (0xFFFFFFFF / 0xFFFF → read the Zip64 extra field).
 *  4. Decompress per-entry via [io.github.yuroyami.kitearchive.codec.Codecs] (STORE/DEFLATE
 *     today; bzip2/LZMA/zstd as those codecs land).
 *  5. Detect duplicate local-header offsets (overlapping-bomb signature) under [ArchiveLimits].
 *
 * The reader takes a [RandomAccessSource] precisely so steps 1–2 are O(1) seeks, not a
 * full scan — see [RandomAccessSource] for why the contract is seekable, not streaming.
 */
class ZipReader(@Suppress("unused") private val source: RandomAccessSource) : ArchiveReader {

    override val format = ArchiveFormat.ZIP

    override fun entries(): List<ArchiveEntry> =
        throw NotImplementedError("ZipReader: central-directory parsing is on the roadmap (see PORTING_STATUS.md)")

    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray =
        throw NotImplementedError("ZipReader: per-entry extraction is on the roadmap (see PORTING_STATUS.md)")
}
