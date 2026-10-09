package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmSynthetic

/**
 * Immutable codec and ZIP-method bindings. Start with [DEFAULT], then use [with]
 * to add or replace codecs without changing another reader or the global defaults:
 * `val codecs = CodecRegistry.DEFAULT.with(custom, zipMethod = 93)`.
 * Pass the result to [io.github.yuroyami.archivekodec.archive.zip.ZipReader].
 *
 * Bindings are immutable snapshots, but codec instances are shared, not copied.
 * Custom implementations must keep their [Codec.id] stable and document their
 * own thread-safety. They must decode the payload framing used by the registered
 * ZIP method and enforce [io.github.yuroyami.archivekodec.security.ArchiveLimits]
 * while producing output. Registration does not add a sniffer or an encoder to
 * [io.github.yuroyami.archivekodec.archive.zip.ZipWriter].
 */
public class CodecRegistry private constructor(
    private val codecs: Map<String, Codec>,
    private val zipMethods: Map<Int, CodecId>,
) {
    /** Independent snapshot of registered identifiers, in insertion order. */
    public val implemented: List<CodecId> get() = codecs.keys.map { CodecId(it) }

    /** Find a registered implementation by value identity, or null if unavailable. */
    public fun byId(id: CodecId): Codec? = codecs[id.name]

    /** Case-insensitive ASCII codec-name lookup; unknown or invalid names return null. */
    public fun byName(name: String): Codec? =
        if (name.any { it.code > 127 }) null else codecs[name.lowercase()]

    /**
     * Resolve a ZIP wire-method number through this registry, or null when no
     * implementation is registered. Numbers outside `0..65535` return null.
     */
    public fun byZipMethod(method: Int): Codec? = zipMethods[method]?.let { byId(it) }

    /**
     * Codec for a recognized compressed-stream [format], or null for containers,
     * unknown formats and unavailable implementations. SNAPPY means framed Snappy.
     */
    public fun byFormat(format: ArchiveFormat): Codec? = when (format) {
        ArchiveFormat.GZIP -> byId(CodecId.GZIP)
        ArchiveFormat.ZLIB -> byId(CodecId.ZLIB)
        ArchiveFormat.LZ4 -> byId(CodecId.LZ4)
        ArchiveFormat.SNAPPY -> byId(CodecId.SNAPPY_FRAMED)
        ArchiveFormat.BZIP2 -> byId(CodecId.BZIP2)
        ArchiveFormat.XZ -> byId(CodecId.XZ)
        ArchiveFormat.LZMA -> byId(CodecId.LZMA)
        ArchiveFormat.ZSTD -> byId(CodecId.ZSTD)
        ArchiveFormat.BROTLI -> byId(CodecId.BROTLI)
        else -> null
    }

    /**
     * Return a new registry containing [codec], replacing any implementation with
     * the same id. Existing ZIP bindings for that id use the replacement, including
     * known methods whose engines were previously unavailable. An explicit
     * [zipMethod] adds or replaces that number's binding; other bindings remain.
     * This registry and [DEFAULT] stay unchanged. The codec instance is shared.
     *
     * @throws IllegalArgumentException if [zipMethod] is outside `0..65535`.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun with(codec: Codec, zipMethod: Int? = null): CodecRegistry {
        require(zipMethod == null || zipMethod in 0..65535) { "ZIP method must be in 0..65535" }
        val id = codec.id
        return CodecRegistry(codecs + (id.name to codec),
            if (zipMethod == null) zipMethods else zipMethods + (zipMethod to id))
    }

    @JvmSynthetic
    internal fun idForZipMethod(method: Int): CodecId? = zipMethods[method]

    /** Built-in defaults; deriving a registry never mutates these bindings. */
    public companion object {
        /**
         * Built-in engines and known ZIP wire mappings. STORE (0) and DEFLATE (8)
         * are available. BZIP2 (12), LZMA (14), ZSTD (93) and PPMD (98) are known
         * mappings without built-in engines; register their ids to enable them.
         */
        @JvmField
        public val DEFAULT: CodecRegistry = CodecRegistry(
            listOf(Codecs.STORE, Codecs.DEFLATE, Codecs.GZIP, Codecs.ZLIB,
                Codecs.LZ4, Codecs.SNAPPY, Codecs.SNAPPY_FRAMED).associateBy { it.id.name },
            mapOf(0 to CodecId.STORE, 8 to CodecId.DEFLATE, 12 to CodecId.BZIP2,
                14 to CodecId.LZMA, 93 to CodecId.ZSTD, 98 to CodecId.PPMD),
        )
    }
}
