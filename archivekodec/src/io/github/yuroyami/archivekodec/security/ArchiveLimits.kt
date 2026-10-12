package io.github.yuroyami.archivekodec.security

import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * Immutable decompression policy. Create one with `ArchiveLimits { ... }`, or
 * configure a [Builder] and call [Builder.build]. [copy] configures a fresh builder
 * from this policy. Results never retain the mutable builder and can be shared.
 *
 * Output and ratio checks apply during each buffered decode/read, before output
 * growth. These limits are per reader (listing) and per call (output, ratio): separate
 * calls do not add up. Pass an [ExtractionBudget] to a reader's read to cap the total
 * output of the reads that share it. Built-in readers apply [maxEntries] while listing
 * and use their policy as the default for each read. Initramfs listings also apply [maxSegments]. No API opens archives
 * found inside other archives, so there is no nesting-depth setting.
 *
 * [rejectUnsafeNames] makes built-in readers run [PathGuard] on every listed name.
 *
 * Every numeric setting is nonnegative. Zero permits only zero output, a zero ratio,
 * zero entries, zero segments or no up-front decoder memory respectively. [DEFAULT] preserves the default
 * values; [UNBOUNDED] raises every ceiling for trusted input.
 *
 * Public construction and copy signatures contain a builder action rather than
 * the settings themselves, so adding a setting does not replace those signatures.
 */
public class ArchiveLimits private constructor(builder: Builder) {
    /** Output ceiling for one decode/read call, in bytes. Default 2 GiB. */
    public val maxUncompressedBytes: Long = builder.maxUncompressedBytes

    /**
     * Uncompressed/compressed ratio ceiling, checked during decode. Default 10000:1.
     * This default cannot reject a valid stream from any currently implemented codec:
     * DEFLATE (including gzip/zlib) stays below 1032:1, LZ4 below 255:1, Snappy
     * (raw or framed) below 64:3, and STORE is 1:1. It can still reject impossible
     * declared sizes before decoding. The high default preserves highly compressible
     * legitimate input; choose a lower value when ratio-based rejection is required.
     *
     * [maxUncompressedBytes] is the effective default output bound and applies to one
     * decode/read call. Bound cumulative output across reads with an [ExtractionBudget].
     */
    public val maxCompressionRatio: Int = builder.maxCompressionRatio

    /** Entry ceiling enforced while built-in readers parse their listings. Default 100k. */
    public val maxEntries: Int = builder.maxEntries

    /** Initramfs segment ceiling, including trailer-only empty segments. Default 100k. Other readers do not use it. */
    public val maxSegments: Int = builder.maxSegments

    /**
     * Ceiling on memory a decoder allocates up front because the stream's header asks for
     * it, in bytes. PPMd sizes its whole model this way; it is checked before allocating.
     * Default 256 MiB, which covers every ZIP PPMd stream and 7-Zip's settings up to -mx9.
     */
    public val maxDecoderMemory: Long = builder.maxDecoderMemory

    /**
     * Whether built-in readers reject a listing that contains a name [PathGuard.sanitize]
     * refuses: empty, absolute, drive-letter, `..` traversal or control-character names.
     * Directory entries naming the root, such as `./`, are allowed. Listed names keep
     * their stored spelling; run [PathGuard.sanitize] for the normalized path. Default true.
     */
    public val rejectUnsafeNames: Boolean = builder.rejectUnsafeNames

    init {
        require(maxUncompressedBytes >= 0) { "archive limits: maxUncompressedBytes must be nonnegative" }
        require(maxCompressionRatio >= 0) { "archive limits: maxCompressionRatio must be nonnegative" }
        require(maxEntries >= 0) { "archive limits: maxEntries must be nonnegative" }
        require(maxSegments >= 0) { "archive limits: maxSegments must be nonnegative" }
        require(maxDecoderMemory >= 0) { "archive limits: maxDecoderMemory must be nonnegative" }
    }

    /**
     * Mutable configuration for a single thread. Settings start at the same values
     * as [DEFAULT]. Validation is deferred until [build]; a failed build does not
     * consume the builder. Each successful build takes an independent snapshot.
     *
     * The zero-argument constructor is also usable from Java and Swift.
     */
    public class Builder public constructor() {
        /** See [ArchiveLimits.maxUncompressedBytes]. Must be nonnegative at build time. */
        public var maxUncompressedBytes: Long = 2L * 1024 * 1024 * 1024

        /** See [ArchiveLimits.maxCompressionRatio]. Must be nonnegative at build time. */
        public var maxCompressionRatio: Int = 10_000

        /** See [ArchiveLimits.maxEntries]. Must be nonnegative at build time. */
        public var maxEntries: Int = 100_000

        /** See [ArchiveLimits.maxSegments]. Must be nonnegative at build time. */
        public var maxSegments: Int = 100_000

        /** See [ArchiveLimits.maxDecoderMemory]. Must be nonnegative at build time. */
        public var maxDecoderMemory: Long = 256L * 1024 * 1024

        /** See [ArchiveLimits.rejectUnsafeNames]. */
        public var rejectUnsafeNames: Boolean = true

        internal constructor(source: ArchiveLimits) : this() {
            maxUncompressedBytes = source.maxUncompressedBytes
            maxCompressionRatio = source.maxCompressionRatio
            maxEntries = source.maxEntries
            maxSegments = source.maxSegments
            maxDecoderMemory = source.maxDecoderMemory
            rejectUnsafeNames = source.rejectUnsafeNames
        }

        /**
         * Snapshot the current settings into an immutable policy.
         * @throws IllegalArgumentException if any setting is negative; the message names it.
         */
        @Throws(IllegalArgumentException::class)
        public fun build(): ArchiveLimits = ArchiveLimits(this)
    }

    /**
     * Copy this policy and apply [configure] to a fresh builder before validation.
     * Omitting the action creates an equal independent policy.
     * @throws IllegalArgumentException if a resulting setting is negative.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): ArchiveLimits =
        Builder(this).apply(configure).build()

    /** Compare all configured policy ceilings. */
    override fun equals(other: Any?): Boolean = other is ArchiveLimits &&
        maxUncompressedBytes == other.maxUncompressedBytes && maxCompressionRatio == other.maxCompressionRatio &&
        maxEntries == other.maxEntries && maxSegments == other.maxSegments &&
        maxDecoderMemory == other.maxDecoderMemory && rejectUnsafeNames == other.rejectUnsafeNames

    /** Hash the policy ceilings used by [equals]. */
    override fun hashCode(): Int {
        var result = maxUncompressedBytes.hashCode()
        result = 31 * result + maxCompressionRatio
        result = 31 * result + maxEntries
        result = 31 * result + maxSegments
        result = 31 * result + maxDecoderMemory.hashCode()
        return 31 * result + rejectUnsafeNames.hashCode()
    }

    /** Describe each configured policy ceiling. */
    override fun toString(): String = "ArchiveLimits(maxUncompressedBytes=$maxUncompressedBytes, " +
        "maxCompressionRatio=$maxCompressionRatio, maxEntries=$maxEntries, maxSegments=$maxSegments, " +
        "maxDecoderMemory=$maxDecoderMemory, rejectUnsafeNames=$rejectUnsafeNames)"

    /** @throws DecompressionBombException if [bytes] exceeds [maxUncompressedBytes]. */
    @Throws(ArchiveException::class)
    public fun checkOutputSize(bytes: Long) {
        if (bytes > maxUncompressedBytes) {
            throw DecompressionBombException(
                "uncompressed output $bytes B exceeds limit $maxUncompressedBytes B",
            )
        }
    }

    /** @throws DecompressionBombException if the ratio exceeds [maxCompressionRatio]. */
    @Throws(ArchiveException::class)
    public fun checkRatio(compressedBytes: Long, uncompressedBytes: Long) {
        if (compressedBytes <= 0) return
        val quotient = uncompressedBytes / compressedBytes
        val remainder = uncompressedBytes % compressedBytes
        if (quotient > maxCompressionRatio || (quotient == maxCompressionRatio.toLong() && remainder > 0)) {
            throw DecompressionBombException(
                "compression ratio $uncompressedBytes/$compressedBytes exceeds ${maxCompressionRatio}:1",
            )
        }
    }

    /** @throws DecompressionBombException if an entry count exceeds [maxEntries]. */
    @Throws(ArchiveException::class)
    public fun checkEntryCount(count: Int) {
        if (count > maxEntries) {
            throw DecompressionBombException("entry count $count exceeds limit $maxEntries")
        }
    }

    /** Default policies and builder-based policy construction. */
    public companion object {
        /**
         * Build a policy from default settings, for example `ArchiveLimits { maxEntries = 5 }`.
         * @throws IllegalArgumentException if a configured setting is negative.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): ArchiveLimits =
            Builder().apply(configure).build()

        /**
         * Policy applied when a caller does not supply one. Bounds each call's output
         * and each reader's entry count, plus initramfs segments, and rejects unsafe
         * entry names; its ratio ceiling cannot reject valid streams
         * from the current codecs. See [maxCompressionRatio] before relying on it.
         */
        @JvmField
        public val DEFAULT: ArchiveLimits = ArchiveLimits()

        /** Opt-out for trusted input: every guard relaxed to its maximum, and names unchecked. */
        @JvmField
        public val UNBOUNDED: ArchiveLimits = ArchiveLimits {
            maxUncompressedBytes = Long.MAX_VALUE
            maxCompressionRatio = Int.MAX_VALUE
            maxEntries = Int.MAX_VALUE
            maxSegments = Int.MAX_VALUE
            maxDecoderMemory = Long.MAX_VALUE
            rejectUnsafeNames = false
        }
    }
}

/** Thrown when input trips a decompression-bomb guard. */
public class DecompressionBombException : ArchiveException {
    public constructor(message: String) : super(message)
    public constructor(message: String, offset: Long?, entryName: String?, cause: Throwable?) :
        super(message, cause, offset, entryName)
}
