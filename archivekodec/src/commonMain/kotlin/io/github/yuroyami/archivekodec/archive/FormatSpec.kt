package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import kotlin.jvm.JvmSynthetic

/** A read-only format probe, free to inspect any source offset. Never closes or retains the source. */
public fun interface FormatMatcher {
    /**
     * Test a candidate; return false for insufficient input. Validate ranges before
     * reading. A match is not full archive validation, and decoding belongs in the reader.
     * @throws ArchiveException if probing the source fails; failures propagate rather than becoming nonmatches.
     */
    @Throws(ArchiveException::class)
    public fun matches(source: RandomAccessSource): Boolean
}

/** Factory for a registered format. Callback instances may be shared between registries. */
public fun interface FormatReaderFactory {
    /**
     * Return a reader with the descriptor's format, enforcing [options]. Ownership
     * transfers to the returned reader on success. Do not close [source] on failure;
     * the caller still owns it. Readers must release the source when closed.
     * @throws ArchiveException if the format cannot be opened or its policy is exceeded.
     */
    @Throws(ArchiveException::class)
    public fun open(source: RandomAccessSource, options: ArchiveOpenOptions): ArchiveReader
}

/**
 * Immutable format description for [FormatRegistry.with]. Detection and opening
 * callbacks are shared, not copied; they must document any synchronization they need.
 * Opening goes through [io.github.yuroyami.archivekodec.ArchiveKodec.open], which
 * supplies the selected options and handles the built-in single gzip wrapper.
 *
 * @throws IllegalArgumentException if [id] is the reserved UNKNOWN sentinel.
 */
public class FormatSpec private constructor(
    /** Format returned by this descriptor's reader. */
    public val id: ArchiveFormat,
    private val matcher: FormatMatcher?,
    private val factory: FormatReaderFactory?,
    @get:JvmSynthetic internal val builtin: Boolean,
) {
    /** Describe a custom reader and its probe; UNKNOWN is reserved for failed detection. */
    @Throws(IllegalArgumentException::class)
    public constructor(id: ArchiveFormat, matches: FormatMatcher, open: FormatReaderFactory) :
        this(id, matches, open, false) {
        require(id != ArchiveFormat.UNKNOWN) { "UNKNOWN is reserved for unrecognized input" }
    }

    /**
     * Describe a format by its ASCII identifier, for example `squashfs`.
     * @throws IllegalArgumentException if [id] is invalid or names the UNKNOWN sentinel.
     */
    @Throws(IllegalArgumentException::class)
    public constructor(id: String, matches: FormatMatcher, open: FormatReaderFactory) :
        this(ArchiveFormat(id), matches, open)

    @JvmSynthetic
    internal fun matches(source: RandomAccessSource): Boolean = matcher!!.matches(source)

    @JvmSynthetic
    internal fun open(source: RandomAccessSource, options: ArchiveOpenOptions): ArchiveReader =
        factory?.open(source, options) ?: throw ArchiveUnsupportedException("no container reader for $id", offset = 0)

    /** Describe the identifier without invoking callbacks. */
    override fun toString(): String = "FormatSpec(id=${id.id})"

    /** Holds internal built-in descriptor construction. */
    public companion object {
        @JvmSynthetic
        internal fun builtin(id: ArchiveFormat, factory: FormatReaderFactory? = null): FormatSpec =
            FormatSpec(id, null, factory, true)
    }
}
