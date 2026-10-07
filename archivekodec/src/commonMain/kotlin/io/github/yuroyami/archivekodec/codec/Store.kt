package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/** The identity codec (ZIP method 0): no compression, the baseline fallback. */
object Store : Codec {
    override val id = CodecId.STORE

    @Throws(ArchiveException::class)
    override fun compress(data: ByteArray): ByteArray = data.copyOf()

    @Throws(ArchiveException::class)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(data.size.toLong())
        limits.checkRatio(data.size.toLong(), data.size.toLong())
        return data.copyOf()
    }
}
