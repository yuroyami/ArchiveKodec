@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.checkedArraySize
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/*
 * Foundation bridges for Swift and Objective-C. They extend the ArchiveKodec object, which
 * Swift sees as methods on `ArchiveKodec.shared`, and take and return `NSData`, so Swift
 * passes and receives `Data` without building a `KotlinByteArray` byte by byte. Each copies
 * the bytes once with memcpy.
 */

/** A new `NSData` holding a copy of [bytes]. Swift: `ArchiveKodec.shared.data(bytes:)`. */
public fun ArchiveKodec.data(bytes: ByteArray): NSData {
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
}

/**
 * A new `ByteArray` holding a copy of [data]. Swift: `ArchiveKodec.shared.bytes(data:)`.
 *
 * @throws ArchiveSizeException if [data] is larger than a `ByteArray` can hold (2 GiB).
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.bytes(data: NSData): ByteArray {
    val length: Long = data.length.convert()
    val result = ByteArray(checkedArraySize(length))
    if (result.isEmpty()) return result
    result.usePinned { memcpy(it.addressOf(0), data.bytes, length.convert()) }
    return result
}

/**
 * Compress [data] with [codec], such as `ArchiveKodec.shared.gzip`.
 *
 * @throws ArchiveException if [codec] cannot compress or [data] is too large.
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.compress(codec: Codec, data: NSData): NSData = data(codec.compress(bytes(data)))

/**
 * Decompress [data] with [codec] under [ArchiveLimits.DEFAULT].
 *
 * @throws ArchiveException for malformed input or output beyond the limits.
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.decompress(codec: Codec, data: NSData): NSData = decompress(codec, data, ArchiveLimits.DEFAULT)

/**
 * Decompress [data] with [codec] under [limits].
 *
 * @throws ArchiveException for malformed input or output beyond [limits].
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.decompress(codec: Codec, data: NSData, limits: ArchiveLimits): NSData =
    data(codec.decompress(bytes(data), limits))

/**
 * Detect the compressed format of [data] and decompress it, as [ArchiveKodec.decompress]
 * does for a `ByteArray`, under [ArchiveLimits.DEFAULT].
 *
 * @throws ArchiveException for an unrecognized format, malformed input or output beyond the limits.
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.decompress(data: NSData): NSData = decompress(data, ArchiveLimits.DEFAULT)

/**
 * Detect the compressed format of [data] and decompress it under [limits].
 *
 * @throws ArchiveException for an unrecognized format, malformed input or output beyond [limits].
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.decompress(data: NSData, limits: ArchiveLimits): NSData = data(decompress(bytes(data), limits))

/**
 * Open the archive held in [data], detecting its format, under [ArchiveLimits.DEFAULT]. The
 * reader keeps its own copy of the bytes, so [data] may change afterwards. Close the reader
 * when done.
 *
 * @throws ArchiveException as [ArchiveKodec.open] does for bytes.
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.open(data: NSData): ArchiveReader = open(bytes(data))

/**
 * Open the archive held in [data] under [limits]. The reader keeps its own copy of the bytes.
 *
 * @throws ArchiveException as [ArchiveKodec.open] does for bytes.
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.open(data: NSData, limits: ArchiveLimits): ArchiveReader = open(bytes(data), limits)

/**
 * Open the archive held in [data] as [format] (a format id such as `"zip"`, or null to
 * detect it) with [options], for passwords, name decoding and limits. The reader keeps its
 * own copy of the bytes.
 *
 * @throws ArchiveException as [ArchiveKodec.open] does for bytes.
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.open(data: NSData, format: String?, options: ArchiveOpenOptions): ArchiveReader =
    open(bytes(data), format, options)

/**
 * The contents of [entry], read by [reader] under the reader's limits.
 * Swift: `ArchiveKodec.shared.read(reader:entry:)`.
 *
 * @throws ArchiveException as [ArchiveReader.read] does.
 */
@Throws(ArchiveException::class)
public fun ArchiveKodec.read(reader: ArchiveReader, entry: ArchiveEntry): NSData = data(reader.read(entry))
