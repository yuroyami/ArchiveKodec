package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.codec.deflate.InflateException
import io.github.yuroyami.archivekodec.security.DecompressionBombException

/** Attach known source context without losing the failure category or original cause. */
internal inline fun <T> withEntryContext(name: String, offset: Long, operation: () -> T): T = try {
    operation()
} catch (error: ArchiveException) {
    throw error.withEntryContext(name, offset)
}

internal fun ArchiveException.withEntryContext(name: String, fallbackOffset: Long, relativeOffset: Boolean = false): ArchiveException {
    if (entryName != null && offset != null) return this
    val position = if (relativeOffset && offset != null) fallbackOffset + offset else offset ?: fallbackOffset
    val label = entryName ?: name
    val text = "${message} (entry '$label', source offset $position)"
    return when (this) {
        is DecompressionBombException -> DecompressionBombException(text, position, label, this)
        is ArchiveUnsupportedException -> ArchiveUnsupportedException(text, position, label, this)
        is ArchiveIOException -> ArchiveIOException(text, this, position, label)
        is ArchiveFormatException, is InflateException -> ArchiveFormatException(text, position, label, this)
        else -> ArchiveException(text, this, position, label)
    }
}
