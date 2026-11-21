package io.github.yuroyami.archivekodec.codec.bzip2

import io.github.yuroyami.archivekodec.codec.CodecOptions
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * bzip2 encoder settings for [io.github.yuroyami.archivekodec.codec.Codecs.BZIP2]. Create one
 * with `Bzip2Options { level = 1 }`. [level] is the block size in units of 100 kB, as
 * `bzip2 -1` to `bzip2 -9` set it: a smaller block needs less memory to write and to read,
 * and usually compresses a little worse. The default is 9. Instances are immutable.
 *
 * @throws IllegalArgumentException from [Builder.build] if [level] is not 1 to 9.
 */
public class Bzip2Options private constructor(builder: Builder) : CodecOptions {
    /** Block size level, 1 to 9: blocks of 100 kB to 900 kB. */
    public val level: Int = builder.level

    init {
        require(level in 1..9) { "bzip2 level must be 1 to 9, not $level" }
    }

    /** Mutable single-thread configuration; [build] takes an immutable snapshot. */
    public class Builder public constructor() {
        /** See [Bzip2Options.level]; initially 9. */
        public var level: Int = 9

        internal constructor(source: Bzip2Options) : this() {
            level = source.level
        }

        /**
         * Snapshot the settings.
         * @throws IllegalArgumentException if [level] is not 1 to 9.
         */
        @Throws(IllegalArgumentException::class)
        public fun build(): Bzip2Options = Bzip2Options(this)
    }

    /** Copy these settings and apply [configure]. */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): Bzip2Options = Builder(this).apply(configure).build()

    /** Compare the level. */
    override fun equals(other: Any?): Boolean = other is Bzip2Options && level == other.level

    /** Hash the level. */
    override fun hashCode(): Int = level

    /** Describe the level. */
    override fun toString(): String = "Bzip2Options(level=$level)"

    /** Defaults and builder-based construction. */
    public companion object {
        /** Level 9: 900 kB blocks, as `bzip2` writes by default. */
        @JvmField public val DEFAULT: Bzip2Options = Builder().build()

        /** Build options, for example `Bzip2Options { level = 1 }`. */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): Bzip2Options = Builder().apply(configure).build()
    }
}
