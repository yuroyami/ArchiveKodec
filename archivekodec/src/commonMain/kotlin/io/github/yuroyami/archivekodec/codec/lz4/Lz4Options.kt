package io.github.yuroyami.archivekodec.codec.lz4

import io.github.yuroyami.archivekodec.codec.CodecOptions
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** LZ4 frame block size: the most uncompressed bytes one block holds. */
public enum class Lz4BlockSize(
    /** The frame descriptor's block-size code (BD bits 4 to 6). */
    public val code: Int,
    /** Block size in bytes. */
    public val bytes: Int,
) {
    /** 64 KiB. */
    KB_64(4, 64 * 1024),

    /** 256 KiB. */
    KB_256(5, 256 * 1024),

    /** 1 MiB. */
    MB_1(6, 1024 * 1024),

    /** 4 MiB. */
    MB_4(7, 4 * 1024 * 1024),
}

/**
 * LZ4 frame settings for [Lz4.frameCompress]. Create one with
 * `Lz4Options { contentChecksum = true }`. Defaults reproduce the plain frame:
 *
 * - [blockSize] [Lz4BlockSize.MB_4].
 * - [blockChecksum] false: no xxHash32 after each block.
 * - [contentChecksum] false: no xxHash32 of the whole content.
 * - [contentSize] false: no 64-bit content size in the header.
 * - [linkedBlocks] false: each block is independent. Linked blocks may match into the
 *   previous 64 KiB of earlier blocks, which compresses small blocks better.
 *
 * Instances are immutable.
 */
public class Lz4Options private constructor(builder: Builder) : CodecOptions {
    /** Maximum block size. */
    public val blockSize: Lz4BlockSize = builder.blockSize

    /** Whether each block is followed by its xxHash32. */
    public val blockChecksum: Boolean = builder.blockChecksum

    /** Whether the frame ends with the xxHash32 of its content. */
    public val contentChecksum: Boolean = builder.contentChecksum

    /** Whether the header records the content size. */
    public val contentSize: Boolean = builder.contentSize

    /** Whether blocks may refer to data in the blocks before them. */
    public val linkedBlocks: Boolean = builder.linkedBlocks

    /** Mutable single-thread configuration; [build] takes an immutable snapshot. */
    public class Builder public constructor() {
        /** See [Lz4Options.blockSize]; initially [Lz4BlockSize.MB_4]. */
        public var blockSize: Lz4BlockSize = Lz4BlockSize.MB_4

        /** See [Lz4Options.blockChecksum]; initially false. */
        public var blockChecksum: Boolean = false

        /** See [Lz4Options.contentChecksum]; initially false. */
        public var contentChecksum: Boolean = false

        /** See [Lz4Options.contentSize]; initially false. */
        public var contentSize: Boolean = false

        /** See [Lz4Options.linkedBlocks]; initially false. */
        public var linkedBlocks: Boolean = false

        internal constructor(source: Lz4Options) : this() {
            blockSize = source.blockSize
            blockChecksum = source.blockChecksum
            contentChecksum = source.contentChecksum
            contentSize = source.contentSize
            linkedBlocks = source.linkedBlocks
        }

        /** Snapshot the settings. */
        public fun build(): Lz4Options = Lz4Options(this)
    }

    /** Copy these settings and apply [configure]. */
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): Lz4Options = Builder(this).apply(configure).build()

    /** Compare every setting. */
    override fun equals(other: Any?): Boolean = other is Lz4Options && blockSize == other.blockSize &&
        blockChecksum == other.blockChecksum && contentChecksum == other.contentChecksum &&
        contentSize == other.contentSize && linkedBlocks == other.linkedBlocks

    /** Hash every setting. */
    override fun hashCode(): Int = listOf(blockSize, blockChecksum, contentChecksum, contentSize, linkedBlocks).hashCode()

    /** Describe every setting. */
    override fun toString(): String = "Lz4Options(blockSize=$blockSize, blockChecksum=$blockChecksum, " +
        "contentChecksum=$contentChecksum, contentSize=$contentSize, linkedBlocks=$linkedBlocks)"

    /** Defaults and builder-based construction. */
    public companion object {
        /** 4 MiB independent blocks without checksums or content size. */
        @JvmField public val DEFAULT: Lz4Options = Builder().build()

        /** Build options, for example `Lz4Options { blockSize = Lz4BlockSize.KB_64 }`. */
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): Lz4Options = Builder().apply(configure).build()
    }
}
