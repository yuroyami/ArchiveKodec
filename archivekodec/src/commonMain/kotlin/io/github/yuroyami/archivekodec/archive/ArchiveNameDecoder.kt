package io.github.yuroyami.archivekodec.archive

import kotlin.jvm.JvmField

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import kotlin.text.CharacterCodingException

/**
 * Decode stored filename bytes after container framing has been removed.
 * Readers supply a defensive copy. ZIP uses this decoder only for unflagged
 * names without valid Unicode Path metadata; bit 11 always selects strict UTF-8.
 * Supply a platform charset decoder for other legacy encodings.
 */
public fun interface ArchiveNameDecoder {
    /**
     * Decode one filename.
     * @throws ArchiveFormatException if the built-in UTF-8 decoder encounters malformed bytes.
     * @throws ArchiveException if the bytes cannot be decoded under this decoder's policy.
     */
    @Throws(ArchiveException::class)
    public fun decode(bytes: ByteArray): String

    /** Built-in strict UTF-8 and legacy CP437 filename decoders. */
    public companion object {
        /** Strict UTF-8; malformed sequences raise [ArchiveFormatException]. */
        @JvmField
        public val UTF8: ArchiveNameDecoder = ArchiveNameDecoder { bytes ->
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (error: CharacterCodingException) {
                throw ArchiveFormatException("invalid UTF-8 filename", cause = error)
            }
        }

        /** IBM CP437, including ASCII controls (not DOS screen glyphs). */
        @JvmField
        public val CP437: ArchiveNameDecoder = ArchiveNameDecoder { bytes ->
            buildString(bytes.size) {
                for (byte in bytes) {
                    val value = byte.toInt() and 255
                    append(if (value < 128) value.toChar() else CP437_HIGH[value - 128])
                }
            }
        }
    }
}

internal fun ArchiveNameDecoder.decodeStoredName(bytes: ByteArray, offset: Long): String = try {
    decode(bytes.copyOf())
} catch (error: ArchiveException) {
    throw error.withEntryContext(bytes.decodeToString(), offset)
} catch (error: Exception) {
    throw ArchiveFormatException("filename decoder failed", offset, bytes.decodeToString(), error)
}

// Unicode Consortium vendor mapping: MICSFT/PC/CP437.TXT.
private const val CP437_HIGH =
    "\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00a2\u00a3\u00a5\u20a7\u0192" +
    "\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u2310\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510" +
    "\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580" +
    "\u03b1\u00df\u0393\u03c0\u03a3\u03c3\u00b5\u03c4\u03a6\u0398\u03a9\u03b4\u221e\u03c6\u03b5\u2229\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u00a0"
