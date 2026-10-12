package io.github.yuroyami.archivekodec.archive.zip

/**
 * Compression methods supported by [ZipWriter]. [STORED] writes bytes unchanged;
 * [DEFLATED] uses raw DEFLATE and is the default for [ZipWriter.FileSpec].
 *
 * [code] is the ZIP wire identifier; persist it rather than an enum ordinal.
 * More supported methods may be added, so caller `when` expressions need `else`.
 */
public enum class ZipMethod(
    /** ZIP wire method number, independent of the enum ordinal. */
    public val code: Int,
) {
    /** Store member bytes without compression. */
    STORED(0),
    /** Compress member bytes as a raw DEFLATE stream. */
    DEFLATED(8),
}
