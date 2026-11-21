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
     * Modification time, at the precision the archive stores, or null when it holds none. ZIP
     * prefers its NTFS and extended-timestamp fields and otherwise reads the zoneless DOS time
     * as UTC.
     */
    public val lastModified: ArchiveTime? = builder.lastModified

    /** [lastModified] in whole seconds since the Unix epoch, UTC, or null. */
    public val lastModifiedEpochSeconds: Long? get() = lastModified?.epochSeconds

    /** Last access time, or null when the archive stores none: pax `atime`, ZIP, 7z, RAR, WIM and ISO 9660 can. */
    public val lastAccessed: ArchiveTime? = builder.lastAccessed

    /**
     * Unix status-change time (`ctime`), or null when the archive stores none: pax `ctime` and
     * Rock Ridge can. It cannot be restored on extraction. Not the creation time; see [created].
     */
    public val statusChanged: ArchiveTime? = builder.statusChanged

    /** Creation (birth) time, or null when the archive stores none: ZIP's NTFS field, 7z, RAR and WIM can. */
    public val created: ArchiveTime? = builder.created

    /** Owner user id, or null when the archive stores none. */
    public val uid: Long? = builder.uid

    /** Owner group id, or null when the archive stores none. */
    public val gid: Long? = builder.gid

    /** Owner user name, as TAR stores it next to [uid], or null. */
    public val ownerName: String? = builder.ownerName

    /** Owner group name, as TAR stores it next to [gid], or null. */
    public val groupName: String? = builder.groupName

    /** Major number of a character or block device ([type]), or null for other members. */
    public val deviceMajor: Long? = builder.deviceMajor

    /** Minor number of a character or block device ([type]), or null for other members. */
    public val deviceMinor: Long? = builder.deviceMinor

    /**
     * Windows file attributes (`FILE_ATTRIBUTE_*`, the low bits shared with MS-DOS: read-only
     * 0x1, hidden 0x2, system 0x4, directory 0x10, archive 0x20, ...), or null when the
     * archive stores none or stores Unix metadata instead.
     */
    public val windowsAttributes: Int? = builder.windowsAttributes

    private val storedXattrs: Map<String, ByteArray> = builder.xattrs.mapValues { it.value.copyOf() }

    /**
     * Extended attributes by name, such as `user.comment` or `security.capability`, as the
     * archive stores them; empty when it stores none. POSIX ACLs appear here as the Linux
     * `system.posix_acl_access` and `system.posix_acl_default` attributes. A fresh copy on each
     * access. Extraction never applies them.
     */
    public val xattrs: Map<String, ByteArray> get() = storedXattrs.mapValues { it.value.copyOf() }

    /** The kind of member, from [isDirectory], [isSymbolicLink], [isHardLink] and the [unixMode] file type. */
    public val type: EntryType get() = when {
        isHardLink -> EntryType.HARD_LINK
        isSymbolicLink -> EntryType.SYMBOLIC_LINK
        isDirectory -> EntryType.DIRECTORY
        else -> when (unixMode?.and(0xF000)) {
            0x2000 -> EntryType.CHARACTER_DEVICE
            0x6000 -> EntryType.BLOCK_DEVICE
            0x1000 -> EntryType.FIFO
            0xC000 -> EntryType.SOCKET
            0x4000 -> EntryType.DIRECTORY
            else -> EntryType.FILE
        }
    }

    /** Encoded payload size in bytes; equals [size] for uncompressed members. */
    public val compressedSize: Long = builder.compressedSize

    /** Registered or known compression codec; unknown ZIP methods use `zip-method-N`, with their raw number in [zipMethod]. */
    public val codec: CodecId = builder.codec

    /**
     * Whether encryption is advertised. Built-in readers decrypt ZipCrypto, WinZip AES and
     * 7z AES given a password; see [encryption].
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
        /** Modification time, initially null. */
        public var lastModified: ArchiveTime? = null

        /** [lastModified] in whole seconds; setting it replaces [lastModified] with a whole-second time. */
        public var lastModifiedEpochSeconds: Long?
            get() = lastModified?.epochSeconds
            set(value) { lastModified = value?.let { ArchiveTime(it) } }

        /** See [ArchiveEntry.lastAccessed]; initially null. */
        public var lastAccessed: ArchiveTime? = null

        /** See [ArchiveEntry.statusChanged]; initially null. */
        public var statusChanged: ArchiveTime? = null

        /** See [ArchiveEntry.created]; initially null. */
        public var created: ArchiveTime? = null

        /** See [ArchiveEntry.uid]; initially null. */
        public var uid: Long? = null

        /** See [ArchiveEntry.gid]; initially null. */
        public var gid: Long? = null

        /** See [ArchiveEntry.ownerName]; initially null. */
        public var ownerName: String? = null

        /** See [ArchiveEntry.groupName]; initially null. */
        public var groupName: String? = null

        /** See [ArchiveEntry.deviceMajor]; initially null. */
        public var deviceMajor: Long? = null

        /** See [ArchiveEntry.deviceMinor]; initially null. */
        public var deviceMinor: Long? = null

        /** See [ArchiveEntry.windowsAttributes]; initially null. */
        public var windowsAttributes: Int? = null

        /** See [ArchiveEntry.xattrs]; initially empty. [build] copies the map and its values. */
        public var xattrs: Map<String, ByteArray> = emptyMap()

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
            lastModified = source.lastModified
            lastAccessed = source.lastAccessed
            statusChanged = source.statusChanged
            created = source.created
            uid = source.uid
            gid = source.gid
            ownerName = source.ownerName
            groupName = source.groupName
            deviceMajor = source.deviceMajor
            deviceMinor = source.deviceMinor
            windowsAttributes = source.windowsAttributes
            xattrs = source.storedXattrs
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
        lastModified == other.lastModified && lastAccessed == other.lastAccessed && statusChanged == other.statusChanged &&
        created == other.created && uid == other.uid && gid == other.gid && ownerName == other.ownerName &&
        groupName == other.groupName && deviceMajor == other.deviceMajor && deviceMinor == other.deviceMinor &&
        windowsAttributes == other.windowsAttributes && xattrsEqual(other) && compressedSize == other.compressedSize &&
        codec == other.codec && isEncrypted == other.isEncrypted && encryption == other.encryption && crc32 == other.crc32 &&
        unixMode == other.unixMode && comment == other.comment && isSymbolicLink == other.isSymbolicLink &&
        isHardLink == other.isHardLink && linkTarget == other.linkTarget

    /** Hash the metadata properties used by [equals]. */
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + isDirectory.hashCode()
        result = 31 * result + (lastModified?.hashCode() ?: 0)
        result = 31 * result + (lastAccessed?.hashCode() ?: 0)
        result = 31 * result + (statusChanged?.hashCode() ?: 0)
        result = 31 * result + (created?.hashCode() ?: 0)
        result = 31 * result + (uid?.hashCode() ?: 0)
        result = 31 * result + (gid?.hashCode() ?: 0)
        result = 31 * result + (ownerName?.hashCode() ?: 0)
        result = 31 * result + (groupName?.hashCode() ?: 0)
        result = 31 * result + (deviceMajor?.hashCode() ?: 0)
        result = 31 * result + (deviceMinor?.hashCode() ?: 0)
        result = 31 * result + (windowsAttributes ?: 0)
        result = 31 * result + storedXattrs.entries.sumOf { it.key.hashCode() xor it.value.contentHashCode() }
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

    private fun xattrsEqual(other: ArchiveEntry): Boolean = storedXattrs.size == other.storedXattrs.size &&
        storedXattrs.all { (key, value) -> other.storedXattrs[key]?.contentEquals(value) == true }

    /** Describe the member metadata with an escaped, bounded name; never reads its payload. */
    override fun toString(): String = "ArchiveEntry(name=\"${describeName(name)}\", size=$size, isDirectory=$isDirectory, " +
        "lastModified=$lastModified, compressedSize=$compressedSize, codec=$codec, " +
        "isEncrypted=$isEncrypted, encryption=$encryption, crc32=$crc32, unixMode=${unixMode?.toString(8)}, " +
        "comment=${comment?.let { "\"${describeName(it)}\"" }}, zipMethod=$zipMethod" +
        (if (isSymbolicLink || isHardLink) ", ${if (isHardLink) "hardLink" else "symbolicLink"}=${linkTarget?.let { "\"${describeName(it)}\"" }}" else "") +
        listOfNotNull(
            lastAccessed?.let { "lastAccessed=$it" }, statusChanged?.let { "statusChanged=$it" }, created?.let { "created=$it" },
            uid?.let { "uid=$it" }, gid?.let { "gid=$it" },
            ownerName?.let { "ownerName=\"${describeName(it)}\"" }, groupName?.let { "groupName=\"${describeName(it)}\"" },
            deviceMajor?.let { "device=$it:$deviceMinor" }, windowsAttributes?.let { "windowsAttributes=0x${it.toString(16)}" },
            storedXattrs.takeIf { it.isNotEmpty() }?.let { "xattrs=${it.size}" },
        ).joinToString("") { ", $it" } + ")"

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
