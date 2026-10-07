package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.codec.CodecId

/**
 * One member of a container. [name] is the decoded stored name, including trailing
 * directory slashes. Use [isDirectory] to identify directories. Always run the name through
 * [io.github.yuroyami.archivekodec.security.PathGuard] before joining it to an output
 * directory. [dataOffset] is where the entry's bytes begin in the source (container
 * internal); -1 when not applicable. [nameBytes] preserves the stored filename
 * without format padding or terminators. It may differ from [name]'s UTF-8 encoding.
 *
 * This ordinary class retains the original constructor, copy and destructuring
 * API. Value equality uses the nine original metadata properties. Supplementary
 * stored bytes survive [copy] without expanding those public signatures.
 */
class ArchiveEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModifiedEpochSeconds: Long? = null,
    val compressedSize: Long = size,
    val codec: CodecId = CodecId.STORE,
    val isEncrypted: Boolean = false,
    val crc32: Long? = null,
    val dataOffset: Long = -1L,
) {
    private var storedNameBytes: ByteArray? = null

    /** Defensive copy of the stored name; null on manually constructed entries. */
    val nameBytes: ByteArray? get() = storedNameBytes?.copyOf()

    internal fun withNameBytes(bytes: ByteArray): ArchiveEntry {
        check(storedNameBytes == null)
        storedNameBytes = bytes.copyOf()
        return this
    }

    /** Copy the original value properties and preserve the immutable stored-name snapshot. */
    fun copy(
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
    }

    operator fun component1(): String = name
    operator fun component2(): Long = size
    operator fun component3(): Boolean = isDirectory
    operator fun component4(): Long? = lastModifiedEpochSeconds
    operator fun component5(): Long = compressedSize
    operator fun component6(): CodecId = codec
    operator fun component7(): Boolean = isEncrypted
    operator fun component8(): Long? = crc32
    operator fun component9(): Long = dataOffset

    override fun equals(other: Any?): Boolean = other is ArchiveEntry &&
        name == other.name && size == other.size && isDirectory == other.isDirectory &&
        lastModifiedEpochSeconds == other.lastModifiedEpochSeconds && compressedSize == other.compressedSize &&
        codec == other.codec && isEncrypted == other.isEncrypted && crc32 == other.crc32 && dataOffset == other.dataOffset

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

    override fun toString(): String = "ArchiveEntry(name=$name, size=$size, isDirectory=$isDirectory, " +
        "lastModifiedEpochSeconds=$lastModifiedEpochSeconds, compressedSize=$compressedSize, codec=$codec, " +
        "isEncrypted=$isEncrypted, crc32=$crc32, dataOffset=$dataOffset)"
}
