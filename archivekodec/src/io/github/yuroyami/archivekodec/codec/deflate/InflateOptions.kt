package io.github.yuroyami.archivekodec.codec.deflate

import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * Called before each DEFLATE block of a stream is decoded. Use it to report progress or to
 * stop a long decode.
 */
public fun interface InflateBlockListener {
    /**
     * [inputConsumed] counts the bytes of the input range read so far, a zlib header included.
     * [outputProduced] counts the bytes decoded so far. To stop the decode, throw: the
     * decoder passes the exception on unchanged, whatever its type.
     */
    public fun onBlock(inputConsumed: Int, outputProduced: Long)
}

/**
 * Settings for [Inflate.inflate] and
 * [Zlib.decompress][io.github.yuroyami.archivekodec.wrapper.Zlib.decompress] calls that
 * return an [InflateResult]. Build with the DSL, for example
 * `InflateOptions { allowTrailingData = true }`. Defaults:
 *
 * - [limits] [ArchiveLimits.DEFAULT].
 * - [dictionary] null.
 * - [sizeHint] 0: the output size is unknown.
 * - [allowTrailingData] false: bytes after the stream are an error.
 * - [verifyChecksum] true: zlib's Adler-32 must be present and correct.
 * - [keepPartialOutput] false: a damaged stream throws.
 * - [onBlock] null.
 * - [usePlatformInflater] false: the pure-Kotlin decoder only.
 * - [stopAfterBytes] -1: decode the whole stream.
 *
 * The defaults decode exactly as the overloads without options do. Instances are immutable.
 */
public class InflateOptions private constructor(builder: Builder) {
    /** Output and ratio ceilings. They always throw, also with [keepPartialOutput]. */
    public val limits: ArchiveLimits = builder.limits

    private val storedDictionary: ByteArray? = builder.dictionary?.copyOf()

    /**
     * Preset dictionary that the stream may refer back to, as
     * [DeflateOptions.dictionary] primes the encoder. It is not part of the output and does
     * not count against [limits]. A zlib stream names its dictionary (FDICT); the Adler-32 of
     * these bytes must match that name. Copied on access.
     */
    public val dictionary: ByteArray? get() = storedDictionary?.copyOf()
    internal val dictionaryBytes: ByteArray? get() = storedDictionary

    /**
     * The output size the caller expects, in bytes, or 0 when unknown. It only guides buffer
     * growth: an output of exactly this size is decoded without a final copy. It is not a
     * limit and a different output size is not an error.
     */
    public val sizeHint: Long = builder.sizeHint

    /**
     * When true, bytes after the end of the stream are ignored and
     * [InflateResult.inputConsumed] tells where the stream ended. When false, they are an
     * [io.github.yuroyami.archivekodec.ArchiveFormatException].
     */
    public val allowTrailingData: Boolean = builder.allowTrailingData

    /**
     * zlib only. When false, the Adler-32 trailer is not read: it may be wrong, cut short or
     * missing. Up to four bytes after the last block count as the trailer; more are trailing
     * data. Raw DEFLATE has no checksum and ignores this setting.
     */
    public val verifyChecksum: Boolean = builder.verifyChecksum

    /**
     * When true, a damaged or cut-short stream does not throw. The result holds the output
     * decoded before the damage and [InflateResult.error] holds the failure. This covers
     * [InflateException] and [io.github.yuroyami.archivekodec.ArchiveFormatException];
     * [limits], a missing dictionary and an output too large for one array still throw.
     */
    public val keepPartialOutput: Boolean = builder.keepPartialOutput

    /** Called before each DEFLATE block; see [InflateBlockListener]. */
    public val onBlock: InflateBlockListener? = builder.onBlock

    /**
     * When true, the JVM and Android decode with `java.util.zip.Inflater` first, which runs the
     * platform's native zlib and is several times faster. Other platforms ignore this setting.
     *
     * The result is the same as without it. zlib and the pure decoder give the same bytes for every
     * valid stream. Damage, a limit, trailing data, a [dictionary] or an [onBlock] listener send the
     * call to the pure decoder, which then gives its usual result or error.
     */
    public val usePlatformInflater: Boolean = builder.usePlatformInflater

    /**
     * When 0 or more, the decode stops once it has this many output bytes and returns them, for
     * example to read a file header without the rest of the stream. [InflateResult.isStopped] is
     * then true, and the zlib checksum and trailing data are not checked. -1 decodes the whole stream.
     */
    public val stopAfterBytes: Long = builder.stopAfterBytes

    init {
        require(sizeHint >= 0) { "sizeHint must not be negative, not $sizeHint" }
        require(stopAfterBytes >= -1) { "stopAfterBytes must be -1 or more, not $stopAfterBytes" }
    }

    /** Mutable single-thread configuration; [build] takes an immutable snapshot. */
    public class Builder public constructor() {
        /** See [InflateOptions.limits]; initially [ArchiveLimits.DEFAULT]. */
        public var limits: ArchiveLimits = ArchiveLimits.DEFAULT
        /** See [InflateOptions.dictionary]; initially null. Copied by [build]. */
        public var dictionary: ByteArray? = null
        /** See [InflateOptions.sizeHint]; initially 0. */
        public var sizeHint: Long = 0
        /** See [InflateOptions.allowTrailingData]; initially false. */
        public var allowTrailingData: Boolean = false
        /** See [InflateOptions.verifyChecksum]; initially true. */
        public var verifyChecksum: Boolean = true
        /** See [InflateOptions.keepPartialOutput]; initially false. */
        public var keepPartialOutput: Boolean = false
        /** See [InflateOptions.onBlock]; initially null. */
        public var onBlock: InflateBlockListener? = null
        /** See [InflateOptions.usePlatformInflater]; initially false. */
        public var usePlatformInflater: Boolean = false
        /** See [InflateOptions.stopAfterBytes]; initially -1. */
        public var stopAfterBytes: Long = -1

        internal constructor(source: InflateOptions) : this() {
            limits = source.limits
            dictionary = source.storedDictionary
            sizeHint = source.sizeHint
            allowTrailingData = source.allowTrailingData
            verifyChecksum = source.verifyChecksum
            keepPartialOutput = source.keepPartialOutput
            onBlock = source.onBlock
            usePlatformInflater = source.usePlatformInflater
            stopAfterBytes = source.stopAfterBytes
        }

        /**
         * Snapshot the settings.
         * @throws IllegalArgumentException if [sizeHint] is negative or [stopAfterBytes] is below -1.
         */
        @Throws(IllegalArgumentException::class)
        public fun build(): InflateOptions = InflateOptions(this)
    }

    /**
     * Copy these settings and apply [configure].
     * @throws IllegalArgumentException if a resulting setting is out of range.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): InflateOptions = Builder(this).apply(configure).build()

    /** Compare every setting, dictionaries by content. */
    override fun equals(other: Any?): Boolean = other is InflateOptions && limits == other.limits &&
        sizeHint == other.sizeHint && allowTrailingData == other.allowTrailingData &&
        verifyChecksum == other.verifyChecksum && keepPartialOutput == other.keepPartialOutput && onBlock == other.onBlock &&
        usePlatformInflater == other.usePlatformInflater && stopAfterBytes == other.stopAfterBytes &&
        (storedDictionary?.contentEquals(other.storedDictionary) ?: (other.storedDictionary == null))

    /** Hash every setting. */
    override fun hashCode(): Int {
        var result = limits.hashCode()
        result = result * 31 + sizeHint.hashCode()
        result = result * 31 + allowTrailingData.hashCode()
        result = result * 31 + verifyChecksum.hashCode()
        result = result * 31 + keepPartialOutput.hashCode()
        result = result * 31 + (onBlock?.hashCode() ?: 0)
        result = result * 31 + usePlatformInflater.hashCode()
        result = result * 31 + stopAfterBytes.hashCode()
        return result * 31 + (storedDictionary?.contentHashCode() ?: 0)
    }

    /** Describe the settings, with the dictionary's size rather than its bytes. */
    override fun toString(): String =
        "InflateOptions(limits=$limits, dictionary=${storedDictionary?.let { "${it.size} B" }}, sizeHint=$sizeHint, " +
            "allowTrailingData=$allowTrailingData, verifyChecksum=$verifyChecksum, keepPartialOutput=$keepPartialOutput, " +
            "onBlock=${if (onBlock == null) "null" else "set"}, usePlatformInflater=$usePlatformInflater, stopAfterBytes=$stopAfterBytes)"

    /** Defaults and builder-based construction. */
    public companion object {
        /** The strict defaults: [ArchiveLimits.DEFAULT], no dictionary, no hint, no leniency. */
        @JvmField public val DEFAULT: InflateOptions = Builder().build()

        /**
         * Build options, for example `InflateOptions { keepPartialOutput = true }`.
         * @throws IllegalArgumentException if a setting is out of range.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): InflateOptions = Builder().apply(configure).build()
    }
}
