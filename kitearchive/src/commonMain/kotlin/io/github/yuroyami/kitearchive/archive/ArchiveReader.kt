package io.github.yuroyami.kitearchive.archive

import io.github.yuroyami.kitearchive.security.ArchiveLimits

/**
 * Read side of a container. [entries] lists members from the header alone and touches
 * no payload, which is "browse without extract". [read] materialises one entry's
 * uncompressed bytes under the bomb [ArchiveLimits].
 */
interface ArchiveReader {
    /** The container format this reader handles. */
    val format: ArchiveFormat

    /** List members from the header/directory only. */
    fun entries(): List<ArchiveEntry>

    /** Read one entry's uncompressed bytes, enforcing [limits]. */
    fun read(entry: ArchiveEntry, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray

    /** Release the underlying source. */
    fun close() {}
}
