package io.github.yuroyami.archivekodec.codec.lzw

import io.github.yuroyami.archivekodec.codec.CodecOptions
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** How LZW packs its variable-width codes into bytes. */
public enum class LzwBitOrder {
    /** Each code starts at the high bit of the next free bit, as PDF and TIFF write it. */
    MSB_FIRST,

    /** Each code starts at the low bit of the next free bit, as GIF and the old-style TIFF LZW write it. */
    LSB_FIRST,
}

/**
 * LZW settings for [Lzw]. The defaults are the LZW of PDF (`LZWDecode` with `EarlyChange` 1) and
 * of TIFF (compression 5): codes packed [LzwBitOrder.MSB_FIRST], 8-bit literals and early change.
 *
 * ```kotlin
 * Lzw.decode(stream, LzwOptions.DEFAULT)                    // PDF and TIFF
 * Lzw.decode(stream, LzwOptions { earlyChange = false })    // PDF with /EarlyChange 0
 * Lzw.decode(pixels, LzwOptions.gif(minimumCodeSize = 8).copy { expectedSize = width * height.toLong() })
 * ```
 *
 * Codes are 12 bits at most. Code `2^literalBits` clears the table and the next code ends the data.
 * The encoder reads [bitOrder], [literalBits] and [earlyChange]; the other settings apply to decoding.
 * Instances are immutable.
 */
public class LzwOptions private constructor(builder: Builder) : CodecOptions {
    /** How codes are packed into bytes. */
    public val bitOrder: LzwBitOrder = builder.bitOrder

    /** The bits of a literal: 8 for PDF and TIFF, the minimum code size (2 to 8) for GIF. */
    public val literalBits: Int = builder.literalBits

    /**
     * Whether the code width grows one code early, when the next free code plus one reaches a power
     * of two. PDF's `EarlyChange` 1 and TIFF need it; GIF and PDF's `EarlyChange` 0 do not.
     */
    public val earlyChange: Boolean = builder.earlyChange

    /**
     * The number of bytes the data must decode to, or -1 when unknown. A decode stops once it has
     * this many bytes and ignores the rest, as GIF and TIFF readers do. Fewer bytes is damage.
     */
    public val expectedSize: Long = builder.expectedSize

    /** The output and ratio ceilings of a decode. */
    public val limits: ArchiveLimits = builder.limits

    /** Whether damaged input gives the bytes decoded before the damage instead of an exception. */
    public val keepPartialOutput: Boolean = builder.keepPartialOutput

    init {
        require(literalBits in 2..8) { "LZW literal bits must be 2 to 8, not $literalBits" }
        require(expectedSize >= -1) { "LZW expected size must be -1 or more, not $expectedSize" }
    }

    /** Mutable single-thread configuration; [build] takes an immutable snapshot. */
    public class Builder public constructor() {
        /** See [LzwOptions.bitOrder]; initially [LzwBitOrder.MSB_FIRST]. */
        public var bitOrder: LzwBitOrder = LzwBitOrder.MSB_FIRST

        /** See [LzwOptions.literalBits]; initially 8. */
        public var literalBits: Int = 8

        /** See [LzwOptions.earlyChange]; initially true. */
        public var earlyChange: Boolean = true

        /** See [LzwOptions.expectedSize]; initially -1. */
        public var expectedSize: Long = -1

        /** See [LzwOptions.limits]; initially [ArchiveLimits.DEFAULT]. */
        public var limits: ArchiveLimits = ArchiveLimits.DEFAULT

        /** See [LzwOptions.keepPartialOutput]; initially false. */
        public var keepPartialOutput: Boolean = false

        internal constructor(source: LzwOptions) : this() {
            bitOrder = source.bitOrder
            literalBits = source.literalBits
            earlyChange = source.earlyChange
            expectedSize = source.expectedSize
            limits = source.limits
            keepPartialOutput = source.keepPartialOutput
        }

        /**
         * Snapshot the settings.
         * @throws IllegalArgumentException if [literalBits] is outside 2 to 8 or [expectedSize] is below -1.
         */
        @Throws(IllegalArgumentException::class)
        public fun build(): LzwOptions = LzwOptions(this)
    }

    /**
     * Copy these settings and apply [configure].
     * @throws IllegalArgumentException if a setting is out of range.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): LzwOptions = Builder(this).apply(configure).build()

    /** Compare every setting. */
    override fun equals(other: Any?): Boolean = other is LzwOptions && bitOrder == other.bitOrder &&
        literalBits == other.literalBits && earlyChange == other.earlyChange && expectedSize == other.expectedSize &&
        limits == other.limits && keepPartialOutput == other.keepPartialOutput

    /** Hash every setting. */
    override fun hashCode(): Int = listOf(bitOrder, literalBits, earlyChange, expectedSize, limits, keepPartialOutput).hashCode()

    /** Describe every setting. */
    override fun toString(): String = "LzwOptions(bitOrder=$bitOrder, literalBits=$literalBits, earlyChange=$earlyChange, " +
        "expectedSize=$expectedSize, limits=$limits, keepPartialOutput=$keepPartialOutput)"

    /** Presets and builder-based construction. */
    public companion object {
        /** PDF `LZWDecode` with `EarlyChange` 1, and TIFF compression 5. */
        @JvmField public val DEFAULT: LzwOptions = Builder().build()

        /** The old-style TIFF LZW that libtiff still reads: codes packed LSB first, with no early change. */
        @JvmField public val TIFF_OLD_STYLE: LzwOptions = LzwOptions {
            bitOrder = LzwBitOrder.LSB_FIRST
            earlyChange = false
        }

        /**
         * GIF's LZW for an image whose data starts with [minimumCodeSize].
         * @throws IllegalArgumentException if [minimumCodeSize] is outside 2 to 8.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        public fun gif(minimumCodeSize: Int): LzwOptions = LzwOptions {
            bitOrder = LzwBitOrder.LSB_FIRST
            literalBits = minimumCodeSize
            earlyChange = false
        }

        /**
         * Build options, for example `LzwOptions { earlyChange = false }`.
         * @throws IllegalArgumentException if a setting is out of range.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): LzwOptions = Builder().apply(configure).build()
    }
}
