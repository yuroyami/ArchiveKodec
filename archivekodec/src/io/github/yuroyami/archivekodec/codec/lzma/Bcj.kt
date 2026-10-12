package io.github.yuroyami.archivekodec.codec.lzma

/**
 * The x86 branch-call-jump filter, as the LZMA SDK's `Bra86.c` defines it for 7z and xz:
 * E8/E9 call and jump targets were made absolute when encoding, and are made relative
 * again here. The last four bytes of a stream are never converted.
 */
internal object Bcj {
    private val ALLOWED = booleanArrayOf(true, true, true, false, true, false, false, false)
    private val BIT_NUMBER = intArrayOf(0, 1, 2, 2, 3, 3, 3, 3)

    private fun isMsByte(b: Int): Boolean = ((b + 1) and 0xFE) == 0

    /** Decode [data] in place, as one stream starting at instruction pointer 0. */
    fun x86Decode(data: ByteArray) {
        val size = data.size
        if (size < 5) return
        val ip = 5
        var position = 0
        var previousPosition = -1
        var previousMask = 0
        val limit = size - 4
        while (true) {
            while (position < limit && (data[position].toInt() and 0xFE) != 0xE8) position++
            if (position >= limit) break
            val gap = position - previousPosition
            if (gap > 3) {
                previousMask = 0
            } else {
                previousMask = (previousMask shl (gap - 1)) and 0x7
                if (previousMask != 0) {
                    val b = data[position + 4 - BIT_NUMBER[previousMask]].toInt() and 0xFF
                    if (!ALLOWED[previousMask] || isMsByte(b)) {
                        previousPosition = position
                        previousMask = ((previousMask shl 1) and 0x7) or 1
                        position++
                        continue
                    }
                }
            }
            previousPosition = position
            if (isMsByte(data[position + 4].toInt() and 0xFF)) {
                var source = (data[position + 4].toInt() and 0xFF shl 24) or (data[position + 3].toInt() and 0xFF shl 16) or
                    (data[position + 2].toInt() and 0xFF shl 8) or (data[position + 1].toInt() and 0xFF)
                var destination: Int
                while (true) {
                    destination = source - (ip + position)
                    if (previousMask == 0) break
                    val index = BIT_NUMBER[previousMask] * 8
                    val b = (destination ushr (24 - index)) and 0xFF
                    if (!isMsByte(b)) break
                    source = destination xor ((1 shl (32 - index)) - 1)
                }
                data[position + 4] = ((((destination ushr 24) and 1) - 1).inv()).toByte()
                data[position + 3] = (destination ushr 16).toByte()
                data[position + 2] = (destination ushr 8).toByte()
                data[position + 1] = destination.toByte()
                position += 5
            } else {
                previousMask = ((previousMask shl 1) and 0x7) or 1
                position++
            }
        }
    }
}
