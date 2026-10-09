package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.codec.CodecOptions
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** How the DEFLATE encoder searches for matches, as zlib's strategies. */
public enum class DeflateStrategy {
    /** Normal LZ77 matching. */
    DEFAULT,

    /** For data such as filtered images: drop matches of five bytes or fewer, so short random runs stay literals. */
    FILTERED,

    /** No matches: Huffman-code literals only. */
    HUFFMAN_ONLY,

    /** Only matches at distance one, which suits run-length data such as PNG rows. */
    RLE,

    /** Never write dynamic Huffman blocks: fixed codes, or stored where smaller. */
    FIXED,
}

/**
 * DEFLATE encoder settings, used by [Deflate.encode], zlib and gzip. Create one with
 * `DeflateOptions { level = 9 }`. Defaults:
 *
 * - [level] 6: lazy matching with zlib's level-6 search depth.
 * - [strategy] [DeflateStrategy.DEFAULT].
 * - [windowBits] 15: a 32 KiB window.
 * - [dictionary] null.
 *
 * Levels follow zlib's table: 0 stores, 1 to 3 match greedily with a short search, and
 * 4 to 9 match lazily, searching deeper as the level rises. Instances are immutable.
 */
public class DeflateOptions private constructor(builder: Builder) : CodecOptions {
    /** 0 (store only) to 9 (smallest output). */
    public val level: Int = builder.level

    /** Match search strategy. */
    public val strategy: DeflateStrategy = builder.strategy

    /** Base-2 logarithm of the window, 9 to 15; matches reach at most 2^[windowBits] bytes back. */
    public val windowBits: Int = builder.windowBits

    private val storedDictionary: ByteArray? = builder.dictionary?.copyOf()

    /**
     * Preset dictionary that primes the window, so the first bytes can match it. Only zlib
     * can carry one (RFC 1950's FDICT); the decoder must supply the same bytes. Copied on access.
     */
    public val dictionary: ByteArray? get() = storedDictionary?.copyOf()

    internal val dictionaryBytes: ByteArray? get() = storedDictionary

    init {
        require(level in 0..9) { "DEFLATE level must be in 0..9, not $level" }
        require(windowBits in 9..15) { "DEFLATE windowBits must be in 9..15, not $windowBits" }
    }

    /** Mutable single-thread configuration; [build] takes an immutable snapshot. */
    public class Builder public constructor() {
        /** See [DeflateOptions.level]; initially 6. */
        public var level: Int = 6

        /** See [DeflateOptions.strategy]; initially [DeflateStrategy.DEFAULT]. */
        public var strategy: DeflateStrategy = DeflateStrategy.DEFAULT

        /** See [DeflateOptions.windowBits]; initially 15. */
        public var windowBits: Int = 15

        /** See [DeflateOptions.dictionary]; initially null. Copied by [build]. */
        public var dictionary: ByteArray? = null

        internal constructor(source: DeflateOptions) : this() {
            level = source.level
            strategy = source.strategy
            windowBits = source.windowBits
            dictionary = source.storedDictionary
        }

        /**
         * Snapshot the settings.
         * @throws IllegalArgumentException if [level] or [windowBits] is out of range.
         */
        @Throws(IllegalArgumentException::class)
        public fun build(): DeflateOptions = DeflateOptions(this)
    }

    /**
     * Copy these settings and apply [configure].
     * @throws IllegalArgumentException if a resulting setting is out of range.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): DeflateOptions = Builder(this).apply(configure).build()

    /** Compare every setting, dictionaries by content. */
    override fun equals(other: Any?): Boolean = other is DeflateOptions && level == other.level &&
        strategy == other.strategy && windowBits == other.windowBits &&
        (storedDictionary?.contentEquals(other.storedDictionary) ?: (other.storedDictionary == null))

    /** Hash every setting. */
    override fun hashCode(): Int =
        ((level * 31 + strategy.hashCode()) * 31 + windowBits) * 31 + (storedDictionary?.contentHashCode() ?: 0)

    /** Describe the settings, with the dictionary's size rather than its bytes. */
    override fun toString(): String =
        "DeflateOptions(level=$level, strategy=$strategy, windowBits=$windowBits, dictionary=${storedDictionary?.let { "${it.size} B" }})"

    /** Defaults and builder-based construction. */
    public companion object {
        /** Level 6, the default strategy, a 32 KiB window and no dictionary. */
        @JvmField public val DEFAULT: DeflateOptions = Builder().build()

        /**
         * Build options, for example `DeflateOptions { level = 1 }`.
         * @throws IllegalArgumentException if a setting is out of range.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): DeflateOptions = Builder().apply(configure).build()
    }
}
