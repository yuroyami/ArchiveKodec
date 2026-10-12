package io.github.yuroyami.archivekodec.archive.tar

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.checkedArraySize
import kotlin.jvm.JvmSynthetic
import kotlin.text.CharacterCodingException

/**
 * Buffered ustar writer for regular files and directories, with GNU `L` records
 * when a UTF-8 name cannot fit the ustar name/prefix fields. Emits unsigned header
 * checksums, 512-byte data padding and two zero EOF blocks. The complete archive
 * must fit in one ByteArray. Compress the result separately to produce `.tar.gz`.
 *
 * Names are preserved, not sanitized for filesystem extraction. Empty names,
 * embedded NUL and malformed Unicode are rejected. A name ending in slash must
 * describe a directory. Metadata defaults are reproducible: time/IDs zero and
 * permission mode 0644. No filesystem is inspected or modified.
 * This writer does not emit links, devices, pax metadata, sparse files or binary
 * numeric fields. File descriptions own their payload snapshots.
 */
public object TarWriter {
    /** Immutable regular-file or directory settings and an owned body snapshot. */
    public class FileSpec private constructor(builder: Builder) {
        /** Exact archive name, validated by [write]; not a sanitized filesystem path. */
        public val name: String = builder.name
        /** Independent copy of the stored body; directories have no body. */
        public val data: ByteArray get() = content.copyOf()
        /** Whether to emit a directory header (type 5) instead of a regular file (type 0). */
        public val isDirectory: Boolean = builder.isDirectory
        /** Unix permission bits, without file-type bits; default octal 0644. */
        public val mode: Int = builder.mode
        /** Nonnegative owner ID fitting seven octal digits; default zero. */
        public val uid: Int = builder.uid
        /** Nonnegative group ID fitting seven octal digits; default zero. */
        public val gid: Int = builder.gid
        /** Nonnegative Unix seconds fitting eleven octal digits; default zero. */
        public val modificationTime: Long = builder.modificationTime

        init {
            require(mode in 0..0xfff) { "TAR: mode must contain only Unix permission bits" }
            require(uid in 0..0x1fffff && gid in 0..0x1fffff) { "TAR: uid and gid must fit seven octal digits" }
            require(modificationTime in 0..0x1ffffffffL) { "TAR: modificationTime must fit eleven octal digits" }
            require(!isDirectory || builder.data.isEmpty()) { "TAR: directories cannot have a data body" }
        }

        private val content: ByteArray = builder.data.copyOf()

        @get:JvmSynthetic
        internal val payload: ByteArray get() = content

        /** Snapshot [data] with reproducible regular-file metadata. */
        @Throws(IllegalArgumentException::class)
        public constructor(name: String, data: ByteArray) : this(Builder(name, data))

        /** Mutable single-thread settings; [build] takes an independent snapshot. */
        public class Builder public constructor(
            /** Exact member name, validated by [write]. */
            public var name: String,
            /** Body bytes copied by [build]. */
            public var data: ByteArray,
        ) {
            /** See [FileSpec.isDirectory]. A directory requires an empty body. */
            public var isDirectory: Boolean = false
            /** Permission bits from octal 0000 through 7777; default octal 0644. */
            public var mode: Int = 0x1a4
            /** Owner ID from zero through octal 7777777; default zero. */
            public var uid: Int = 0
            /** Group ID from zero through octal 7777777; default zero. */
            public var gid: Int = 0
            /** Unix seconds from zero through octal 77777777777; default zero. */
            public var modificationTime: Long = 0

            internal constructor(source: FileSpec) : this(source.name, source.content.copyOf()) {
                isDirectory = source.isDirectory
                mode = source.mode
                uid = source.uid
                gid = source.gid
                modificationTime = source.modificationTime
            }

            /**
             * Snapshot settings and body.
             * @throws IllegalArgumentException for out-of-range metadata or a directory with data.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): FileSpec = FileSpec(this)
        }

        /**
         * Configure an independent copy, including independently mutable body bytes.
         * @throws IllegalArgumentException if the resulting settings fail [Builder.build] validation.
         */
        @Throws(IllegalArgumentException::class)
        @JvmOverloads
        public fun copy(configure: Builder.() -> Unit = {}): FileSpec = Builder(this).apply(configure).build()

        /** Compare names, numeric metadata, directory flags and body contents. */
        override fun equals(other: Any?): Boolean = this === other || other is FileSpec &&
            name == other.name && isDirectory == other.isDirectory && mode == other.mode &&
            uid == other.uid && gid == other.gid && modificationTime == other.modificationTime &&
            content.contentEquals(other.content)

        /** Hash metadata and body contents. */
        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + isDirectory.hashCode()
            result = 31 * result + mode
            result = 31 * result + uid
            result = 31 * result + gid
            result = 31 * result + modificationTime.hashCode()
            return 31 * result + content.contentHashCode()
        }

        /** Bounded description without dumping names or bodies. */
        override fun toString(): String = "FileSpec(nameLength=${name.length}, bytes=${content.size}, " +
            "isDirectory=$isDirectory, mode=${mode.toString(8)}, uid=$uid, gid=$gid, modificationTime=$modificationTime)"

        /** Construct member settings with a builder action. */
        public companion object {
            /**
             * Configure metadata before taking a snapshot.
             * @throws IllegalArgumentException if the settings fail [Builder.build] validation.
             */
            @Throws(IllegalArgumentException::class)
            @JvmStatic
            public operator fun invoke(name: String, data: ByteArray, configure: Builder.() -> Unit): FileSpec =
                Builder(name, data).apply(configure).build()
        }
    }

    private class Prepared(val spec: FileSpec, val name: ByteArray, val prefix: ByteArray, val longName: ByteArray?)

    /**
     * Assemble members in the supplied order, preserving duplicate names.
     * Uses ustar prefix splitting where possible and a GNU long-name record otherwise.
     * Validates names and the complete size before allocating archive storage.
     * Empty input produces exactly two zero blocks. No compression is applied.
     *
     * @throws ArchiveFormatException for invalid names, malformed Unicode, or a regular-file name ending in slash.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if output cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun write(files: List<FileSpec>): ByteArray {
        var total = 1024L + 512L * files.size
        checkedArraySize(total)
        val prepared = ArrayList<Prepared>()
        for (spec in files) {
            val encoded = encodeName(spec)
            var name = encoded
            var prefix = byteArrayOf()
            var longName: ByteArray? = null
            if (encoded.size > 100) {
                val split = if (encoded.size <= 256) (encoded.lastIndex downTo 1).firstOrNull {
                    encoded[it].toInt() == 47 && it <= 155 && encoded.size - it - 1 in 1..100
                } else null
                if (split != null) {
                    prefix = encoded.copyOfRange(0, split)
                    name = encoded.copyOfRange(split + 1, encoded.size)
                } else {
                    longName = encoded
                    name = "LongName".encodeToByteArray()
                    total += 512 + align512(encoded.size + 1L)
                }
            }
            total += align512(spec.payload.size.toLong())
            checkedArraySize(total)
            prepared.add(Prepared(spec, name, prefix, longName))
        }
        val out = ByteArrayBuilder(checkedArraySize(total))
        for (file in prepared) {
            val spec = file.spec
            val longName = file.longName
            if (longName != null) {
                out.append(header("././@LongLink".encodeToByteArray(), byteArrayOf(), longName.size + 1L, 'L', 0x1a4, 0, 0, 0))
                out.append(longName)
                out.append(0)
                pad(out)
            }
            out.append(header(file.name, file.prefix, spec.payload.size.toLong(),
                if (spec.isDirectory) '5' else '0', spec.mode, spec.uid, spec.gid, spec.modificationTime))
            out.append(spec.payload)
            pad(out)
        }
        out.append(ByteArray(1024))
        return out.takeByteArray()
    }

    private fun encodeName(spec: FileSpec): ByteArray {
        val name = spec.name
        if (name.isEmpty() || '\u0000' in name || !spec.isDirectory && name.endsWith('/')) {
            throw ArchiveFormatException("TAR: invalid member name or directory spelling on a regular file", entryName = name)
        }
        return try {
            name.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw ArchiveFormatException("TAR: filename is not valid Unicode", entryName = name, cause = error)
        }
    }

    private fun header(name: ByteArray, prefix: ByteArray, size: Long, type: Char, mode: Int, uid: Int, gid: Int, time: Long): ByteArray {
        val bytes = ByteArray(512)
        name.copyInto(bytes)
        octal(bytes, 100, 8, mode.toLong())
        octal(bytes, 108, 8, uid.toLong())
        octal(bytes, 116, 8, gid.toLong())
        octal(bytes, 124, 12, size)
        octal(bytes, 136, 12, time)
        bytes.fill(32, 148, 156)
        bytes[156] = type.code.toByte()
        "ustar\u0000".encodeToByteArray().copyInto(bytes, 257)
        bytes[263] = 48
        bytes[264] = 48
        octal(bytes, 329, 8, 0)
        octal(bytes, 337, 8, 0)
        prefix.copyInto(bytes, 345)
        var checksum = 0L
        for (byte in bytes) checksum += byte.toLong() and 255L
        octal(bytes, 148, 7, checksum) // Six octal digits, NUL, then the existing space.
        return bytes
    }

    private fun octal(bytes: ByteArray, start: Int, width: Int, value: Long) {
        var rest = value
        for (index in start + width - 2 downTo start) {
            bytes[index] = (48 + (rest and 7).toInt()).toByte()
            rest = rest ushr 3
        }
        bytes[start + width - 1] = 0
    }

    private fun pad(out: ByteArrayBuilder) {
        repeat((512 - (out.size and 511)) and 511) { out.append(0) }
    }

    private fun align512(size: Long): Long = (size + 511) and -512L
}
