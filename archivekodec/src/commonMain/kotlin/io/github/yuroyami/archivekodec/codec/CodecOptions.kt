package io.github.yuroyami.archivekodec.codec

/**
 * Settings for one codec's encoder, passed to [Codec.compress]. Each codec accepts its own
 * options class, such as [io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions] for
 * DEFLATE, zlib and gzip or [io.github.yuroyami.archivekodec.codec.lz4.Lz4Options] for LZ4,
 * and rejects any other with [IllegalArgumentException].
 */
public interface CodecOptions
