package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.codec.CodecId

/**
 * One member of a container. [name] is the raw stored name. Always run it through
 * [io.github.yuroyami.archivekodec.security.PathGuard] before joining it to an output
 * directory. [dataOffset] is where the entry's bytes begin in the source (container
 * internal); -1 when not applicable.
 */
data class ArchiveEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModifiedEpochSeconds: Long? = null,
    val compressedSize: Long = size,
    val codec: CodecId = CodecId.STORE,
    val isEncrypted: Boolean = false,
    val crc32: Long? = null,
    val dataOffset: Long = -1L,
)
