package io.github.yuroyami.archivekodec.wrapper

import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

import kotlin.jvm.JvmSynthetic

/**
 * Immutable RFC 1952 member-header metadata. Create with `GzipHeader { ... }`
 * or [Builder], and use [copy] to configure an independent snapshot.
 * The reproducible defaults are MTIME 0 (no timestamp), OS 255 (unknown), XFL 0,
 * no optional fields, and cleared text/header-CRC flags.
 *
 * Names and comments are ISO 8859-1 text without NUL. A null value omits its
 * field; an empty value emits an empty field. Extra bytes are owned snapshots.
 * Names are preserved exactly; callers writing a member should supply the
 * original basename prescribed by RFC 1952. No filesystem path is sanitized.
 */
public class GzipHeader private constructor(builder: Builder) {
    /** Original name (FNAME), or null when absent. */
    public val name: String? = builder.name
    /** Human-readable comment (FCOMMENT), or null when absent. */
    public val comment: String? = builder.comment
    /** Unsigned 32-bit Unix seconds (MTIME); zero means unavailable and is the default. */
    public val mtimeEpochSeconds: Long = builder.mtimeEpochSeconds
    /** Unsigned OS byte; default 255 means unknown. */
    public val os: Int = builder.os
    /** Informational XFL byte, default zero. Does not select an encoder level or strategy. */
    public val extraFlags: Int = builder.extraFlags
    /** FTEXT hint that the payload is text; no conversion is performed. Default false. */
    public val isText: Boolean = builder.isText
    /** Whether FHCRC is present; [Gzip.compress] calculates it and [Gzip.readHeader] verifies it. */
    public val hasHeaderCrc: Boolean = builder.hasHeaderCrc

    init {
        require(name == null || name.all { it.code in 1..255 }) { "gzip header: name must be Latin-1 without NUL" }
        require(comment == null || comment.all { it.code in 1..255 }) { "gzip header: comment must be Latin-1 without NUL" }
        require(mtimeEpochSeconds in 0..0xffffffffL) { "gzip header: mtimeEpochSeconds must fit unsigned 32 bits" }
        require(os in 0..255) { "gzip header: os must fit one unsigned byte" }
        require(extraFlags in 0..255) { "gzip header: extraFlags must fit one unsigned byte" }
        require((builder.extra?.size ?: 0) <= 65535) { "gzip header: extra cannot exceed 65535 bytes" }
    }

    private val extraContent: ByteArray? = builder.extra?.copyOf()

    /** Independent copy of the raw FEXTRA bytes, without XLEN, or null when absent. */
    public val extra: ByteArray? get() = extraContent?.copyOf()

    @get:JvmSynthetic
    internal val storedExtra: ByteArray? get() = extraContent

    /** Mutable single-thread settings. [build] validates and snapshots all fields. */
    public class Builder public constructor() {
        /** See [GzipHeader.name]. */
        public var name: String? = null
        /** See [GzipHeader.comment]. */
        public var comment: String? = null
        /** See [GzipHeader.mtimeEpochSeconds]. */
        public var mtimeEpochSeconds: Long = 0
        /** See [GzipHeader.os]. */
        public var os: Int = 255
        /** See [GzipHeader.extraFlags]. */
        public var extraFlags: Int = 0
        /** See [GzipHeader.isText]. */
        public var isText: Boolean = false
        /** See [GzipHeader.hasHeaderCrc]. */
        public var hasHeaderCrc: Boolean = false
        /** Raw extra bytes (at most 65535), copied by [build]; null omits FEXTRA. */
        public var extra: ByteArray? = null

        internal constructor(source: GzipHeader) : this() {
            name = source.name
            comment = source.comment
            mtimeEpochSeconds = source.mtimeEpochSeconds
            os = source.os
            extraFlags = source.extraFlags
            isText = source.isText
            hasHeaderCrc = source.hasHeaderCrc
            extra = source.extraContent?.copyOf()
        }

        /**
         * Take an independent immutable snapshot.
         * @throws IllegalArgumentException for NUL/non-Latin-1 text, out-of-range numbers or oversized extra bytes.
         */
        @Throws(IllegalArgumentException::class)
        public fun build(): GzipHeader = GzipHeader(this)
    }

    /**
     * Configure a copy with independently mutable extra bytes.
     * @throws IllegalArgumentException if the resulting settings fail [Builder.build] validation.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): GzipHeader = Builder(this).apply(configure).build()

    /** Compare every field, including extra byte contents and absent versus empty optional fields. */
    override fun equals(other: Any?): Boolean = this === other || other is GzipHeader &&
        name == other.name && comment == other.comment && mtimeEpochSeconds == other.mtimeEpochSeconds &&
        os == other.os && extraFlags == other.extraFlags && isText == other.isText && hasHeaderCrc == other.hasHeaderCrc &&
        (if (extraContent == null) other.extraContent == null else other.extraContent != null && extraContent.contentEquals(other.extraContent))

    /** Hash the same metadata and extra contents compared by [equals]. */
    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + (comment?.hashCode() ?: 0)
        result = 31 * result + mtimeEpochSeconds.hashCode()
        result = 31 * result + os
        result = 31 * result + extraFlags
        result = 31 * result + isText.hashCode()
        result = 31 * result + hasHeaderCrc.hashCode()
        return 31 * result + (extraContent?.contentHashCode() ?: 0)
    }

    /** Bounded description without dumping names, comments or extra bytes. */
    override fun toString(): String = "GzipHeader(nameLength=${name?.length}, commentLength=${comment?.length}, " +
        "mtimeEpochSeconds=$mtimeEpochSeconds, os=$os, extraFlags=$extraFlags, isText=$isText, " +
        "hasHeaderCrc=$hasHeaderCrc, extraBytes=${extraContent?.size})"

    /** Default metadata and builder-based construction. */
    public companion object {
        /**
         * Configure an immutable header from reproducible defaults.
         * @throws IllegalArgumentException if settings fail [Builder.build] validation.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): GzipHeader = Builder().apply(configure).build()

        /** MTIME 0, OS 255, XFL 0, no optional fields, text hint or header CRC. */
        @JvmField
        public val DEFAULT: GzipHeader = GzipHeader()
    }
}
