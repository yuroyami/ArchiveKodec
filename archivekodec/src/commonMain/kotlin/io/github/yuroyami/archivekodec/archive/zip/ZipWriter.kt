package io.github.yuroyami.archivekodec.archive.zip

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.DosTime
import io.github.yuroyami.archivekodec.util.checkedArraySize
import io.github.yuroyami.archivekodec.util.describeName
import io.github.yuroyami.archivekodec.util.le16
import io.github.yuroyami.archivekodec.util.le32
import io.github.yuroyami.archivekodec.util.le64
import kotlin.jvm.JvmSynthetic
import kotlin.text.CharacterCodingException

/**
 * ZIP writer for STORE and DEFLATE entries, with ZIP64 where it is needed. The whole
 * archive is built in memory and returned as a `ByteArray`; there is no streaming
 * form. ZIP64 end records are emitted when the entry count passes 65535. Sizes and
 * offsets cannot reach the ZIP64 boundary in this whole-array API; the complete
 * output must fit in one `ByteArray`. Smaller entry counts use classic ZIP records.
 * Member sizes and offsets always use classic 32-bit fields, even when the entry
 * count requires ZIP64 end records.
 * Names are UTF-8 with bit 11 set in both headers. Their encoded length must fit
 * the 16-bit filename field, including for ZIP64 archives. Accepted names retain
 * their exact spelling. Backslashes, leading slashes, drive/device prefixes in
 * the first nonempty, non-dot component, parent (`..`) components, ASCII controls
 * (including NUL and DEL), and malformed Unicode are rejected before compression.
 * Relative dot components, repeated forward slashes, trailing directory slashes,
 * and empty anonymous names are supported; filesystem mapping is the caller's job.
 * Entries use the reproducible default timestamp 1980-01-01 00:00:00 unless
 * [FileSpec.lastModifiedEpochSeconds] is set. A set time is written both as a UTC DOS
 * time (clamped to 1980–2107, odd seconds rounded down) and, when it fits a signed
 * 32-bit value, as an Info-ZIP extended timestamp, which readers prefer. A set
 * [FileSpec.unixMode] marks the entry as made on Unix and stores the mode in the high
 * 16 bits of the external attributes. Specs without these settings produce the same
 * bytes as before they existed.
 *
 * Import this writer directly; it is not reachable from the `ArchiveKodec` object.
 */
public object ZipWriter {

    /**
     * Immutable file description with an owned payload snapshot. The public [data]
     * getter returns a copy; input arrays and retained builders cannot change it.
     * Equality and hashing compare [name], [method], payload content and the optional
     * time, mode and comment.
     *
     * Direct constructors have fixed two/three-argument signatures. Builder actions
     * and [copy] accept future metadata without expanding those signatures. Native
     * construction reserves a catchable argument-error bridge for validated metadata.
     * Name/path validation remains at [write], before compression or output assembly.
     */
    public class FileSpec private constructor(builder: Builder) {
        /** Exact archive spelling. Validated by [write]; no normalization is applied. */
        public val name: String = builder.name

        private val content: ByteArray = builder.data.copyOf()

        /** Independent copy of the uncompressed payload. Each access copies the bytes. */
        public val data: ByteArray get() = content.copyOf()

        /** Supported ZIP method. The default is [ZipMethod.DEFLATED]. */
        public val method: ZipMethod = builder.method

        /** Modification time in Unix epoch seconds, written as UTC; null writes 1980-01-01. */
        public val lastModifiedEpochSeconds: Long? = builder.lastModifiedEpochSeconds

        /** Unix `st_mode` in `0..0xFFFF`, such as `0x81ED` (`0o100755`); null writes no Unix attributes. */
        public val unixMode: Int? = builder.unixMode

        /** Entry comment, written as UTF-8 in the central directory; null writes none. */
        public val comment: String? = builder.comment

        @get:JvmSynthetic
        internal val payload: ByteArray get() = content

        @get:JvmSynthetic
        internal val commentBytes: ByteArray? = comment?.let { encodeComment(it, "entry comment") }

        init {
            require(unixMode == null || unixMode in 0..0xFFFF) { "ZIP: unixMode $unixMode does not fit 16 bits" }
        }

        /** Snapshot [data] using the default deflated method. */
        @Throws(IllegalArgumentException::class)
        public constructor(name: String, data: ByteArray) : this(name, data, ZipMethod.DEFLATED)

        /** Snapshot [data] using the selected supported [method]. */
        @Throws(IllegalArgumentException::class)
        public constructor(name: String, data: ByteArray, method: ZipMethod) :
            this(Builder(name, data).apply { this.method = method })

        /**
         * Mutable single-thread configuration. [data] remains mutable while configuring;
         * [build] takes an independent snapshot. Future settings are body properties.
         */
        public class Builder public constructor(
            /** Exact archive filename; validated when [ZipWriter.write] is called. */
            public var name: String,
            /** Mutable payload to snapshot when [build] is called. */
            public var data: ByteArray,
        ) {
            /** Compression method, initially [ZipMethod.DEFLATED]. */
            public var method: ZipMethod = ZipMethod.DEFLATED

            /** Modification time in Unix epoch seconds, initially null. */
            public var lastModifiedEpochSeconds: Long? = null

            /** Unix `st_mode` in `0..0xFFFF`, initially null. Validated by [build]. */
            public var unixMode: Int? = null

            /** Entry comment, initially null. Its UTF-8 form must fit 65535 bytes; validated by [build]. */
            public var comment: String? = null

            internal constructor(source: FileSpec) : this(source.name, source.content.copyOf()) {
                method = source.method
                lastModifiedEpochSeconds = source.lastModifiedEpochSeconds
                unixMode = source.unixMode
                comment = source.comment
            }

            /**
             * Snapshot settings and bytes.
             * @throws IllegalArgumentException if [unixMode] exceeds 16 bits, or [comment] is not
             * valid Unicode or its UTF-8 form exceeds 65535 bytes.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): FileSpec = FileSpec(this)
        }

        /**
         * Configure an independent builder from this file, then snapshot it.
         * Mutable bytes inside [configure] belong to the copy, never the source.
         * @throws IllegalArgumentException if [configure] throws an argument error.
         */
        @Throws(IllegalArgumentException::class)
        @JvmOverloads
        public fun copy(configure: Builder.() -> Unit = {}): FileSpec = Builder(this).apply(configure).build()

        /** Compare the filename, compression method and payload content. */
        override fun equals(other: Any?): Boolean = this === other || other is FileSpec &&
            name == other.name && method == other.method && content.contentEquals(other.content) &&
            lastModifiedEpochSeconds == other.lastModifiedEpochSeconds && unixMode == other.unixMode && comment == other.comment

        /** Hash the filename, compression method, payload content and optional metadata. */
        override fun hashCode(): Int {
            var result = 31 * (31 * name.hashCode() + method.hashCode()) + content.contentHashCode()
            result = 31 * result + (lastModifiedEpochSeconds?.hashCode() ?: 0)
            result = 31 * result + (unixMode ?: 0)
            return 31 * result + (comment?.hashCode() ?: 0)
        }

        /** Bounded description with an escaped name, byte count, method and any set metadata; no payload dump. */
        override fun toString(): String = buildString {
            append("FileSpec(name=\"").append(describeName(name)).append("\", bytes=").append(content.size)
            append(", method=").append(method)
            lastModifiedEpochSeconds?.let { append(", lastModifiedEpochSeconds=").append(it) }
            unixMode?.let { append(", unixMode=0o").append(it.toString(8)) }
            comment?.let { append(", comment=\"").append(describeName(it)).append('"') }
            append(')')
        }

        /** Construct file descriptions with a builder action. */
        public companion object {
            /**
             * Configure additional settings before taking a snapshot, for example
             * `FileSpec("a", bytes) { method = ZipMethod.STORED }`.
             * @throws IllegalArgumentException if [configure] throws an argument error.
             */
            @Throws(IllegalArgumentException::class)
            @JvmStatic
            @JvmOverloads
            public operator fun invoke(
                name: String,
                data: ByteArray,
                method: ZipMethod = ZipMethod.DEFLATED,
                configure: Builder.() -> Unit = {},
            ): FileSpec = Builder(name, data).apply { this.method = method }.apply(configure).build()
        }
    }

    private class PreparedFile(val spec: FileSpec, val nameBytes: ByteArray)

    private class Rec(
        val nameBytes: ByteArray,
        val method: Int,
        val crc: Long,
        val comp: Long,
        val uncomp: Long,
        val offset: Long,
        val spec: FileSpec,
        val dosDateTime: Int,
        val extra: ByteArray,
    )

    private const val MAX_COMMENT_BYTES = 0xFFFF
    private const val UNIX_HOST = 3 shl 8

    /** Strict UTF-8 for a ZIP comment field. */
    private fun encodeComment(comment: String, what: String): ByteArray {
        val bytes = try {
            comment.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("ZIP: $what is not valid Unicode", error)
        }
        require(bytes.size <= MAX_COMMENT_BYTES) { "ZIP: $what of ${bytes.size} UTF-8 bytes exceeds 65535" }
        return bytes
    }

    /** Info-ZIP extended timestamp (0x5455) holding only the modification time, or empty. */
    private fun extendedTimestamp(epochSeconds: Long?): ByteArray {
        if (epochSeconds == null || epochSeconds !in Int.MIN_VALUE..Int.MAX_VALUE) return ByteArray(0)
        val t = epochSeconds.toInt()
        return byteArrayOf(0x55, 0x54, 5, 0, 1, t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte())
    }

    private const val UTF8_FLAG = 0x0800
    private const val MAX_NAME_BYTES = 0xFFFF
    private const val DEFAULT_DOS_DATE = 0x0021 // 1980-01-01

    private fun encodeName(name: String): ByteArray {
        // UTF-8 needs at least one byte per valid UTF-16 code unit. Reject
        // names that cannot fit before allocating their encoded representation.
        if (name.length > MAX_NAME_BYTES) {
            throw ArchiveFormatException("ZIP: UTF-8 filename exceeds 65535 bytes", entryName = name)
        }
        if (name.startsWith('/')) {
            throw ArchiveFormatException("ZIP: filename has a leading slash", entryName = name)
        }
        var start = 0
        var colon = -1
        var firstComponent = true
        for (at in 0..name.length) {
            if (at < name.length) {
                val char = name[at]
                if (char == '\\') {
                    throw ArchiveFormatException("ZIP: filename contains a backslash", entryName = name)
                }
                if (char.code <= 0x1F || char.code == 0x7F) {
                    throw ArchiveFormatException("ZIP: filename contains an ASCII control character", entryName = name)
                }
                if (char != '/') {
                    if (char == ':' && colon < 0) colon = at
                    continue
                }
            }
            val length = at - start
            if (length == 2 && name[start] == '.' && name[start + 1] == '.') {
                throw ArchiveFormatException("ZIP: filename contains a parent path component", entryName = name)
            }
            if (firstComponent && length > 0 && !(length == 1 && name[start] == '.')) {
                if (colon > start) {
                    throw ArchiveFormatException("ZIP: filename has a drive or device prefix", entryName = name)
                }
                firstComponent = false
            }
            start = at + 1
            colon = -1
        }
        val bytes = try {
            name.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw ArchiveFormatException("ZIP: filename is not valid Unicode", entryName = name, cause = error)
        }
        if (bytes.size > MAX_NAME_BYTES) {
            throw ArchiveFormatException("ZIP: UTF-8 filename exceeds 65535 bytes", entryName = name)
        }
        return bytes
    }

    /**
     * Validates every name before compressing data or constructing output. Accepted
     * names are written unchanged; invalid paths are never silently normalized.
     * A drive/device prefix is a nonempty prefix followed by `:` in the first
     * nonempty component other than `.`. Colons in later components are supported.
     *
     * @throws ArchiveFormatException if a name violates the path or Unicode rules
     * documented above, or its UTF-8 encoding exceeds 65535 bytes.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the archive cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun write(files: List<FileSpec>): ByteArray = write(files, ByteArray(0))

    /**
     * Write [files] with an archive [comment] in the end record, as UTF-8.
     *
     * @throws IllegalArgumentException if [comment] is not valid Unicode or its UTF-8 form exceeds 65535 bytes.
     * @throws ArchiveFormatException if a name violates the rules documented on [write].
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the archive cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    @JvmStatic
    public fun write(files: List<FileSpec>, comment: String): ByteArray = write(files, encodeComment(comment, "archive comment"))

    private fun write(files: List<FileSpec>, archiveComment: ByteArray): ByteArray {
        // Include both headers and the end records before constructing any output.
        var plannedSize = 22L + archiveComment.size + (if (files.size > 0xFFFF) 76 else 0) + files.size.toLong() * 76
        checkedArraySize(plannedSize)
        val prepared = ArrayList<PreparedFile>(files.size)
        for (f in files) {
            val nameBytes = encodeName(f.name)
            plannedSize += nameBytes.size * 2L + extendedTimestamp(f.lastModifiedEpochSeconds).size * 2L + (f.commentBytes?.size ?: 0)
            if (f.method == ZipMethod.STORED) plannedSize += f.payload.size
            checkedArraySize(plannedSize)
            prepared.add(PreparedFile(f, nameBytes))
        }
        val out = ByteArrayBuilder(1024)
        val recs = ArrayList<Rec>(prepared.size)

        for (file in prepared) {
            val f = file.spec
            val data = f.payload
            val offset = out.size.toLong()
            val nameBytes = file.nameBytes
            val crc = Crc32.of(data)
            val method = f.method.code
            val comp = when (f.method) {
                ZipMethod.STORED -> data
                ZipMethod.DEFLATED -> Deflate.encode(data)
            }
            if (f.method != ZipMethod.STORED) {
                plannedSize += comp.size
                checkedArraySize(plannedSize)
            }
            val dosDateTime = f.lastModifiedEpochSeconds?.let(DosTime::fromEpochSeconds) ?: (DEFAULT_DOS_DATE shl 16)
            val extra = extendedTimestamp(f.lastModifiedEpochSeconds)
            out.le32(ZipFormat.LOC)
            out.le16(20)                       // version needed
            out.le16(UTF8_FLAG)                // flags: UTF-8 name
            out.le16(method)
            out.le16(dosDateTime and 0xFFFF); out.le16(dosDateTime ushr 16) // DOS time, then date
            out.le32(crc)
            out.le32(comp.size.toLong()); out.le32(data.size.toLong())
            out.le16(nameBytes.size)
            out.le16(extra.size)
            out.append(nameBytes)
            out.append(extra)
            out.append(comp)

            recs.add(Rec(nameBytes, method, crc, comp.size.toLong(), data.size.toLong(), offset, f, dosDateTime, extra))
        }

        val cdStart = out.size.toLong()
        for (r in recs) {
            val mode = r.spec.unixMode
            val comment = r.spec.commentBytes ?: ByteArray(0)
            out.le32(ZipFormat.CEN)
            out.le16(if (mode == null) 45 else UNIX_HOST or 45) // version made by, host 3 = Unix
            out.le16(20)                       // version needed
            out.le16(UTF8_FLAG)                // flags: UTF-8 name and comment
            out.le16(r.method)
            out.le16(r.dosDateTime and 0xFFFF); out.le16(r.dosDateTime ushr 16)
            out.le32(r.crc)
            out.le32(r.comp); out.le32(r.uncomp)
            out.le16(r.nameBytes.size)
            out.le16(r.extra.size)
            out.le16(comment.size)
            out.le16(0)                        // disk start
            out.le16(0)                        // internal attrs
            // st_mode in the high 16 bits; directories also carry the MS-DOS directory bit.
            val directoryBit = if (r.spec.name.endsWith('/')) 0x10L else 0L
            out.le32(if (mode == null) 0 else (mode.toLong() shl 16) or directoryBit)
            out.le32(r.offset)
            out.append(r.nameBytes)
            out.append(r.extra)
            out.append(comment)
        }
        val cdSize = out.size.toLong() - cdStart
        val count = recs.size

        // Sizes and offsets fit in a ByteArray's Int range; only the entry count
        // can overflow its classic ZIP field.
        if (count > 0xFFFF) {
            val z64Pos = out.size.toLong()
            out.le32(ZipFormat.Z64_EOCD)
            out.le64(44L)                      // size of remaining record
            out.le16(45); out.le16(45)
            out.le32(0); out.le32(0)           // this disk, cd start disk
            out.le64(count.toLong()); out.le64(count.toLong())
            out.le64(cdSize); out.le64(cdStart)
            out.le32(ZipFormat.Z64_LOC)
            out.le32(0)                        // disk of zip64 eocd
            out.le64(z64Pos)
            out.le32(1)                        // total disks
        }

        out.le32(ZipFormat.EOCD)
        out.le16(0); out.le16(0)
        out.le16(minOf(count, 0xFFFF)); out.le16(minOf(count, 0xFFFF))
        out.le32(cdSize); out.le32(cdStart)
        out.le16(archiveComment.size)
        out.append(archiveComment)
        return out.toByteArray()
    }
}
