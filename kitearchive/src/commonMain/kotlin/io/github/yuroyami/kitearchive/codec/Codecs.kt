package io.github.yuroyami.kitearchive.codec

import io.github.yuroyami.kitearchive.codec.deflate.Deflate
import io.github.yuroyami.kitearchive.codec.deflate.Inflate
import io.github.yuroyami.kitearchive.security.ArchiveLimits
import io.github.yuroyami.kitearchive.wrapper.Gzip
import io.github.yuroyami.kitearchive.wrapper.Zlib

/**
 * Registry of the codecs implemented today, each adapting a low-level engine
 * ([Deflate]/[Inflate], [Gzip], [Zlib], [Store]) to the [Codec] contract.
 */
object Codecs {

    val STORE: Codec = Store

    val DEFLATE: Codec = object : Codec {
        override val id = CodecId.DEFLATE
        override fun compress(data: ByteArray): ByteArray = Deflate.encode(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray {
            val out = Inflate.inflate(data)
            limits.checkOutputSize(out.size.toLong())
            return out
        }
    }

    val GZIP: Codec = object : Codec {
        override val id = CodecId.GZIP
        override fun compress(data: ByteArray): ByteArray = Gzip.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Gzip.decompress(data, limits.maxUncompressedBytes)
    }

    val ZLIB: Codec = object : Codec {
        override val id = CodecId.ZLIB
        override fun compress(data: ByteArray): ByteArray = Zlib.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Zlib.decompress(data, limits.maxUncompressedBytes)
    }

    private val all: List<Codec> = listOf(STORE, DEFLATE, GZIP, ZLIB)

    /** The codec for [id], or null if not yet implemented. */
    fun byId(id: CodecId): Codec? = all.firstOrNull { it.id == id }

    /** Ids of the codecs available right now. */
    val implemented: List<CodecId> get() = all.map { it.id }
}
