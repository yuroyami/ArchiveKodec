package io.github.yuroyami.archivekodec

import kotlin.jvm.JvmOverloads

/**
 * Base for expected archive and codec failures. [offset] identifies the source
 * byte when known; [entryName] identifies the member when known.
 */
public open class ArchiveException @JvmOverloads public constructor(
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
public class ArchiveFormatException @JvmOverloads public constructor(
    message: String,
    offset: Long? = null,
    entryName: String? = null,
    cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/** An in-memory archive or codec result cannot be represented by a single ByteArray. */
public class ArchiveSizeException(message: String) : ArchiveException(message)

/** A recognized format, compression method or requested operation is not implemented. */
public class ArchiveUnsupportedException @JvmOverloads public constructor(
    message: String, offset: Long? = null, entryName: String? = null, cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/** A source or adapter failed while performing IO; [cause] retains the original failure. */
public class ArchiveIOException @JvmOverloads public constructor(
    message: String, cause: Throwable, offset: Long? = null, entryName: String? = null,
) : ArchiveException(message, cause, offset, entryName)

/** Listing or reading was attempted after the reader released its source. */
public class ArchiveClosedException @JvmOverloads public constructor(
    message: String, offset: Long? = null, entryName: String? = null, cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/** A member is encrypted and no password was supplied, or the supplied one is wrong. */
public class ArchivePasswordException @JvmOverloads public constructor(
    message: String, offset: Long? = null, entryName: String? = null, cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/**
 * Encrypted data failed its authentication code: it was altered or damaged after it was
 * encrypted. Raised before any decompression runs on the data.
 */
public class ArchiveAuthenticationException @JvmOverloads public constructor(
    message: String, offset: Long? = null, entryName: String? = null, cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)
