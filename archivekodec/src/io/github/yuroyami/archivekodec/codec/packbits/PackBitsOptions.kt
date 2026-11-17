package io.github.yuroyami.archivekodec.codec.packbits

import io.github.yuroyami.archivekodec.codec.CodecOptions
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** What the length byte 128 means. Every other length byte means the same in both. */
public enum class PackBitsVariant {
    /** Apple PackBits, as TIFF compression 32773 uses it: 128 does nothing. */
    PACKBITS,

    /** PDF's `RunLengthDecode` filter (ISO 32000-1, 7.4.5): 128 ends the data. */
    PDF_RUN_LENGTH,
}

/**
 * PackBits settings for [PackBits]. The default is TIFF's PackBits with no expected size.
 * The encoder reads only [variant]; the other settings apply to decoding. Instances are immutable.
 */
public class PackBitsOptions private constructor(builder: Builder) : CodecOptions {
    /** Which meaning the length byte 128 has. */
    public val variant: PackBitsVariant = builder.variant

    /**
     * The number of bytes the data must decode to, or -1 when unknown. A decode stops once it has
     * this many bytes and ignores the rest, as TIFF readers do. Fewer bytes is damage.
     */
    public val expectedSize: Long = builder.expectedSize

    /** The output and ratio ceilings of a decode. */
    public val limits: ArchiveLimits = builder.limits

    /** Whether damaged input gives the bytes decoded before the damage instead of an exception. */
    public val keepPartialOutput: Boolean = builder.keepPartialOutput

    init {
        require(expectedSize >= -1) { "PackBits expected size must be -1 or more, not $expectedSize" }
    }

    /** Mutable single-thread configuration; [build] takes an immutable snapshot. */
    public class Builder public constructor() {
        /** See [PackBitsOptions.variant]; initially [PackBitsVariant.PACKBITS]. */
        public var variant: PackBitsVariant = PackBitsVariant.PACKBITS

        /** See [PackBitsOptions.expectedSize]; initially -1. */
        public var expectedSize: Long = -1

        /** See [PackBitsOptions.limits]; initially [ArchiveLimits.DEFAULT]. */
        public var limits: ArchiveLimits = ArchiveLimits.DEFAULT

        /** See [PackBitsOptions.keepPartialOutput]; initially false. */
        public var keepPartialOutput: Boolean = false

        internal constructor(source: PackBitsOptions) : this() {
            variant = source.variant
            expectedSize = source.expectedSize
            limits = source.limits
            keepPartialOutput = source.keepPartialOutput
        }

        /**
         * Snapshot the settings.
         * @throws IllegalArgumentException if [expectedSize] is below -1.
         */
        @Throws(IllegalArgumentException::class)
        public fun build(): PackBitsOptions = PackBitsOptions(this)
    }

    /**
     * Copy these settings and apply [configure].
     * @throws IllegalArgumentException if a setting is out of range.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): PackBitsOptions = Builder(this).apply(configure).build()

    /** Compare every setting. */
    override fun equals(other: Any?): Boolean = other is PackBitsOptions && variant == other.variant &&
        expectedSize == other.expectedSize && limits == other.limits && keepPartialOutput == other.keepPartialOutput

    /** Hash every setting. */
    override fun hashCode(): Int = listOf(variant, expectedSize, limits, keepPartialOutput).hashCode()

    /** Describe every setting. */
    override fun toString(): String = "PackBitsOptions(variant=$variant, expectedSize=$expectedSize, limits=$limits, " +
        "keepPartialOutput=$keepPartialOutput)"

    /** Presets and builder-based construction. */
    public companion object {
        /** TIFF's PackBits with no expected size. */
        @JvmField public val DEFAULT: PackBitsOptions = Builder().build()

        /** PDF's `RunLengthDecode`. */
        @JvmField public val PDF_RUN_LENGTH: PackBitsOptions = PackBitsOptions { variant = PackBitsVariant.PDF_RUN_LENGTH }

        /**
         * Build options, for example `PackBitsOptions { expectedSize = rowBytes * rows }`.
         * @throws IllegalArgumentException if a setting is out of range.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): PackBitsOptions = Builder().apply(configure).build()
    }
}
