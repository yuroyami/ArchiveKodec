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
) : Exception(message, cause)

/** Malformed or truncated archive data, including a declared range outside its source. */
class ArchiveFormatException(
    message: String,
    offset: Long? = null,
    entryName: String? = null,
    cause: Throwable? = null,
) : ArchiveException(message, cause, offset, entryName)

/** An in-memory archive or codec result cannot be represented by a single ByteArray. */
class ArchiveSizeException(message: String) : ArchiveException(message)
