package io.github.yuroyami.kitearchive.codec

import io.github.yuroyami.kitearchive.security.ArchiveLimits

/** The identity codec (ZIP method 0): no compression, the baseline fallback. */
object Store : Codec {
    override val id = CodecId.STORE

    override fun compress(data: ByteArray): ByteArray = data.copyOf()

    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(data.size.toLong())
        return data.copyOf()
    }
}
