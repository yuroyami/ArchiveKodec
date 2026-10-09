package io.github.yuroyami.archivekodec.codec.bzip2

/** bzip2's CRC-32: polynomial 0x04C11DB7, most significant bit first, inverted. */
internal class Bzip2Crc {
    private var crc = -1

    fun update(value: Int) {
        crc = (crc shl 8) xor TABLE[(crc ushr 24) xor (value and 0xFF)]
    }

    fun update(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) update(data[i].toInt())
    }

    fun value(): Int = crc.inv()

    private companion object {
        val TABLE = IntArray(256) { index ->
            var c = index shl 24
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            c
        }
    }
}
