package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * Immutable decompression policy. Create one with `ArchiveLimits { ... }`, or
 * configure a [Builder] and call [Builder.build]. [copy] configures a fresh builder
 * from this policy. Results never retain the mutable builder and can be shared.
 *
 * Output and ratio checks apply during each buffered decode/read, before output
 * growth. Separate calls do not share an archive-wide byte budget. Built-in readers
 * apply [maxEntries] while listing and use their policy as the default for each
 * read. Current buffered APIs do not recurse into nested archives or apply [maxNestingDepth].
 *
 * Every setting is nonnegative. Zero permits only zero output, a zero ratio,
 * zero entries or zero nesting depth respectively. [DEFAULT] preserves the default
 * values; [UNBOUNDED] raises every ceiling for trusted input.
 *
 * Public construction and copy signatures contain a builder action rather than
 * the settings themselves, so adding a setting does not replace those signatures.
 */
public class ArchiveLimits private constructor(builder: Builder) {
    /** Output ceiling for one decode/read call, in bytes. Default 2 GiB. */
    public val maxUncompressedBytes: Long = builder.maxUncompressedBytes

    /** Uncompressed/compressed ratio ceiling, checked during decode. Default 10000:1. */
    public val maxCompressionRatio: Int = builder.maxCompressionRatio

    /** Entry ceiling enforced while built-in readers parse their listings. Default 100k. */
    public val maxEntries: Int = builder.maxEntries

    /** Reserved nested-archive depth ceiling; buffered APIs do not enforce it. Default 8. */
    public val maxNestingDepth: Int = builder.maxNestingDepth

    init {
        require(maxUncompressedBytes >= 0) { "archive limits: maxUncompressedBytes must be nonnegative" }
        require(maxCompressionRatio >= 0) { "archive limits: maxCompressionRatio must be nonnegative" }
        require(maxEntries >= 0) { "archive limits: maxEntries must be nonnegative" }
        require(maxNestingDepth >= 0) { "archive limits: maxNestingDepth must be nonnegative" }
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

        /** See [ArchiveLimits.maxNestingDepth]. Must be nonnegative at build time. */
        public var maxNestingDepth: Int = 8

        internal constructor(source: ArchiveLimits) : this() {
            maxUncompressedBytes = source.maxUncompressedBytes
            maxCompressionRatio = source.maxCompressionRatio
            maxEntries = source.maxEntries
            maxNestingDepth = source.maxNestingDepth
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
    public fun copy(configure: Builder.() -> Unit = {}): ArchiveLimits =
        Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean = other is ArchiveLimits &&
        maxUncompressedBytes == other.maxUncompressedBytes && maxCompressionRatio == other.maxCompressionRatio &&
        maxEntries == other.maxEntries && maxNestingDepth == other.maxNestingDepth

    override fun hashCode(): Int {
        var result = maxUncompressedBytes.hashCode()
        result = 31 * result + maxCompressionRatio
        result = 31 * result + maxEntries
        return 31 * result + maxNestingDepth
    }

    override fun toString(): String = "ArchiveLimits(maxUncompressedBytes=$maxUncompressedBytes, " +
        "maxCompressionRatio=$maxCompressionRatio, maxEntries=$maxEntries, maxNestingDepth=$maxNestingDepth)"

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

    public companion object {
        /**
         * Build a policy from default settings, for example `ArchiveLimits { maxEntries = 5 }`.
         * @throws IllegalArgumentException if a configured setting is negative.
         */
        @Throws(IllegalArgumentException::class)
        public operator fun invoke(configure: Builder.() -> Unit = {}): ArchiveLimits =
            Builder().apply(configure).build()

        /** The safe default applied when a caller doesn't pass its own. */
        public val DEFAULT: ArchiveLimits = ArchiveLimits()

        /** Opt-out for trusted input: every guard relaxed to its maximum. */
        public val UNBOUNDED: ArchiveLimits = ArchiveLimits {
            maxUncompressedBytes = Long.MAX_VALUE
            maxCompressionRatio = Int.MAX_VALUE
            maxEntries = Int.MAX_VALUE
            maxNestingDepth = Int.MAX_VALUE
        }
    }
}

/** Thrown when input trips a decompression-bomb guard. */
public class DecompressionBombException : ArchiveException {
    public constructor(message: String) : super(message)
    public constructor(message: String, offset: Long?, entryName: String?, cause: Throwable?) :
        super(message, cause, offset, entryName)
}
