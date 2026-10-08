package io.github.yuroyami.archivekodec.archive.ar

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.checkedArraySize
import kotlin.jvm.JvmSynthetic
import kotlin.text.CharacterCodingException

/**
 * Buffered GNU ar writer for ordinary `.a` and `.deb` member containers.
 * Writes 60-byte ASCII headers, even-byte padding and a GNU `//` long-name table.
 * Names are UTF-8 basenames: no empty/dot names, slashes, backslashes, colons,
 * ASCII controls or reserved symbol-index names. Accepted spelling is preserved.
 * The complete archive must fit in one ByteArray.
 *
 * Metadata defaults are reproducible: Unix time zero, uid/gid zero and mode 0100644.
 * No symbol index is generated; use a platform's `ranlib` when a linker needs one.
 * Debian member ordering and the contents of control/data archives are the caller's
 * responsibility. BSD inline names and thin archives are not emitted.
 * This object has no mutable state. File descriptions own their payload snapshots.
 */
public object ArWriter {
    /** Immutable member settings and an owned payload snapshot. */
    public class FileSpec private constructor(builder: Builder) {
        /** Exact UTF-8 basename, validated by [write]. */
        public val name: String = builder.name
        /** Independent payload copy; changing it cannot modify this description. */
        public val data: ByteArray get() = content.copyOf()
        /** Seconds since the Unix epoch, represented by a 12-character decimal field. */
        public val modificationTime: Long = builder.modificationTime
        /** Numeric owner ID, represented by a six-character decimal field. */
        public val uid: Int = builder.uid
        /** Numeric group ID, represented by a six-character decimal field. */
        public val gid: Int = builder.gid
        /** Unix mode bits, represented by an eight-character octal field. */
        public val mode: Int = builder.mode

        init {
            require(modificationTime in 0..999_999_999_999L) { "ar: modificationTime must fit 12 decimal digits" }
            require(uid in 0..999_999 && gid in 0..999_999) { "ar: uid and gid must fit six decimal digits" }
            require(mode in 0..0xFFFFFF) { "ar: mode must fit eight octal digits" }
        }

        private val content: ByteArray = builder.data.copyOf()

        @get:JvmSynthetic
        internal val payload: ByteArray get() = content

        /** Snapshot [data] with reproducible default metadata. */
        @Throws(IllegalArgumentException::class)
        public constructor(name: String, data: ByteArray) : this(Builder(name, data))

        /** Mutable single-thread settings; [build] takes an independent snapshot. */
        public class Builder public constructor(
            /** Exact member basename; [write] validates its spelling. */
            public var name: String,
            /** Mutable bytes copied by [build]. */
            public var data: ByteArray,
        ) {
            /** Unix timestamp in seconds, from zero through 999999999999; default zero. */
            public var modificationTime: Long = 0
            /** Owner ID, from zero through 999999; default zero. */
            public var uid: Int = 0
            /** Group ID, from zero through 999999; default zero. */
            public var gid: Int = 0
            /** Mode bits, from zero through octal 77777777; default octal 0100644. */
            public var mode: Int = 0x81A4

            internal constructor(source: FileSpec) : this(source.name, source.content.copyOf()) {
                modificationTime = source.modificationTime
                uid = source.uid
                gid = source.gid
                mode = source.mode
            }

            /**
             * Snapshot settings and payload.
             * @throws IllegalArgumentException if numeric metadata cannot fit its header field.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): FileSpec = FileSpec(this)
        }

        /**
         * Configure an independent copy, including independently mutable payload bytes.
         * @throws IllegalArgumentException if numeric metadata cannot fit its header field.
         */
        @Throws(IllegalArgumentException::class)
        public fun copy(configure: Builder.() -> Unit = {}): FileSpec = Builder(this).apply(configure).build()

        /** Compare names, numeric metadata and payload contents. */
        override fun equals(other: Any?): Boolean = this === other || other is FileSpec &&
            name == other.name && modificationTime == other.modificationTime && uid == other.uid &&
            gid == other.gid && mode == other.mode && content.contentEquals(other.content)

        /** Hash names, numeric metadata and payload contents. */
        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + modificationTime.hashCode()
            result = 31 * result + uid
            result = 31 * result + gid
            result = 31 * result + mode
            return 31 * result + content.contentHashCode()
        }

        /** Bounded description of sizes and metadata, without dumping names or payloads. */
        override fun toString(): String = "FileSpec(nameLength=${name.length}, bytes=${content.size}, " +
            "modificationTime=$modificationTime, uid=$uid, gid=$gid, mode=${mode.toString(8)})"

        /** Construct member settings with a builder action. */
        public companion object {
            /**
             * Configure metadata before taking a snapshot, for example `FileSpec("x", bytes) { uid = 1000 }`.
             * @throws IllegalArgumentException if numeric metadata cannot fit its header field.
             */
            @Throws(IllegalArgumentException::class)
            public operator fun invoke(name: String, data: ByteArray, configure: Builder.() -> Unit): FileSpec =
                Builder(name, data).apply(configure).build()
        }
    }

    private class Prepared(val spec: FileSpec, val field: ByteArray)

    /**
     * Assemble members in the supplied order, preserving duplicate names.
     * Validates every name and the complete size before allocating archive storage.
     * Empty input produces the eight-byte ar magic alone. No compression is applied.
     *
     * @throws ArchiveFormatException for an invalid or reserved basename or malformed Unicode.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the archive cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun write(files: List<FileSpec>): ByteArray {
        var total = 8L + 60L * files.size
        checkedArraySize(total) // Reject impossible synthetic lists before reading their elements.
        val prepared = ArrayList<Prepared>()
        val tableNames = LinkedHashMap<String, Pair<Int, ByteArray>>()
        var tableSize = 0
        for (spec in files) {
            val name = encodeName(spec.name)
            val field = if (name.size <= 15 && ' ' !in spec.name && spec.name != "#1") {
                name + byteArrayOf(47)
            } else {
                val entry = tableNames[spec.name] ?: (tableSize to name).also {
                    tableSize = checkedArraySize(tableSize.toLong() + name.size + 2)
                    tableNames[spec.name] = it
                }
                "/${entry.first}".encodeToByteArray()
            }
            total += spec.payload.size.toLong() + (spec.payload.size and 1)
            checkedArraySize(total + if (tableSize > 0) 60L + tableSize + (tableSize and 1) else 0)
            prepared.add(Prepared(spec, field))
        }
        if (tableSize > 0) total += 60L + tableSize + (tableSize and 1)
        val out = ByteArrayBuilder(checkedArraySize(total))
        out.append("!<arch>\n".encodeToByteArray())
        if (tableSize > 0) {
            out.append(header("//".encodeToByteArray(), tableSize, 0, 0, 0, 0))
            for ((_, entry) in tableNames) {
                out.append(entry.second)
                out.append(47)
                out.append(10)
            }
            if (tableSize and 1 != 0) out.append(10)
        }
        for (file in prepared) {
            val spec = file.spec
            val field = file.field
            out.append(header(field, spec.payload.size, spec.modificationTime, spec.uid, spec.gid, spec.mode))
            out.append(spec.payload)
            if (spec.payload.size and 1 != 0) out.append(10)
        }
        return out.takeByteArray()
    }

    private fun encodeName(name: String): ByteArray {
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) {
            throw ArchiveFormatException("ar: expected a nonempty basename without paths or ASCII controls", entryName = name)
        }
        if (name in setOf("__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED")) {
            throw ArchiveFormatException("ar: reserved symbol-index name", entryName = name)
        }
        return try {
            name.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw ArchiveFormatException("ar: filename is not valid Unicode", entryName = name, cause = error)
        }
    }

    private fun header(name: ByteArray, size: Int, time: Long, uid: Int, gid: Int, mode: Int): ByteArray {
        val bytes = ByteArray(60) { 32 }
        name.copyInto(bytes)
        time.toString().encodeToByteArray().copyInto(bytes, 16)
        uid.toString().encodeToByteArray().copyInto(bytes, 28)
        gid.toString().encodeToByteArray().copyInto(bytes, 34)
        mode.toString(8).encodeToByteArray().copyInto(bytes, 40)
        size.toString().encodeToByteArray().copyInto(bytes, 48)
        bytes[58] = 96
        bytes[59] = 10
        return bytes
    }
}
