package io.github.yuroyami.archivekodec.archive.cpio

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.checkedArraySize
import kotlin.jvm.JvmSynthetic
import kotlin.text.CharacterCodingException

/**
 * Buffered cpio writer for the `newc` and `crc` ASCII dialects used by initramfs.
 * Writes 110-byte headers, NUL-terminated UTF-8 names, four-byte alignment and a
 * final `TRAILER!!!` record. The complete archive must fit in one ByteArray.
 * The crc dialect stores an unsigned byte sum, not a cyclic redundancy check.
 *
 * Names are preserved, not sanitized for filesystem extraction. Empty names,
 * embedded NUL, malformed Unicode and the exact trailer name are rejected.
 * Metadata defaults are reproducible: inode/time/IDs/devices zero, one link and
 * regular-file mode 0100644. Hard links require caller-supplied matching inode and
 * device numbers with [FileSpec.linkCount] greater than one. Payload placement and
 * member ordering are preserved; no filesystem is inspected or modified.
 * No odc, old binary or compressed output is emitted. File descriptions own their
 * payload snapshots; this object has no mutable state.
 */
public object CpioWriter {
    /** ASCII cpio header and checksum convention. */
    public enum class Dialect {
        /** Magic 070701, with a zero check field. */
        NEWC,
        /** Magic 070702, with the body's unsigned byte sum modulo 2^32. */
        CRC,
    }

    /** Immutable member metadata and an owned payload snapshot. */
    public class FileSpec private constructor(builder: Builder) {
        /** Exact archive name, validated by [write]; not a sanitized filesystem path. */
        public val name: String = builder.name
        /** Independent copy of the member's stored body. */
        public val data: ByteArray get() = content.copyOf()
        /** Unsigned 32-bit inode number; default zero. */
        public val inode: Long = builder.inode
        /** Unix file type and permissions in the low 16 bits; default octal 0100644. */
        public val mode: Int = builder.mode
        /** Unsigned 32-bit owner ID; default zero. */
        public val uid: Long = builder.uid
        /** Unsigned 32-bit group ID; default zero. */
        public val gid: Long = builder.gid
        /** Unsigned 32-bit link count; default one. Values above one identify hard-link groups. */
        public val linkCount: Long = builder.linkCount
        /** Unsigned 32-bit seconds since the Unix epoch; default zero. */
        public val modificationTime: Long = builder.modificationTime
        /** Unsigned 32-bit major number of the containing device; default zero. */
        public val deviceMajor: Long = builder.deviceMajor
        /** Unsigned 32-bit minor number of the containing device; default zero. */
        public val deviceMinor: Long = builder.deviceMinor
        /** Unsigned 32-bit major number of a device node; default zero. */
        public val rdevMajor: Long = builder.rdevMajor
        /** Unsigned 32-bit minor number of a device node; default zero. */
        public val rdevMinor: Long = builder.rdevMinor

        init {
            for (value in longArrayOf(inode, uid, gid, linkCount, modificationTime,
                deviceMajor, deviceMinor, rdevMajor, rdevMinor)) {
                require(value in 0..0xffffffffL) { "cpio: metadata must fit an unsigned 32-bit field" }
            }
            require(mode in 0..0xffff) { "cpio: mode must fit the Unix type and permission bits" }
            val type = mode and 0xf000
            require(type in intArrayOf(0x1000, 0x2000, 0x4000, 0x6000, 0x8000, 0xa000, 0xc000)) {
                "cpio: mode must identify a supported Unix file type"
            }
            require(type == 0x8000 || type == 0xa000 || builder.data.isEmpty()) {
                "cpio: only regular files and symlinks may have a data body"
            }
            require(type != 0xa000 || builder.data.isNotEmpty() && 0.toByte() !in builder.data) {
                "cpio: a symlink needs a nonempty target without NUL"
            }
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
            /** Stored body, copied by [build]. Symlinks store their target here. */
            public var data: ByteArray,
        ) {
            /** See [FileSpec.inode]. Must fit an unsigned 32-bit field. */
            public var inode: Long = 0
            /** See [FileSpec.mode]. Directories and special files require an empty body. */
            public var mode: Int = 0x81a4
            /** See [FileSpec.uid]. Must fit an unsigned 32-bit field. */
            public var uid: Long = 0
            /** See [FileSpec.gid]. Must fit an unsigned 32-bit field. */
            public var gid: Long = 0
            /** See [FileSpec.linkCount]. Must fit an unsigned 32-bit field. */
            public var linkCount: Long = 1
            /** See [FileSpec.modificationTime]. Must fit an unsigned 32-bit field. */
            public var modificationTime: Long = 0
            /** See [FileSpec.deviceMajor]. Must fit an unsigned 32-bit field. */
            public var deviceMajor: Long = 0
            /** See [FileSpec.deviceMinor]. Must fit an unsigned 32-bit field. */
            public var deviceMinor: Long = 0
            /** See [FileSpec.rdevMajor]. Must fit an unsigned 32-bit field. */
            public var rdevMajor: Long = 0
            /** See [FileSpec.rdevMinor]. Must fit an unsigned 32-bit field. */
            public var rdevMinor: Long = 0

            internal constructor(source: FileSpec) : this(source.name, source.content.copyOf()) {
                inode = source.inode
                mode = source.mode
                uid = source.uid
                gid = source.gid
                linkCount = source.linkCount
                modificationTime = source.modificationTime
                deviceMajor = source.deviceMajor
                deviceMinor = source.deviceMinor
                rdevMajor = source.rdevMajor
                rdevMinor = source.rdevMinor
            }

            /**
             * Snapshot settings and body.
             * @throws IllegalArgumentException for out-of-range metadata, an unknown file
             * type, a body on a directory/special file, or an empty/NUL-containing symlink target.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): FileSpec = FileSpec(this)
        }

        /**
         * Configure an independent copy, including independently mutable body bytes.
         * @throws IllegalArgumentException if the resulting settings fail [Builder.build] validation.
         */
        @Throws(IllegalArgumentException::class)
        public fun copy(configure: Builder.() -> Unit = {}): FileSpec = Builder(this).apply(configure).build()

        /** Compare names, numeric metadata and body contents. */
        override fun equals(other: Any?): Boolean = this === other || other is FileSpec &&
            name == other.name && inode == other.inode && mode == other.mode && uid == other.uid &&
            gid == other.gid && linkCount == other.linkCount && modificationTime == other.modificationTime &&
            deviceMajor == other.deviceMajor && deviceMinor == other.deviceMinor &&
            rdevMajor == other.rdevMajor && rdevMinor == other.rdevMinor && content.contentEquals(other.content)

        /** Hash names, numeric metadata and body contents. */
        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + inode.hashCode()
            result = 31 * result + mode
            result = 31 * result + uid.hashCode()
            result = 31 * result + gid.hashCode()
            result = 31 * result + linkCount.hashCode()
            result = 31 * result + modificationTime.hashCode()
            result = 31 * result + deviceMajor.hashCode()
            result = 31 * result + deviceMinor.hashCode()
            result = 31 * result + rdevMajor.hashCode()
            result = 31 * result + rdevMinor.hashCode()
            return 31 * result + content.contentHashCode()
        }

        /** Bounded description without dumping names or bodies. */
        override fun toString(): String = "FileSpec(nameLength=${name.length}, bytes=${content.size}, " +
            "inode=$inode, mode=${mode.toString(8)}, linkCount=$linkCount)"

        /** Construct member settings with a builder action. */
        public companion object {
            /**
             * Configure metadata before taking a snapshot.
             * @throws IllegalArgumentException if the settings fail [Builder.build] validation.
             */
            @Throws(IllegalArgumentException::class)
            public operator fun invoke(name: String, data: ByteArray, configure: Builder.() -> Unit): FileSpec =
                Builder(name, data).apply(configure).build()
        }
    }

    private class Prepared(val spec: FileSpec, val name: ByteArray)
    private data class LinkKey(val major: Long, val minor: Long, val inode: Long)

    /**
     * Write [Dialect.NEWC] members in the supplied order, followed by the trailer.
     * @throws ArchiveFormatException for an invalid name or conflicting hard-link file types.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if output cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun write(files: List<FileSpec>): ByteArray = write(files, Dialect.NEWC)

    /**
     * Write the selected dialect, preserving duplicate names, order and body placement.
     * Validates names, hard-link types and total size before allocating archive storage.
     * Empty input produces only the padded trailer. No 512-byte block padding is added.
     *
     * @throws ArchiveFormatException for an empty/reserved name, NUL, malformed Unicode,
     * or conflicting file types in a hard-link group.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if output cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun write(files: List<FileSpec>, dialect: Dialect): ByteArray {
        var total = 124L + 112L * files.size // Trailer and the smallest possible named headers.
        checkedArraySize(total)
        val prepared = ArrayList<Prepared>()
        val links = HashMap<LinkKey, Int>()
        for (spec in files) {
            val name = encodeName(spec.name)
            val type = spec.mode and 0xf000
            if (spec.linkCount > 1 && type != 0x4000) {
                val key = LinkKey(spec.deviceMajor, spec.deviceMinor, spec.inode)
                val previous = links.put(key, type)
                if (previous != null && previous != type) {
                    throw ArchiveFormatException("cpio: conflicting hard-link file types", entryName = spec.name)
                }
            }
            total += align4(111L + name.size) - 112 + align4(spec.payload.size.toLong())
            checkedArraySize(total)
            prepared.add(Prepared(spec, name))
        }
        val out = ByteArrayBuilder(checkedArraySize(total))
        for (file in prepared) {
            val spec = file.spec
            writeRecord(out, dialect, file.name, spec.payload, longArrayOf(
                spec.inode, spec.mode.toLong(), spec.uid, spec.gid, spec.linkCount,
                spec.modificationTime, spec.payload.size.toLong(), spec.deviceMajor,
                spec.deviceMinor, spec.rdevMajor, spec.rdevMinor, file.name.size + 1L,
                if (dialect == Dialect.CRC) byteSum(spec.payload) else 0,
            ))
        }
        writeRecord(out, dialect, "TRAILER!!!".encodeToByteArray(), byteArrayOf(),
            longArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 11, 0))
        return out.takeByteArray()
    }

    private fun encodeName(name: String): ByteArray {
        if (name.isEmpty() || '\u0000' in name || name == "TRAILER!!!") {
            throw ArchiveFormatException("cpio: invalid or reserved member name", entryName = name)
        }
        return try {
            name.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw ArchiveFormatException("cpio: filename is not valid Unicode", entryName = name, cause = error)
        }
    }

    private fun writeRecord(out: ByteArrayBuilder, dialect: Dialect, name: ByteArray, data: ByteArray, fields: LongArray) {
        val header = ByteArray(110)
        (if (dialect == Dialect.NEWC) "070701" else "070702").encodeToByteArray().copyInto(header)
        var at = 6
        for (field in fields) for (shift in 28 downTo 0 step 4) {
            header[at++] = "0123456789abcdef"[((field ushr shift) and 15).toInt()].code.toByte()
        }
        out.append(header)
        out.append(name)
        out.append(0)
        repeat((4 - (out.size and 3)) and 3) { out.append(0) }
        out.append(data)
        repeat((4 - (out.size and 3)) and 3) { out.append(0) }
    }

    private fun byteSum(data: ByteArray): Long {
        var sum = 0L
        for (byte in data) sum += byte.toLong() and 255L
        return sum and 0xffffffffL
    }

    private fun align4(size: Long): Long = (size + 3) and -4L
}
