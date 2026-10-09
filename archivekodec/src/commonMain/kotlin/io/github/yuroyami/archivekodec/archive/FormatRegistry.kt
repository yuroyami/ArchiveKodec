package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.sevenzip.SevenZipReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import kotlin.jvm.JvmField

/**
 * Immutable ordered format descriptors. Start with [DEFAULT] and derive a new
 * registry with [with]. New or replaced descriptors run first; other descriptors
 * retain their order. This makes a detector at an arbitrary offset able to override
 * a short built-in signature. Probe failures propagate and never trigger a fallback.
 *
 * All built-in probes share one read of up to 512 bytes, using [Magic]'s precedence.
 * Custom probes receive the complete source and should bounds-check their reads.
 * Registries own no resources and do not modify the source. Callbacks are shared
 * and retain their own thread-safety rules; registry mutation is never global.
 */
public class FormatRegistry private constructor(private val specs: List<FormatSpec>) {
    /** Independent snapshot of descriptors in detection order. */
    public val formats: List<FormatSpec> get() = specs.toMutableList()

    /** Return a registry that tries [spec] first, replacing any descriptor with the same id. */
    public fun with(spec: FormatSpec): FormatRegistry = FormatRegistry(listOf(spec) + specs.filter { it.id != spec.id })

    /** Find a descriptor by canonical identifier, or null if unregistered. */
    public fun byId(id: ArchiveFormat): FormatSpec? = specs.firstOrNull { it.id == id }

    /** Case-insensitive ASCII identifier lookup; unknown or invalid names return null. */
    public fun byName(id: String): FormatSpec? =
        if (id.any { it.code > 127 }) null else specs.firstOrNull { it.id.id == id.lowercase() }

    /**
     * Return the first matching descriptor, or null when unrecognized. Does not
     * open, close or decode the source, and does not search ZIP footers. Built-in
     * compression descriptors may match without an available container reader.
     * @throws ArchiveException if a probe or source operation fails, including an invalid source size or read count.
     */
    @Throws(ArchiveException::class)
    public fun sniff(source: RandomAccessSource): FormatSpec? {
        var header: ArchiveFormat? = null
        for (spec in specs) {
            val matched = if (spec.builtin) {
                val detected = header ?: Magic.sniff(source).also { header = it }
                detected == spec.id
            } else spec.matches(source)
            if (matched) return spec
        }
        return null
    }

    /** Default detection rules and registered reader factories. */
    public companion object {
        /**
         * Built-in magic rules, TAR/ZIP/ar/cpio/7z readers and the facade's single gzip
         * wrapper. Other recognized streams remain detectable but need an explicit
         * decoder or replacement descriptor to open. LZMA and BROTLI never sniff
         * automatically, but can be replaced and selected explicitly by id.
         */
        @JvmField
        public val DEFAULT: FormatRegistry = FormatRegistry(listOf(
            FormatSpec.builtin(ArchiveFormat.TAR) { source, options ->
                TarReader(source, options.nameDecoder ?: ArchiveNameDecoder.UTF8, options.limits)
            },
            FormatSpec.builtin(ArchiveFormat.ZIP) { source, options ->
                ZipReader(source, options)
            },
            FormatSpec.builtin(ArchiveFormat.AR) { source, options ->
                ArReader(source, options.nameDecoder ?: ArchiveNameDecoder.UTF8, options.limits, options.externalMembers)
            },
            FormatSpec.builtin(ArchiveFormat.CPIO) { source, options ->
                CpioReader(source, options.nameDecoder ?: ArchiveNameDecoder.UTF8, options.limits)
            },
            FormatSpec.builtin(ArchiveFormat.GZIP), FormatSpec.builtin(ArchiveFormat.ZLIB),
            FormatSpec.builtin(ArchiveFormat.BZIP2), FormatSpec.builtin(ArchiveFormat.XZ),
            FormatSpec.builtin(ArchiveFormat.LZMA), FormatSpec.builtin(ArchiveFormat.ZSTD),
            FormatSpec.builtin(ArchiveFormat.LZ4),
            FormatSpec.builtin(ArchiveFormat.SEVEN_ZIP) { source, options -> SevenZipReader(source, options) },
            FormatSpec.builtin(ArchiveFormat.RAR), FormatSpec.builtin(ArchiveFormat.BROTLI),
            FormatSpec.builtin(ArchiveFormat.SNAPPY),
        ))
    }
}
