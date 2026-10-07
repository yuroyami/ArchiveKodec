package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.Zlib

/**
 * Registry of the codecs implemented today, each adapting a low-level engine
 * ([Deflate]/[Inflate], [Gzip], [Zlib], [Store]) to the [Codec] contract.
 */
object Codecs {

    val STORE: Codec = Store

    val DEFLATE: Codec = object : Codec {
        override val id = CodecId.DEFLATE
        override fun compress(data: ByteArray): ByteArray = Deflate.encode(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Inflate.inflate(data, limits)
    }

    val GZIP: Codec = object : Codec {
        override val id = CodecId.GZIP
        override fun compress(data: ByteArray): ByteArray = Gzip.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Gzip.decompress(data, limits)
    }

    val ZLIB: Codec = object : Codec {
        override val id = CodecId.ZLIB
        override fun compress(data: ByteArray): ByteArray = Zlib.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Zlib.decompress(data, limits)
    }

    val LZ4: Codec = object : Codec {
        override val id = CodecId.LZ4
        override fun compress(data: ByteArray): ByteArray = Lz4.frameCompress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Lz4.frameDecompress(data, limits)
    }

    val SNAPPY: Codec = object : Codec {
        override val id = CodecId.SNAPPY
        override fun compress(data: ByteArray): ByteArray = Snappy.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Snappy.decompress(data, limits)
    }

    private val all: List<Codec> = listOf(STORE, DEFLATE, GZIP, ZLIB, LZ4, SNAPPY)

    /** The codec for [id], or null if not yet implemented. */
    fun byId(id: CodecId): Codec? = all.firstOrNull { it.id == id }

    /** Ids of the codecs available right now. */
    val implemented: List<CodecId> get() = all.map { it.id }
}
