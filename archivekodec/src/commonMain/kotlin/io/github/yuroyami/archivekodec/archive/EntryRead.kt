package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveClosedException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.codec.deflate.InflateException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.ArchivePathException
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.security.PathGuard

/** Attach known source context without losing the failure category or original cause. */
internal inline fun <T> withEntryContext(name: String, offset: Long, operation: () -> T): T = try {
    operation()
} catch (error: ArchiveException) {
    throw error.withEntryContext(name, offset)
}

internal fun ArchiveException.withEntryContext(name: String, fallbackOffset: Long, relativeOffset: Boolean = false): ArchiveException {
    if (entryName != null && offset != null || this is ArchivePathException) return this
    val position = if (relativeOffset && offset != null) fallbackOffset + offset else offset ?: fallbackOffset
    val label = entryName ?: name
    val text = "${message} (entry '$label', source offset $position)"
    return when (this) {
        is ArchiveClosedException -> ArchiveClosedException(text, position, label, this)
        is DecompressionBombException -> DecompressionBombException(text, position, label, this)
        is ArchiveUnsupportedException -> ArchiveUnsupportedException(text, position, label, this)
        is ArchiveIOException -> ArchiveIOException(text, this, position, label)
        is ArchiveFormatException, is InflateException -> ArchiveFormatException(text, position, label, this)
        else -> ArchiveException(text, this, position, label)
    }
}

/**
 * Check every name against [limits], then mark each listed entry as [owner]'s, so
 * [resolveIssued] accepts it and its unchanged copies. Returns a read-only snapshot,
 * so a caller cannot replace a trusted entry.
 *
 * @throws io.github.yuroyami.archivekodec.security.ArchivePathException for an unsafe
 * name when [ArchiveLimits.rejectUnsafeNames] is set.
 */
internal fun List<ArchiveEntry>.issuedBy(owner: Any, limits: ArchiveLimits): List<ArchiveEntry> {
    if (limits.rejectUnsafeNames) {
        for (entry in this) if (!PathGuard.isRootDirectory(entry)) PathGuard.sanitize(entry.name)
    }
    val snapshot = toTypedArray()
    snapshot.forEachIndexed { index, entry -> entry.issue(owner, index) }
    return object : AbstractList<ArchiveEntry>() {
        override val size: Int get() = snapshot.size
        override fun get(index: Int): ArchiveEntry = snapshot[index]
    }
}

/**
 * Return [owner]'s listed entry for [entry]. Entries built by hand, issued by another
 * reader, or copied with changed metadata fail with an exception naming the entry.
 */
internal fun List<ArchiveEntry>.resolveIssued(owner: Any, entry: ArchiveEntry, format: String): ArchiveEntry {
    val listed = getOrNull(entry.slotIn(owner))
        ?: throw ArchiveFormatException("$format: entry '${entry.name}' was not listed by this reader", null, entry.name)
    if (listed != entry) {
        throw ArchiveFormatException("$format: supplied entry metadata differs from this reader's listing", listed.dataOffset, listed.name)
    }
    return listed
}
