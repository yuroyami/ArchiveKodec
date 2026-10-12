package io.github.yuroyami.archivekodec.codec.ppmd

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.codec.lzma.LzOutput
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * The model memory a stream asks for is allocated up front, as the reference decoders do.
 * It is refused above [ArchiveLimits.maxDecoderMemory], so a tiny hostile header cannot
 * demand gigabytes, and above what one array can hold.
 */
private fun checkModelSize(size: Long, limits: ArchiveLimits) {
    if (size > limits.maxDecoderMemory) {
        throw DecompressionBombException("PPMd: model size $size B exceeds the decoder memory limit ${limits.maxDecoderMemory} B")
    }
    if (size > Int.MAX_VALUE - 64) throw ArchiveUnsupportedException("PPMd: model size $size B is too large")
}

/** Run [block], reporting a model or range-coder failure on malformed input as corrupt data. */
private inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: ArchiveException) {
    throw e
} catch (e: RuntimeException) {
    throw ArchiveFormatException("PPMd: corrupt data (${e::class.simpleName})")
}

/**
 * PPMd var.I revision 1 with ZIP's framing (method 98): a 16-bit little-endian header of
 * order - 1 (4 bits), memory in MiB - 1 (8 bits) and the restore method (4 bits), then the
 * range-coded symbols and an end marker.
 */
internal object PpmdZip {
    fun decompress(data: ByteArray, limits: ArchiveLimits, expectedSize: Long? = null, observe: ((PpmdHeap) -> Unit)? = null): ByteArray {
        if (data.size < 2) throw ArchiveFormatException("PPMd: missing header")
        val header = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val order = (header and 0xF) + 1
        val memory = (((header ushr 4) and 0xFF) + 1).toLong() shl 20
        val restore = header ushr 12
        if (order < 2) throw ArchiveFormatException("PPMd: model order $order is below 2")
        if (restore == 2) throw ArchiveUnsupportedException("PPMd: the freeze restore method is not supported")
        if (restore > 2) throw ArchiveFormatException("PPMd: invalid restore method $restore")
        checkModelSize(memory, limits)
        if (expectedSize != null) limits.checkOutputSize(expectedSize)
        val input = PpmdInput(data, 2, data.size)
        val out = LzOutput(limits, data.size)
        guarded {
            val rc = RangeDecoder8(input)
            if (!rc.init()) throw ArchiveFormatException("PPMd: invalid range coder start")
            val model = Ppmd8(memory.toInt(), order, restore)
            if (expectedSize != null) {
                for (n in 0 until expectedSize) {
                    val symbol = model.decodeSymbol(rc)
                    if (symbol < 0) {
                        throw ArchiveFormatException(if (symbol == -1) "PPMd: end marker after $n of $expectedSize bytes" else "PPMd: corrupt data")
                    }
                    out.put(symbol)
                }
            } else {
                while (true) {
                    val symbol = model.decodeSymbol(rc)
                    if (symbol == -1) break
                    if (symbol < 0) throw ArchiveFormatException("PPMd: corrupt data")
                    out.put(symbol)
                }
                if (!rc.finishedOk) throw ArchiveFormatException("PPMd: range coder did not finish cleanly")
            }
            observe?.invoke(model)
        }
        return out.take()
    }

    fun compress(data: ByteArray): ByteArray {
        val order = 6
        val memoryMiB = (data.size / (64 * 1024)).coerceIn(1, 16)
        val restore = Ppmd8.RESTORE_RESTART
        val out = ByteArrayBuilder(maxOf(64, data.size / 3))
        val header = (order - 1) or ((memoryMiB - 1) shl 4) or (restore shl 12)
        out.append(header.toByte())
        out.append((header ushr 8).toByte())
        val rc = RangeEncoder8(out)
        val model = Ppmd8(memoryMiB shl 20, order, restore)
        for (b in data) model.encodeSymbol(rc, b.toInt() and 0xFF)
        model.encodeSymbol(rc, -1)
        rc.flush()
        return out.takeByteArray()
    }
}

/** PPMd var.H as 7z stores it (method 03 04 01): properties are the order and a 32-bit memory size. */
internal object Ppmd7z {
    fun decompress(data: ByteArray, properties: ByteArray, size: Long, limits: ArchiveLimits, observe: ((PpmdHeap) -> Unit)? = null): ByteArray {
        if (properties.size < 5) throw ArchiveFormatException("7z: PPMd properties are too short")
        val order = properties[0].toInt() and 0xFF
        val memory = (properties[1].toLong() and 0xFF) or ((properties[2].toLong() and 0xFF) shl 8) or
            ((properties[3].toLong() and 0xFF) shl 16) or ((properties[4].toLong() and 0xFF) shl 24)
        if (order < 2 || order > Ppmd7.MAX_ORDER) throw ArchiveFormatException("7z: invalid PPMd order $order")
        if (memory < (1 shl 11) || memory > 0xFFFFFFFFL - 12 * 3) throw ArchiveFormatException("7z: invalid PPMd memory size $memory")
        checkModelSize(memory, limits)
        limits.checkOutputSize(size)
        val input = PpmdInput(data, 0, data.size)
        val out = LzOutput(limits, data.size)
        guarded {
            val rc = RangeDecoder7(input)
            if (!rc.init()) throw ArchiveFormatException("7z: invalid PPMd range coder start")
            val model = Ppmd7(memory.toInt(), order)
            for (n in 0 until size) {
                val symbol = model.decodeSymbol(rc)
                if (symbol < 0) {
                    throw ArchiveFormatException(if (symbol == -1) "7z: PPMd end marker after $n of $size bytes" else "7z: corrupt PPMd data")
                }
                out.put(symbol)
            }
            observe?.invoke(model)
        }
        return out.take()
    }
}
