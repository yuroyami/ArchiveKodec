package io.github.yuroyami.archivekodec

/**
 * Base for expected archive and codec failures. [offset] identifies the source
 * byte when known; [entryName] identifies the member when known.
 */
public open class ArchiveException(
    message: String,
    cause: Throwable? = null,
    /** Absolute source byte offset of the failure, or null when unavailable. */
    public val offset: Long? = null,
    /** Stored member name associated with the failure, or null when unavailable. */
    public val entryName: String? = null,
) : Exception(message, cause) {
    /** Describe the failure with its available member name and source offset. */
    override fun toString(): String = buildString {
        append(this@ArchiveException::class.simpleName ?: "ArchiveException")
        append(": ").append(message)
        entryName?.let { append(" [entry '").append(it).append("']") }
        offset?.let { append(" [offset ").append(it).append(']') }
    }
}

/** Malformed or truncated archive data, including a declared range outside its source. */
public class ArchiveFormatException(
    message: String,
    offset: Long? = null,
    entryName: String? = null,
    cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/** An in-memory archive or codec result cannot be represented by a single ByteArray. */
public class ArchiveSizeException(message: String) : ArchiveException(message)

/** A recognized format, compression method or requested operation is not implemented. */
public class ArchiveUnsupportedException(
    message: String, offset: Long? = null, entryName: String? = null, cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/** A source or adapter failed while performing IO; [cause] retains the original failure. */
public class ArchiveIOException(
    message: String, cause: Throwable, offset: Long? = null, entryName: String? = null,
) : ArchiveException(message, cause, offset, entryName)

/** Listing or reading was attempted after the reader released its source. */
public class ArchiveClosedException(
    message: String, offset: Long? = null, entryName: String? = null, cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)
