package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.jvm.JvmSynthetic

/**
 * One member of a container. [name] is the decoded stored name, including trailing
 * directory slashes. Use [isDirectory] to identify directories. Always run the name through
 * [io.github.yuroyami.archivekodec.security.PathGuard] before joining it to an output
 * directory. [nameBytes] preserves the stored filename without format padding or
 * terminators. It may differ from [name]'s UTF-8 encoding.
 *
 * Built-in readers only read entries they listed themselves, or unchanged [copy]
 * results of them. An entry built with the public constructor, an entry from another
 * reader, or a copy with changed metadata is rejected with
 * [io.github.yuroyami.archivekodec.ArchiveFormatException] naming the entry. The
 * public constructor exists for custom [ArchiveReader] implementations, which locate
 * their own payloads. Value equality uses the eight metadata properties. Supplementary
 * stored bytes, [zipMethod] and the issuing reader survive [copy] without expanding
 * those public signatures.
 */
public class ArchiveEntry(
    /** Decoded stored filename, including a directory slash when present; not a sanitized filesystem path. */
    public val name: String,
    /** Uncompressed member size in bytes; directory entries normally report zero. */
    public val size: Long,
    /** Whether the container identifies this member as a directory. */
    public val isDirectory: Boolean,
    /** Modification time in seconds since the Unix epoch, or null when the reader does not expose it. */
    public val lastModifiedEpochSeconds: Long? = null,
    /** Encoded payload size in bytes; equals [size] for uncompressed members. */
    public val compressedSize: Long = size,
    /** Registered or known compression codec; unknown ZIP methods use `zip-method-N`, with their raw number in [zipMethod]. */
    public val codec: CodecId = CodecId.STORE,
    /** Whether encryption is advertised; built-in readers cannot decrypt encrypted payloads. */
    public val isEncrypted: Boolean = false,
    /** Unsigned CRC-32 in a Long, or null when no CRC-32 is exposed by the reader. */
    public val crc32: Long? = null,
) {
    private var storedNameBytes: ByteArray? = null
    private var storedZipMethod: Int? = null
    private var issuer: Any? = null
    private var slot: Int = -1

    /**
     * Container-internal source position: a ZIP local-header offset, otherwise the
     * payload offset; -1 on entries no built-in reader located.
     */
    @get:JvmSynthetic
    internal var dataOffset: Long = -1L
        private set

    /** Defensive copy of the stored name; null on manually constructed entries. */
    public val nameBytes: ByteArray? get() = storedNameBytes?.copyOf()

    /**
     * ZIP central-directory compression method in `0..65535`, or null for other
     * containers and manually constructed entries. Preserves the wire number even
     * when a custom codec is registered. Method 99 remains the AES marker, not its
     * underlying compression method. Decoder availability belongs to the reader's
     * [io.github.yuroyami.archivekodec.codec.CodecRegistry.byZipMethod].
     */
    public val zipMethod: Int? get() = storedZipMethod

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
     * Copy the metadata properties and preserve stored names, the raw ZIP method and the
     * issuing reader. A built-in reader still reads an unchanged copy, but rejects a
     * copy whose metadata differs from its listing.
     */
    public fun copy(
        name: String = this.name,
        size: Long = this.size,
        isDirectory: Boolean = this.isDirectory,
        lastModifiedEpochSeconds: Long? = this.lastModifiedEpochSeconds,
        compressedSize: Long = this.compressedSize,
        codec: CodecId = this.codec,
        isEncrypted: Boolean = this.isEncrypted,
        crc32: Long? = this.crc32,
    ): ArchiveEntry = ArchiveEntry(name, size, isDirectory, lastModifiedEpochSeconds,
        compressedSize, codec, isEncrypted, crc32).also {
        it.storedNameBytes = storedNameBytes
        it.storedZipMethod = storedZipMethod
        it.issuer = issuer
        it.slot = slot
        it.dataOffset = dataOffset
    }

    /** Return [name] for positional destructuring. */
    public operator fun component1(): String = name
    /** Return [size] for positional destructuring. */
    public operator fun component2(): Long = size
    /** Return [isDirectory] for positional destructuring. */
    public operator fun component3(): Boolean = isDirectory
    /** Return [lastModifiedEpochSeconds] for positional destructuring. */
    public operator fun component4(): Long? = lastModifiedEpochSeconds
    /** Return [compressedSize] for positional destructuring. */
    public operator fun component5(): Long = compressedSize
    /** Return [codec] for positional destructuring. */
    public operator fun component6(): CodecId = codec
    /** Return [isEncrypted] for positional destructuring. */
    public operator fun component7(): Boolean = isEncrypted
    /** Return [crc32] for positional destructuring. */
    public operator fun component8(): Long? = crc32

    /** Compare the eight metadata properties; stored names, ZIP methods and the issuing reader are not part of equality. */
    override fun equals(other: Any?): Boolean = other is ArchiveEntry &&
        name == other.name && size == other.size && isDirectory == other.isDirectory &&
        lastModifiedEpochSeconds == other.lastModifiedEpochSeconds && compressedSize == other.compressedSize &&
        codec == other.codec && isEncrypted == other.isEncrypted && crc32 == other.crc32

    /** Hash the eight metadata properties used by [equals]. */
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + isDirectory.hashCode()
        result = 31 * result + (lastModifiedEpochSeconds?.hashCode() ?: 0)
        result = 31 * result + compressedSize.hashCode()
        result = 31 * result + codec.hashCode()
        result = 31 * result + isEncrypted.hashCode()
        return 31 * result + (crc32?.hashCode() ?: 0)
    }

    /** Describe the member metadata without reading its payload. */
    override fun toString(): String = "ArchiveEntry(name=$name, size=$size, isDirectory=$isDirectory, " +
        "lastModifiedEpochSeconds=$lastModifiedEpochSeconds, compressedSize=$compressedSize, codec=$codec, " +
        "isEncrypted=$isEncrypted, crc32=$crc32, zipMethod=$zipMethod)"
}
