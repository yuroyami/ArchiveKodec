package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.crypto.ArchiveEncryption
import io.github.yuroyami.archivekodec.util.describeName
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/**
 * One member of a container. [name] is the decoded stored name, including trailing
 * directory slashes. Use [isDirectory] to identify directories. Built-in readers refuse
 * unsafe names by default (see
 * [io.github.yuroyami.archivekodec.security.ArchiveLimits.rejectUnsafeNames]) but keep the
 * stored spelling: join [io.github.yuroyami.archivekodec.security.PathGuard.sanitize]'s
 * result, not the raw name, to an output directory. [nameBytes] preserves the stored filename without format padding or
 * terminators. It may differ from [name]'s UTF-8 encoding.
 *
 * Built-in readers only read entries they listed themselves, or unchanged [copy]
 * results of them. An entry built with a public constructor or builder, an entry from
 * another reader, or a copy with changed metadata is rejected with
 * [io.github.yuroyami.archivekodec.ArchiveFormatException] naming the entry. Public
 * construction exists for custom [ArchiveReader] implementations, which locate their
 * own payloads.
 *
 * The direct constructor has a fixed three-argument signature. Other metadata is set
 * through a [Builder], the `ArchiveEntry(name, size, isDirectory) { ... }` factory or
 * [copy], so adding metadata never replaces a public construction or copy signature.
 * Value equality uses the public metadata properties. Stored name bytes, [zipMethod]
 * and the issuing reader survive [copy] but are not part of equality.
 */
public class ArchiveEntry private constructor(builder: Builder) {
    /** Decoded stored filename, including a directory slash when present; not a sanitized filesystem path. */
    public val name: String = builder.name

    /** Uncompressed member size in bytes; directory entries normally report zero. */
    public val size: Long = builder.size

    /** Whether the container identifies this member as a directory. */
    public val isDirectory: Boolean = builder.isDirectory

    /**
     * Modification time in seconds since the Unix epoch, UTC, or null when the archive holds
     * none. ZIP prefers its extended-timestamp field and otherwise reads the zoneless DOS time
     * as UTC; pax times round down to whole seconds.
     */
    public val lastModifiedEpochSeconds: Long? = builder.lastModifiedEpochSeconds

    /** Encoded payload size in bytes; equals [size] for uncompressed members. */
    public val compressedSize: Long = builder.compressedSize

    /** Registered or known compression codec; unknown ZIP methods use `zip-method-N`, with their raw number in [zipMethod]. */
    public val codec: CodecId = builder.codec

    /**
     * Whether encryption is advertised. Built-in readers decrypt ZipCrypto, WinZip AES and
     * 7z AES given a password and, for AES, a crypto provider; see [encryption].
     */
    public val isEncrypted: Boolean = builder.isEncrypted

    /**
     * The encryption scheme protecting this member, or null when it is not encrypted or the
     * reader does not know. [ArchiveEncryption.ZIP_CRYPTO_INSECURE] marks broken legacy ZIP
     * encryption; treat such members as unprotected.
     */
    public val encryption: ArchiveEncryption? = builder.encryption

    /** Unsigned CRC-32 in a Long, or null when no CRC-32 is exposed by the reader. */
    public val crc32: Long? = builder.crc32

    /**
     * Unix `st_mode`, including the file-type bits (for example `0o100755` for an executable
     * file), or null when the archive stores none. ZIP exposes it only for entries made on
     * Unix hosts. TAR and ar types come from the member type when the stored mode omits them.
     */
    public val unixMode: Int? = builder.unixMode

    /** Per-entry comment, or null when the archive stores none. Only ZIP has entry comments. */
    public val comment: String? = builder.comment

    /**
     * Whether this member is a symbolic link (or a Windows junction): set by the reader, or
     * implied by a [unixMode] of type `0o120000`. Its target is [linkTarget] when the reader
     * knows it from the metadata; otherwise reading the member returns the target text. Do not
     * create links from untrusted archives without resolving the target first, for example
     * with [io.github.yuroyami.archivekodec.security.PathGuard.resolveLink].
     */
    public val isSymbolicLink: Boolean = builder.isSymbolicLink || (builder.unixMode?.and(0xF000) == 0xA000)

    /**
     * Whether this member is a hard link to the earlier member named [linkTarget], whose
     * data reading it returns. Only TAR and RAR5 record hard links this way; cpio and
     * SquashFS hard links are listed as ordinary members sharing data.
     */
    public val isHardLink: Boolean = builder.isHardLink

    /**
     * The stored target of a symbolic link, or the member name a hard link points to; null
     * otherwise, or when a symbolic link keeps its target in its data (ZIP, cpio, 7z, RAR4).
     * The text is as stored: relative to the link's directory, possibly absolute or with `..`.
     */
    public val linkTarget: String? = builder.linkTarget

    private var storedNameBytes: ByteArray? = builder.origin?.storedNameBytes
    private var storedZipMethod: Int? = builder.origin?.storedZipMethod
    private var issuer: Any? = builder.origin?.issuer
    private var slot: Int = builder.origin?.slot ?: -1

    /**
     * Container-internal source position: a ZIP local-header offset, otherwise the
     * payload offset; -1 on entries no built-in reader located.
     */
    @get:JvmSynthetic
    internal var dataOffset: Long = builder.origin?.dataOffset ?: -1L
        private set

    /**
     * Describe a member with default metadata: no timestamp, [compressedSize] equal to
     * [size], [CodecId.STORE], unencrypted and no CRC-32.
     * @throws IllegalArgumentException reserved for validated metadata.
     */
    @Throws(IllegalArgumentException::class)
    public constructor(name: String, size: Long, isDirectory: Boolean) : this(Builder(name, size, isDirectory))

    /** Defensive copy of the stored name; null on entries no built-in reader listed. */
    public val nameBytes: ByteArray? get() = storedNameBytes?.copyOf()

    /**
     * ZIP central-directory compression method in `0..65535`, or null for other
     * containers and manually constructed entries. Preserves the wire number even
     * when a custom codec is registered. Method 99 remains the AES marker, not its
     * underlying compression method. Decoder availability belongs to the reader's
     * [io.github.yuroyami.archivekodec.codec.CodecRegistry.byZipMethod].
     */
    public val zipMethod: Int? get() = storedZipMethod

    /**
     * Mutable single-thread configuration. [build] takes an independent snapshot, so
     * retaining or reusing a builder never changes a built entry. Future metadata is
     * added as body properties.
     */
    public class Builder public constructor(
        /** Decoded stored filename. */
        public var name: String,
        /** Uncompressed member size in bytes. */
        public var size: Long,
        /** Whether the member is a directory. */
        public var isDirectory: Boolean,
    ) {
        /** Modification time in Unix epoch seconds, initially null. */
        public var lastModifiedEpochSeconds: Long? = null

        /** Encoded payload size, initially the constructor's [size]. */
        public var compressedSize: Long = size

        /** Compression codec, initially [CodecId.STORE]. */
        public var codec: CodecId = CodecId.STORE

        /** Whether encryption is advertised, initially false. */
        public var isEncrypted: Boolean = false

        /** Encryption scheme, initially null. */
        public var encryption: ArchiveEncryption? = null

        /** Unsigned CRC-32, initially null. */
        public var crc32: Long? = null

        /** Unix `st_mode` including file-type bits, initially null. */
        public var unixMode: Int? = null

        /** Entry comment, initially null. */
        public var comment: String? = null

        /** Whether the member is a symbolic link, initially false; a symlink [unixMode] also implies it. */
        public var isSymbolicLink: Boolean = false

        /** Whether the member is a hard link to [linkTarget], initially false. */
        public var isHardLink: Boolean = false

        /** Link target, initially null. */
        public var linkTarget: String? = null

        internal var origin: ArchiveEntry? = null
            private set

        internal constructor(source: ArchiveEntry) : this(source.name, source.size, source.isDirectory) {
            lastModifiedEpochSeconds = source.lastModifiedEpochSeconds
            compressedSize = source.compressedSize
            codec = source.codec
            isEncrypted = source.isEncrypted
            encryption = source.encryption
            crc32 = source.crc32
            unixMode = source.unixMode
            comment = source.comment
            isSymbolicLink = source.isSymbolicLink
            isHardLink = source.isHardLink
            linkTarget = source.linkTarget
            origin = source
        }

        /**
         * Snapshot the current metadata.
         * @throws IllegalArgumentException reserved for validated metadata.
         */
        @Throws(IllegalArgumentException::class)
        public fun build(): ArchiveEntry = ArchiveEntry(this)
    }

    internal fun withNameBytes(bytes: ByteArray): ArchiveEntry {
        check(storedNameBytes == null)
        storedNameBytes = bytes.copyOf()
        return this
    }

    internal fun withZipMethod(method: Int): ArchiveEntry {
        check(storedZipMethod == null && method in 0..65535)
        storedZipMethod = method
        return this
    }

    /** Record where a built-in reader found this member; only called while parsing. */
    internal fun locatedAt(offset: Long): ArchiveEntry {
        dataOffset = offset
        return this
    }

    /** Mark this listed entry as [owner]'s member at [index] of its listing. */
    internal fun issue(owner: Any, index: Int) {
        check(issuer == null)
        issuer = owner
        slot = index
    }

    /** Listing index when [owner] issued this entry or its copy, otherwise -1. */
    internal fun slotIn(owner: Any): Int = if (issuer === owner) slot else -1

    /**
     * Configure an independent builder from this entry, then snapshot it. Stored names,
     * the raw ZIP method and the issuing reader are preserved. A built-in reader still
     * reads an unchanged copy, but rejects a copy whose metadata differs from its listing.
     * @throws IllegalArgumentException if [configure] throws an argument error.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): ArchiveEntry = Builder(this).apply(configure).build()

    /** Compare the metadata properties; stored names, ZIP methods and the issuing reader are not part of equality. */
    override fun equals(other: Any?): Boolean = this === other || other is ArchiveEntry &&
        name == other.name && size == other.size && isDirectory == other.isDirectory &&
        lastModifiedEpochSeconds == other.lastModifiedEpochSeconds && compressedSize == other.compressedSize &&
        codec == other.codec && isEncrypted == other.isEncrypted && encryption == other.encryption && crc32 == other.crc32 &&
        unixMode == other.unixMode && comment == other.comment && isSymbolicLink == other.isSymbolicLink &&
        isHardLink == other.isHardLink && linkTarget == other.linkTarget

    /** Hash the metadata properties used by [equals]. */
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + isDirectory.hashCode()
        result = 31 * result + (lastModifiedEpochSeconds?.hashCode() ?: 0)
        result = 31 * result + compressedSize.hashCode()
        result = 31 * result + codec.hashCode()
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + (encryption?.hashCode() ?: 0)
        result = 31 * result + (crc32?.hashCode() ?: 0)
        result = 31 * result + (unixMode ?: 0)
        result = 31 * result + (comment?.hashCode() ?: 0)
        result = 31 * result + isSymbolicLink.hashCode()
        result = 31 * result + isHardLink.hashCode()
        return 31 * result + (linkTarget?.hashCode() ?: 0)
    }

    /** Describe the member metadata with an escaped, bounded name; never reads its payload. */
    override fun toString(): String = "ArchiveEntry(name=\"${describeName(name)}\", size=$size, isDirectory=$isDirectory, " +
        "lastModifiedEpochSeconds=$lastModifiedEpochSeconds, compressedSize=$compressedSize, codec=$codec, " +
        "isEncrypted=$isEncrypted, encryption=$encryption, crc32=$crc32, unixMode=${unixMode?.toString(8)}, " +
        "comment=${comment?.let { "\"${describeName(it)}\"" }}, zipMethod=$zipMethod" +
        (if (isSymbolicLink || isHardLink) ", ${if (isHardLink) "hardLink" else "symbolicLink"}=${linkTarget?.let { "\"${describeName(it)}\"" }}" else "") + ")"

    /** Builder-based construction. */
    public companion object {
        /**
         * Configure metadata beyond the three required properties, for example
         * `ArchiveEntry("a.txt", 3, false) { crc32 = 0x352441C2 }`.
         * @throws IllegalArgumentException if [configure] throws an argument error.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        public operator fun invoke(
            name: String,
            size: Long,
            isDirectory: Boolean,
            configure: Builder.() -> Unit,
        ): ArchiveEntry = Builder(name, size, isDirectory).apply(configure).build()
    }
}
