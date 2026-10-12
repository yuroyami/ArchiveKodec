package io.github.yuroyami.archivekodec.codec.deflate

/** The issue #44 fixture, using Java Random's specified 48-bit sequence. */
internal val deflateLengthLimitInput: ByteArray by lazy {
    val multiplier = 0x5DEECE66DL
    val mask = (1L shl 48) - 1
    var seed = multiplier // new java.util.Random(0)
    fun next31(): Int {
        seed = (seed * multiplier + 11) and mask
        return (seed ushr 17).toInt()
    }
    fun nextInt(bound: Int): Int {
        if (bound and (bound - 1) == 0) return ((bound.toLong() * next31()) ushr 31).toInt()
        while (true) {
            val bits = next31()
            val value = bits % bound
            // Express Java's signed-Int rejection without target-specific overflow.
            if (bits.toLong() - value + bound - 1 < (1L shl 31)) return value
        }
    }
    val input = ByteArray(1_000_000)
    var position = 0
    var f0 = 1
    var f1 = 1
    for (symbol in 0 until 15) {
        val count = if (symbol < 2) 1 else (f0 + f1).also { f0 = f1; f1 = it }
        repeat(count) { input[position++] = symbol.toByte() }
    }
    while (position < input.size) input[position++] = (32 + nextInt(224)).toByte()
    for (i in input.lastIndex downTo 1) {
        val j = nextInt(i + 1)
        val value = input[i]; input[i] = input[j]; input[j] = value
    }
    input
}
