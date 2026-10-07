package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.checkedArraySize
import kotlin.text.CharacterCodingException
import kotlin.jvm.JvmSynthetic

/**
 * ZIP writer for STORE and DEFLATE entries, with ZIP64 where it is needed. The whole
 * archive is built in memory and returned as a `ByteArray`; there is no streaming
 * form. ZIP64 records are emitted only when the entry count passes 65535 or a size or
 * offset overflows 32 bits, with per-entry ZIP64 extra fields for oversized entries,
 * so ordinary archives stay classic and small.
 * Names are UTF-8 with bit 11 set in both headers. Their encoded length must fit
 * the 16-bit filename field, including for ZIP64 archives. Accepted names retain
 * their exact spelling. Backslashes, leading slashes, drive/device prefixes in
 * the first nonempty, non-dot component, parent (`..`) components, ASCII controls
 * (including NUL and DEL), and malformed Unicode are rejected before compression.
 * Relative dot components, repeated forward slashes, trailing directory slashes,
 * and empty anonymous names are supported; filesystem mapping is the caller's job.
 * Entries use the reproducible default timestamp 1980-01-01 00:00:00.
 *
 * This is the only writer in the library, and it is not reachable from the
 * `ArchiveKodec` object.
 */
public object ZipWriter {

    /**
     * Immutable file description with an owned payload snapshot. The public [data]
     * getter returns a copy; input arrays and retained builders cannot change it.
     * Equality and hashing compare [name], [method] and payload content.
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

        @get:JvmSynthetic
        internal val payload: ByteArray get() = content

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
        public class Builder public constructor(public var name: String, public var data: ByteArray) {
            /** Compression method, initially [ZipMethod.DEFLATED]. */
            public var method: ZipMethod = ZipMethod.DEFLATED

            internal constructor(source: FileSpec) : this(source.name, source.content.copyOf()) {
                method = source.method
            }

            /** Snapshot settings and bytes. Reserves native throws for validated metadata. */
            @Throws(IllegalArgumentException::class)
            public fun build(): FileSpec = FileSpec(this)
        }

        /**
         * Configure an independent builder from this file, then snapshot it.
         * Mutable bytes inside [configure] belong to the copy, never the source.
         * @throws IllegalArgumentException if [configure] throws an argument error.
         */
        @Throws(IllegalArgumentException::class)
        public fun copy(configure: Builder.() -> Unit = {}): FileSpec = Builder(this).apply(configure).build()

        override fun equals(other: Any?): Boolean = this === other || other is FileSpec &&
            name == other.name && method == other.method && content.contentEquals(other.content)

        override fun hashCode(): Int = 31 * (31 * name.hashCode() + method.hashCode()) + content.contentHashCode()

        /** Bounded description with an escaped name, byte count and method; no payload dump. */
        override fun toString(): String = "FileSpec(name=\"${descriptionName()}\", bytes=${content.size}, method=$method)"

        private fun descriptionName(): String = buildString {
            var at = 0
            while (at < name.length) {
                val char = name[at]
                var width = 1
                val text = when (char) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> when {
                        char.code in 0xD800..0xDBFF && at + 1 < name.length && name[at + 1].code in 0xDC00..0xDFFF -> {
                            width = 2
                            name.substring(at, at + 2)
                        }
                        char.code < 0x20 || char.code == 0x7F || char.code in 0xD800..0xDFFF ->
                            "\\u${char.code.toString(16).padStart(4, '0')}"
                        else -> char.toString()
                    }
                }
                if (length + text.length > 96) {
                    append('…')
                    break
                }
                append(text)
                at += width
            }
        }

        public companion object {
            /**
             * Configure additional settings before taking a snapshot, for example
             * `FileSpec("a", bytes) { method = ZipMethod.STORED }`.
             * @throws IllegalArgumentException if [configure] throws an argument error.
             */
            @Throws(IllegalArgumentException::class)
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
        val zip64: Boolean,
    )

    private const val MASK32 = 0xFFFFFFFFL
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
    public fun write(files: List<FileSpec>): ByteArray {
        // Include both headers and the end records before constructing any output.
        var plannedSize = 22L + (if (files.size > 0xFFFF) 76 else 0) + files.size.toLong() * 76
        checkedArraySize(plannedSize)
        val prepared = ArrayList<PreparedFile>(files.size)
        for (f in files) {
            val nameBytes = encodeName(f.name)
            plannedSize += nameBytes.size * 2L
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
            val needs64 = offset > MASK32 || comp.size.toLong() > MASK32 || data.size.toLong() > MASK32

            out.le32(ZipFormat.LOC)
            out.le16(if (needs64) 45 else 20) // version needed
            out.le16(UTF8_FLAG)                // flags: UTF-8 name
            out.le16(method)
            out.le16(0); out.le16(DEFAULT_DOS_DATE) // midnight, 1980-01-01
            out.le32(crc)
            if (needs64) { out.le32(MASK32); out.le32(MASK32) } else { out.le32(comp.size.toLong()); out.le32(data.size.toLong()) }
            out.le16(nameBytes.size)
            out.le16(if (needs64) 20 else 0)   // extra len (zip64 = 4 hdr + 16 data)
            out.append(nameBytes)
            if (needs64) {
                out.le16(0x0001); out.le16(16)
                out.le64(data.size.toLong()); out.le64(comp.size.toLong())
            }
            out.append(comp)

            recs.add(Rec(nameBytes, method, crc, comp.size.toLong(), data.size.toLong(), offset, needs64))
        }

        val cdStart = out.size.toLong()
        for (r in recs) {
            out.le32(ZipFormat.CEN)
            out.le16(45)                       // version made by
            out.le16(if (r.zip64) 45 else 20)  // version needed
            out.le16(UTF8_FLAG)                // flags: UTF-8 name
            out.le16(r.method)
            out.le16(0); out.le16(DEFAULT_DOS_DATE) // midnight, 1980-01-01
            out.le32(r.crc)
            if (r.zip64) { out.le32(MASK32); out.le32(MASK32) } else { out.le32(r.comp); out.le32(r.uncomp) }
            out.le16(r.nameBytes.size)
            out.le16(if (r.zip64) 28 else 0)   // extra len (4 hdr + 24 data: uncomp, comp, offset)
            out.le16(0)                        // comment len
            out.le16(0)                        // disk start
            out.le16(0)                        // internal attrs
            out.le32(0)                        // external attrs
            if (r.zip64) out.le32(MASK32) else out.le32(r.offset)
            out.append(r.nameBytes)
            if (r.zip64) {
                out.le16(0x0001); out.le16(24)
                out.le64(r.uncomp); out.le64(r.comp); out.le64(r.offset)
            }
        }
        val cdSize = out.size.toLong() - cdStart
        val count = recs.size

        val needEocd64 = count > 0xFFFF || cdStart > MASK32 || cdSize > MASK32
        if (needEocd64) {
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
        out.le32(minOf(cdSize, MASK32)); out.le32(minOf(cdStart, MASK32))
        out.le16(0)                            // comment len
        return out.toByteArray()
    }
}
