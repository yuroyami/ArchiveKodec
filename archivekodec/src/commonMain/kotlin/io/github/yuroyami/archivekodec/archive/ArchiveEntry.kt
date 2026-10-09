package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.codec.CodecId

/**
 * One member of a container. [name] is the decoded stored name, including trailing
 * directory slashes. Use [isDirectory] to identify directories. Always run the name through
 * [io.github.yuroyami.archivekodec.security.PathGuard] before joining it to an output
 * directory. [dataOffset] is a format-specific reader position, not a portable
 * payload offset; -1 when not applicable. [nameBytes] preserves the stored filename
 * without format padding or terminators. It may differ from [name]'s UTF-8 encoding.
 *
 * This ordinary class retains the original constructor, copy and destructuring
 * API. Value equality uses the nine original metadata properties. Supplementary
 * stored bytes and [zipMethod] survive [copy] without expanding those public signatures.
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
    /** Format-specific source position: a ZIP local-header offset, otherwise a payload offset; -1 if unavailable. Use [ArchiveReader.read]. */
    public val dataOffset: Long = -1L,
) {
    private var storedNameBytes: ByteArray? = null
    private var storedZipMethod: Int? = null

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

    /** Copy the original value properties and preserve stored names and the raw ZIP method. */
    public fun copy(
        name: String = this.name,
        size: Long = this.size,
        isDirectory: Boolean = this.isDirectory,
        lastModifiedEpochSeconds: Long? = this.lastModifiedEpochSeconds,
        compressedSize: Long = this.compressedSize,
        codec: CodecId = this.codec,
        isEncrypted: Boolean = this.isEncrypted,
        crc32: Long? = this.crc32,
        dataOffset: Long = this.dataOffset,
    ): ArchiveEntry = ArchiveEntry(name, size, isDirectory, lastModifiedEpochSeconds,
        compressedSize, codec, isEncrypted, crc32, dataOffset).also {
        it.storedNameBytes = storedNameBytes
        it.storedZipMethod = storedZipMethod
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
    /** Return [dataOffset] for positional destructuring. */
    public operator fun component9(): Long = dataOffset

    /** Compare the nine original metadata properties; supplementary stored names and ZIP methods are not part of equality. */
    override fun equals(other: Any?): Boolean = other is ArchiveEntry &&
        name == other.name && size == other.size && isDirectory == other.isDirectory &&
        lastModifiedEpochSeconds == other.lastModifiedEpochSeconds && compressedSize == other.compressedSize &&
        codec == other.codec && isEncrypted == other.isEncrypted && crc32 == other.crc32 && dataOffset == other.dataOffset

    /** Hash the nine metadata properties used by [equals]. */
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + isDirectory.hashCode()
        result = 31 * result + (lastModifiedEpochSeconds?.hashCode() ?: 0)
        result = 31 * result + compressedSize.hashCode()
        result = 31 * result + codec.hashCode()
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + (crc32?.hashCode() ?: 0)
        return 31 * result + dataOffset.hashCode()
    }

    /** Describe the member metadata without reading its payload. */
    override fun toString(): String = "ArchiveEntry(name=$name, size=$size, isDirectory=$isDirectory, " +
        "lastModifiedEpochSeconds=$lastModifiedEpochSeconds, compressedSize=$compressedSize, codec=$codec, " +
        "isEncrypted=$isEncrypted, crc32=$crc32, dataOffset=$dataOffset, zipMethod=$zipMethod)"
}
