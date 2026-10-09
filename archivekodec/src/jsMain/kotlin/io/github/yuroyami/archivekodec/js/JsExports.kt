@file:OptIn(ExperimentalJsExport::class)
@file:JsExport

/**
 * The library as JavaScript and TypeScript see it: the declarations in this package are the
 * exports of the npm module. Kotlin callers use [io.github.yuroyami.archivekodec.ArchiveKodec]
 * instead; this package exists because JavaScript cannot use Kotlin collections, `Long` or
 * default arguments on interfaces, which the Kotlin API is built from.
 *
 * Bytes are `Int8Array`, Kotlin's `ByteArray`. Wrap a `Uint8Array` without copying as
 * `new Int8Array(u8.buffer, u8.byteOffset, u8.length)`, and back the same way. Sizes are
 * numbers. Failures throw an `Error` whose message says what was wrong and where.
 *
 * Every decoding call is bounded by
 * [io.github.yuroyami.archivekodec.security.ArchiveLimits.DEFAULT], with its 2 GiB output
 * cap replaced by `maxBytes` where a call takes one.
 */
package io.github.yuroyami.archivekodec.js

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.crypto.ArchivePasswordProvider
import io.github.yuroyami.archivekodec.security.ArchiveLimits

private const val DEFAULT_MAX_BYTES: Double = 2.0 * 1024 * 1024 * 1024

/** The version of the library, such as `0.2.0`. */
public fun version(): String = ArchiveKodec.VERSION

/** The ids of the available codecs, for [compress] and [decompress]: `"gzip"`, `"zstd"` and so on. */
public fun codecs(): Array<String> = ArchiveKodec.codecs.map { it.name }.toTypedArray()

/**
 * The format [data] starts with, as an id such as `"zip"`, `"gzip"` or `"seven_zip"`, or
 * `"unknown"`. Only the first bytes are looked at; 64 KiB is always enough.
 */
public fun sniff(data: ByteArray): String = ArchiveKodec.sniff(data).id

/**
 * Compress [data] with the codec named [codec], one of [codecs]. [level] (0–9) applies to
 * the DEFLATE family (`deflate`, `zlib`, `gzip`); other codecs use their default.
 */
public fun compress(codec: String, data: ByteArray, level: Int = 6): ByteArray {
    val chosen = codecNamed(codec)
    return if (codec == "deflate" || codec == "zlib" || codec == "gzip") chosen.compress(data, DeflateOptions { this.level = level })
    else chosen.compress(data)
}

/** Decompress [data] with the codec named [codec], producing at most [maxBytes] bytes. */
public fun decompress(codec: String, data: ByteArray, maxBytes: Double = DEFAULT_MAX_BYTES): ByteArray =
    codecNamed(codec).decompress(data, limits(maxBytes))

/** Decompress [data], detecting gzip, zlib, bzip2, xz, LZMA, zstd, LZ4, Brotli or framed Snappy. */
public fun decompressAny(data: ByteArray, maxBytes: Double = DEFAULT_MAX_BYTES): ByteArray =
    ArchiveKodec.decompress(data, limits(maxBytes))

/** Compress [data] as a gzip member. */
public fun gzip(data: ByteArray, level: Int = 6): ByteArray = compress("gzip", data, level)

/** Decompress every member of a gzip stream. */
public fun gunzip(data: ByteArray, maxBytes: Double = DEFAULT_MAX_BYTES): ByteArray = decompress("gzip", data, maxBytes)

/** Compress [data] as a raw DEFLATE stream, with no header or checksum. */
public fun deflate(data: ByteArray, level: Int = 6): ByteArray = compress("deflate", data, level)

/** Decompress a raw DEFLATE stream. */
public fun inflate(data: ByteArray, maxBytes: Double = DEFAULT_MAX_BYTES): ByteArray = decompress("deflate", data, maxBytes)

/** Compress [data] as a zlib stream (RFC 1950). */
public fun zlib(data: ByteArray, level: Int = 6): ByteArray = compress("zlib", data, level)

/** Decompress a zlib stream (RFC 1950). */
public fun unzlib(data: ByteArray, maxBytes: Double = DEFAULT_MAX_BYTES): ByteArray = decompress("zlib", data, maxBytes)

/** Compress [data] as an LZ4 frame. */
public fun lz4(data: ByteArray): ByteArray = compress("lz4", data)

/** Decompress an LZ4 frame. */
public fun unlz4(data: ByteArray, maxBytes: Double = DEFAULT_MAX_BYTES): ByteArray = decompress("lz4", data, maxBytes)

/**
 * Open an archive held in [data]: ZIP, TAR (also gzip-wrapped), 7z, RAR, CAB, ISO 9660,
 * WIM, SquashFS, ar or cpio. The format is detected unless [format] names it, as an id from
 * [sniff]. [password] opens encrypted members. Each member read produces at most [maxBytes]
 * bytes.
 *
 * The archive keeps [data]; do not change it until [Archive.close].
 */
public fun open(
    data: ByteArray,
    format: String? = null,
    password: String? = null,
    maxBytes: Double = DEFAULT_MAX_BYTES,
): Archive {
    val options = ArchiveOpenOptions {
        limits = limits(maxBytes)
        if (password != null) this.password = ArchivePasswordProvider.of(password.toCharArray())
    }
    return Archive(ArchiveKodec.open(data, format, options))
}

/** Write a ZIP archive holding [members], each DEFLATE-compressed, in the order given. */
public fun zip(members: Array<ZipMember>): ByteArray =
    ZipWriter.write(members.map { ZipWriter.FileSpec(it.name, it.data) })

/** An open archive, from [open]. Read members by name or by position in [entries], then [close] it. */
public class Archive internal constructor(private val reader: ArchiveReader) {
    private val listed: List<ArchiveEntry> = reader.entries()

    /** The archive's format id, such as `"zip"`. */
    public val format: String = reader.format.id

    /** The members, in the order the archive stores them. A fresh array on each access. */
    public val entries: Array<Entry> get() = Array(listed.size) { Entry(listed[it], it) }

    /**
     * The contents of the first member called [name]. A symbolic link reads as its target's
     * path in the formats that store the target as data.
     *
     * @throws Error if no member has that name, or the member cannot be read.
     */
    public fun read(name: String): ByteArray {
        val entry = listed.firstOrNull { it.name == name } ?: throw IllegalArgumentException("no member named '$name'")
        return reader.read(entry)
    }

    /** The contents of the member at [index] in [entries], for archives that repeat a name. */
    public fun readAt(index: Int): ByteArray {
        require(index in listed.indices) { "no member at index $index; the archive has ${listed.size}" }
        return reader.read(listed[index])
    }

    /** Release the archive. Reads after closing throw. */
    public fun close() {
        reader.close()
    }
}

/** One member of an [Archive]. */
public class Entry internal constructor(entry: ArchiveEntry, index: Int) {
    /** The position of this member in [Archive.entries], for [Archive.readAt]. */
    public val index: Int = index

    /** The stored name, `/`-separated; directories may end with `/`. Not a safe path as it is. */
    public val name: String = entry.name

    /** The uncompressed size in bytes. */
    public val size: Double = entry.size.toDouble()

    /** The stored (compressed) size in bytes. */
    public val compressedSize: Double = entry.compressedSize.toDouble()

    /** True for a directory. */
    public val isDirectory: Boolean = entry.isDirectory

    /** True when reading needs a password. */
    public val isEncrypted: Boolean = entry.isEncrypted

    /** True for a symbolic link; see [linkTarget]. */
    public val isSymbolicLink: Boolean = entry.isSymbolicLink

    /** True for a hard link, which reads as the data of the member it names. */
    public val isHardLink: Boolean = entry.isHardLink

    /** The path a link points to, where the format stores it as metadata, or null. */
    public val linkTarget: String? = entry.linkTarget

    /** The modification time in milliseconds since 1970, as `new Date(...)` takes, or null. */
    public val lastModified: Double? = entry.lastModifiedEpochSeconds?.let { it.toDouble() * 1000 }

    /** The CRC-32 the archive records for the contents, or null. */
    public val crc32: Double? = entry.crc32?.toDouble()

    /** The Unix permission and type bits, or null. */
    public val unixMode: Int? = entry.unixMode

    /** The codec the member is stored with, such as `"deflate"` or `"store"`. */
    public val codec: String = entry.codec.name

    /** The member's comment, or null. */
    public val comment: String? = entry.comment
}

/** A file for [zip]: its [name] in the archive and its [data]. */
public class ZipMember(
    /** The name in the archive, `/`-separated. */
    public val name: String,
    /** The contents. */
    public val data: ByteArray,
)

private fun codecNamed(name: String): Codec =
    ArchiveKodec.codec(name) ?: throw ArchiveUnsupportedException("no codec named '$name'")

private fun limits(maxBytes: Double): ArchiveLimits {
    require(maxBytes >= 0 && maxBytes <= Long.MAX_VALUE.toDouble()) { "maxBytes must be a non-negative number, not $maxBytes" }
    val cap = maxBytes.toLong()
    return if (cap == ArchiveLimits.DEFAULT.maxUncompressedBytes) ArchiveLimits.DEFAULT
    else ArchiveLimits.DEFAULT.copy { maxUncompressedBytes = cap }
}
