package io.github.yuroyami.archivekodec.codec.deflate

// No platform zlib is used here; the pure decoder and encoder run.

internal actual fun platformInflate(
    input: ByteArray, offset: Int, length: Int, zlib: Boolean, sizeHint: Int, maxOutput: Long,
): PlatformInflated? = null

internal actual fun platformDeflate(data: ByteArray, level: Int, strategy: Int): ByteArray? = null
