package io.github.yuroyami.archivekodec

/**
 * Base for expected archive and codec failures. [offset] identifies the source
 * byte when known; [entryName] identifies the member when known.
 */
open class ArchiveException(
    message: String,
    cause: Throwable? = null,
    val offset: Long? = null,
    val entryName: String? = null,
) : Exception(message, cause) {
    override fun toString(): String = buildString {
        append(this@ArchiveException::class.simpleName ?: "ArchiveException")
        append(": ").append(message)
        entryName?.let { append(" [entry '").append(it).append("']") }
        offset?.let { append(" [offset ").append(it).append(']') }
    }
}

/** Malformed or truncated archive data, including a declared range outside its source. */
class ArchiveFormatException(
    message: String,
    offset: Long? = null,
    entryName: String? = null,
    cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/** An in-memory archive or codec result cannot be represented by a single ByteArray. */
class ArchiveSizeException(message: String) : ArchiveException(message)

/** A recognized format, compression method or requested operation is not implemented. */
class ArchiveUnsupportedException(
    message: String, offset: Long? = null, entryName: String? = null, cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/** A source or adapter failed while performing IO; [cause] retains the original failure. */
class ArchiveIOException(
    message: String, cause: Throwable, offset: Long? = null, entryName: String? = null,
) : ArchiveException(message, cause, offset, entryName)
