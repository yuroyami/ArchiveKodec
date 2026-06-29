package io.github.yuroyami.kitearchive.codec.deflate

/**
 * Raw DEFLATE (RFC 1951) decompressor — a faithful pure-Kotlin port of Mark
 * Adler's `puff()` (the canonical, deliberately-simple reference inflate, bundled
 * with zlib at `contrib/puff/puff.c`). Carried over from the KiteTorrent port,
 * which itself took it from libtorrent's `src/puff.cpp`.
 *
 * Differences from the C, all behaviour-preserving:
 *
 *  - puff writes into a caller-supplied fixed-size buffer and returns `1`
 *    ("output space exhausted") when it's too small; we grow the output array on
 *    demand instead, so error code `1` never occurs here.
 *
 *  - puff signals "ran past the end of input" via `setjmp`/`longjmp`; we model
 *    that non-local exit with a private [OutOfInput] throwable caught at the top
 *    of [inflate], mapping to [InflateError.DATA_DID_NOT_TERMINATE].
 *
 *  - puff's "scan only" mode (`dest == NULL`) is omitted; we always materialise
 *    the output.
 *
 * Bits are consumed LSB-first within each input byte; Huffman codes are read
 * MSB-first and accumulated in reversed order so canonical codes compare as plain
 * integers (see [decode]).
 *
 * This object is stateless and thread-safe; all mutable inflate state lives in the
 * private [State] created per call.
 */
object Inflate {

    // Maximums fixed by the deflate format (puff.c #defines).
    private const val MAXBITS = 15        // maximum bits in a code
    private const val MAXLCODES = 286     // maximum number of literal/length codes
    private const val MAXDCODES = 30      // maximum number of distance codes
    private const val MAXCODES = MAXLCODES + MAXDCODES
    private const val FIXLCODES = 288     // number of fixed literal/length codes

    // Size base for length codes 257..285 (puff `lens[]`).
    private val LENS = shortArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    // Extra bits for length codes 257..285 (puff `lext[]`).
    private val LEXT = shortArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    // Offset base for distance codes 0..29 (puff `dists[]`).
    private val DISTS = shortArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577,
    )
    // Extra bits for distance codes 0..29 (puff `dext[]`).
    private val DEXT = shortArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11,
        12, 12, 13, 13,
    )
    // Permutation of the code length codes in a dynamic block (puff `order[]`).
    private val ORDER = intArrayOf(
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
    )

    /**
     * Inflate a raw DEFLATE stream (no gzip/zlib wrapper) and return the
     * decompressed bytes.
     *
     * @throws InflateException with the faithful [InflateError] on malformed or
     *   truncated input — the same failure taxonomy puff returns numerically.
     */
    fun inflate(input: ByteArray): ByteArray {
        val s = State(input)
        try {
            var last: Int
            do {
                last = s.bits(1)            // one if last block
                val type = s.bits(2)        // block type 0..3
                val err = when (type) {
                    0 -> stored(s)
                    1 -> fixed(s)
                    2 -> dynamic(s)
                    else -> InflateError.INVALID_BLOCK_TYPE.code   // type == 3
                }
                if (err != 0) throw InflateException(InflateError.fromPuff(err))
            } while (last == 0)
        } catch (_: OutOfInput) {
            throw InflateException(InflateError.DATA_DID_NOT_TERMINATE)
        }
        return s.output()
    }

    /** Non-local "out of input" signal, replacing puff's setjmp/longjmp. */
    private class OutOfInput : Throwable()

    /**
     * Per-call inflate state. Holds the input cursor + bit buffer and the growable
     * output. Mirrors puff's `struct state`, except [out] grows instead of being a
     * fixed buffer, so there is no `outlen` overflow path.
     */
    private class State(private val inBuf: ByteArray) {
        private var incnt = 0           // bytes read so far
        var bitbuf = 0                  // bit buffer
        var bitcnt = 0                  // number of valid low bits in bitbuf

        private var out = ByteArray(256)
        var outcnt = 0
            private set

        val inLen: Int get() = inBuf.size
        val inAvailable: Int get() = inBuf.size - incnt

        fun output(): ByteArray = out.copyOf(outcnt)

        fun nextByte(): Int {
            if (incnt == inBuf.size) throw OutOfInput()
            return inBuf[incnt++].toInt() and 0xff
        }

        fun rawByte(): Int = inBuf[incnt++].toInt() and 0xff

        fun skip(n: Int) { incnt += n }

        fun bits(need: Int): Int {
            var value = bitbuf
            while (bitcnt < need) {
                value = value or (nextByte() shl bitcnt)
                bitcnt += 8
            }
            bitbuf = value ushr need
            bitcnt -= need
            return value and ((1 shl need) - 1)
        }

        fun ensure(extra: Int) {
            val needed = outcnt + extra
            if (needed > out.size) {
                var cap = if (out.size == 0) 256 else out.size
                while (cap < needed) cap = cap shl 1
                out = out.copyOf(cap)
            }
        }

        fun writeByte(b: Int) {
            ensure(1)
            out[outcnt++] = b.toByte()
        }

        /** Copy [len] bytes from [dist] back in the output (LZ77 back-reference). */
        fun copyBack(dist: Int, len: Int) {
            ensure(len)
            var src = outcnt - dist
            var n = len
            while (n-- > 0) {
                out[outcnt++] = out[src++]
            }
        }

        /** Copy [len] verbatim bytes straight from the input (stored block). */
        fun copyStored(len: Int) {
            ensure(len)
            var n = len
            while (n-- > 0) {
                out[outcnt++] = inBuf[incnt++]
            }
        }
    }

    private class Huffman(maxSymbols: Int) {
        val count = ShortArray(MAXBITS + 1)
        val symbol = ShortArray(maxSymbols)
    }

    private fun stored(s: State): Int {
        s.bitbuf = 0
        s.bitcnt = 0

        if (s.inAvailable < 4) return InflateError.DATA_DID_NOT_TERMINATE.code
        var len = s.rawByte()
        len = len or (s.rawByte() shl 8)
        val nlen0 = s.rawByte()
        val nlen1 = s.rawByte()
        if (nlen0 != (len.inv() and 0xff) || nlen1 != ((len.inv() ushr 8) and 0xff)) {
            return InflateError.INVALID_STORED_BLOCK_LENGTH.code
        }

        if (s.inAvailable < len) return InflateError.DATA_DID_NOT_TERMINATE.code
        s.copyStored(len)
        return 0
    }

    private fun decode(s: State, h: Huffman): Int {
        var bitbuf = s.bitbuf
        var left = s.bitcnt
        var code = 0
        var first = 0
        var index = 0
        var len = 1
        var nextIdx = 1

        while (true) {
            while (left > 0) {
                left--
                code = code or (bitbuf and 1)
                bitbuf = bitbuf ushr 1
                val count = h.count[nextIdx++].toInt()
                if (code - count < first) {
                    s.bitbuf = bitbuf
                    s.bitcnt = (s.bitcnt - len) and 7
                    return h.symbol[index + (code - first)].toInt()
                }
                index += count
                first += count
                first = first shl 1
                code = code shl 1
                len++
            }
            left = (MAXBITS + 1) - len
            if (left == 0) break
            bitbuf = s.nextByte()
            if (left > 8) left = 8
        }
        return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code
    }

    private fun construct(h: Huffman, length: ShortArray, n: Int): Int {
        for (len in 0..MAXBITS) h.count[len] = 0
        for (symbol in 0 until n) h.count[length[symbol].toInt()]++
        if (h.count[0].toInt() == n) return 0

        var left = 1
        for (len in 1..MAXBITS) {
            left = left shl 1
            left -= h.count[len].toInt()
            if (left < 0) return left
        }

        val offs = ShortArray(MAXBITS + 1)
        offs[1] = 0
        for (len in 1 until MAXBITS) {
            offs[len + 1] = (offs[len] + h.count[len]).toShort()
        }

        for (symbol in 0 until n) {
            val l = length[symbol].toInt()
            if (l != 0) {
                h.symbol[offs[l].toInt()] = symbol.toShort()
                offs[l]++
            }
        }
        return left
    }

    private fun codes(s: State, lencode: Huffman, distcode: Huffman): Int {
        var symbol: Int
        do {
            symbol = decode(s, lencode)
            if (symbol < 0) return symbol
            when {
                symbol < 256 -> {
                    s.writeByte(symbol)
                }
                symbol > 256 -> {
                    var sym = symbol - 257
                    if (sym >= 29) return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code
                    val len = LENS[sym].toInt() + s.bits(LEXT[sym].toInt())

                    sym = decode(s, distcode)
                    if (sym < 0) return sym
                    val dist = DISTS[sym].toInt() + s.bits(DEXT[sym].toInt())
                    if (dist > s.outcnt) return InflateError.DISTANCE_TOO_FAR_BACK.code
                    s.copyBack(dist, len)
                }
                // symbol == 256 -> end of block, loop terminates
            }
        } while (symbol != 256)
        return 0
    }

    private fun fixed(s: State): Int {
        val lengths = ShortArray(FIXLCODES)
        var symbol = 0
        while (symbol < 144) { lengths[symbol] = 8; symbol++ }
        while (symbol < 256) { lengths[symbol] = 9; symbol++ }
        while (symbol < 280) { lengths[symbol] = 7; symbol++ }
        while (symbol < FIXLCODES) { lengths[symbol] = 8; symbol++ }
        val lencode = Huffman(FIXLCODES)
        construct(lencode, lengths, FIXLCODES)

        for (i in 0 until MAXDCODES) lengths[i] = 5
        val distcode = Huffman(MAXDCODES)
        construct(distcode, lengths, MAXDCODES)

        return codes(s, lencode, distcode)
    }

    private fun dynamic(s: State): Int {
        val lengths = ShortArray(MAXCODES)

        val nlen = s.bits(5) + 257
        val ndist = s.bits(5) + 1
        val ncode = s.bits(4) + 4
        if (nlen > MAXLCODES || ndist > MAXDCODES) {
            return InflateError.TOO_MANY_LENGTH_OR_DISTANCE_CODES.code
        }

        var index = 0
        while (index < ncode) {
            lengths[ORDER[index]] = s.bits(3).toShort()
            index++
        }
        while (index < 19) {
            lengths[ORDER[index]] = 0
            index++
        }

        val lencode = Huffman(MAXLCODES)
        var err = construct(lencode, lengths, 19)
        if (err != 0) return InflateError.CODE_LENGTHS_CODES_INCOMPLETE.code

        index = 0
        while (index < nlen + ndist) {
            var symbol = decode(s, lencode)
            if (symbol < 0) return symbol
            if (symbol < 16) {
                lengths[index++] = symbol.toShort()
            } else {
                var repeatLen = 0
                when (symbol) {
                    16 -> {
                        if (index == 0) return InflateError.REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH.code
                        repeatLen = lengths[index - 1].toInt()
                        symbol = 3 + s.bits(2)
                    }
                    17 -> symbol = 3 + s.bits(3)
                    else -> symbol = 11 + s.bits(7)
                }
                if (index + symbol > nlen + ndist) {
                    return InflateError.REPEAT_MORE_THAN_SPECIFIED_LENGTHS.code
                }
                while (symbol-- > 0) lengths[index++] = repeatLen.toShort()
            }
        }

        if (lengths[256].toInt() == 0) return InflateError.MISSING_END_OF_BLOCK_CODE.code

        err = construct(lencode, lengths, nlen)
        if (err != 0 && (err < 0 || nlen != lencode.count[0].toInt() + lencode.count[1].toInt())) {
            return InflateError.INVALID_LITERAL_LENGTH_CODE_LENGTHS.code
        }

        val distLengths = ShortArray(MAXDCODES)
        for (i in 0 until ndist) distLengths[i] = lengths[nlen + i]
        val distcode = Huffman(MAXDCODES)
        err = construct(distcode, distLengths, ndist)
        if (err != 0 && (err < 0 || ndist != distcode.count[0].toInt() + distcode.count[1].toInt())) {
            return InflateError.INVALID_DISTANCE_CODE_LENGTHS.code
        }

        return codes(s, lencode, distcode)
    }
}
