package io.github.yuroyami.archivekodec.codec.bzip2

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * bzip2 decoder: Huffman with up to six tables and a selector per 50 symbols, then
 * move-to-front, RUNA/RUNB zero runs, the inverse Burrows-Wheeler transform and the
 * initial run-length stage. Concatenated streams decode as one output, as `bzip2 -d`
 * does. Block and stream CRCs are verified, and output is checked against the limits
 * before each write. Deprecated randomised blocks are supported.
 */
internal object Bzip2Decoder {
    private const val GROUP_SIZE = 50
    private const val MAX_GROUPS = 6
    private const val MAX_SELECTORS = 18_002 // keep at most this many, as libbzip2 does since CVE-2019-12900
    private const val MAX_CODE_LENGTH = 20
    private const val RUN_A = 0
    private const val RUN_B = 1

    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        limits.checkOutputSize(0)
        limits.checkRatio(input.size.toLong(), 0)
        val out = Output(limits, input.size)
        val bits = BitReader(input)
        var streams = 0
        while (streams == 0 || bits.bytePosition < input.size) {
            decodeStream(bits, out)
            streams++
        }
        return out.take()
    }

    private fun decodeStream(bits: BitReader, out: Output) {
        val start = bits.bytePosition.toLong()
        if (bits.remainingBits < 32) throw ArchiveFormatException("bzip2: truncated stream header", start)
        if (bits.bits(8) != 'B'.code || bits.bits(8) != 'Z'.code || bits.bits(8) != 'h'.code) {
            throw ArchiveFormatException("bzip2: missing BZh signature", start)
        }
        val level = bits.bits(8) - '0'.code
        if (level !in 1..9) throw ArchiveFormatException("bzip2: invalid block size level", start + 3)
        val maxBlock = level * 100_000
        val tt = IntArray(maxBlock)
        var combined = 0
        while (true) {
            val magic = bits.bits(24).toLong() shl 24 or bits.bits(24).toLong()
            when (magic) {
                0x177245385090L -> {
                    val stored = bits.bits(32)
                    if (stored != combined) throw ArchiveFormatException("bzip2: stream CRC mismatch", bits.bytePosition.toLong())
                    bits.alignToByte()
                    return
                }
                0x314159265359L -> {
                    val crc = decodeBlock(bits, out, tt, maxBlock)
                    combined = (combined shl 1 or (combined ushr 31)) xor crc
                }
                else -> throw ArchiveFormatException("bzip2: invalid block signature", bits.bytePosition.toLong())
            }
        }
    }

    /** Decode one block into [out] and return its verified CRC. */
    private fun decodeBlock(bits: BitReader, out: Output, tt: IntArray, maxBlock: Int): Int {
        val blockOffset = bits.bytePosition.toLong()
        fun fail(message: String): Nothing = throw ArchiveFormatException("bzip2: $message", bits.bytePosition.toLong())
        val storedCrc = bits.bits(32)
        val randomised = bits.bits(1) == 1
        val origPtr = bits.bits(24)

        // Symbol map: which byte values occur, in sixteen groups of sixteen.
        val seqToUnseq = IntArray(256)
        var inUse = 0
        val groups = bits.bits(16)
        for (i in 0 until 16) {
            if (groups and (0x8000 ushr i) == 0) continue
            val members = bits.bits(16)
            for (j in 0 until 16) if (members and (0x8000 ushr j) != 0) seqToUnseq[inUse++] = i * 16 + j
        }
        if (inUse == 0) fail("block uses no symbols")
        val alphaSize = inUse + 2
        val endOfBlock = inUse + 1

        val nGroups = bits.bits(3)
        if (nGroups !in 2..MAX_GROUPS) fail("invalid Huffman table count $nGroups")
        val nSelectors = bits.bits(15)
        if (nSelectors < 1) fail("block has no selectors")
        val selectorOrder = IntArray(nGroups) { it }
        val kept = minOf(nSelectors, MAX_SELECTORS)
        val selectors = ByteArray(kept)
        for (i in 0 until nSelectors) {
            var j = 0
            while (bits.bits(1) == 1) if (++j >= nGroups) fail("invalid selector")
            // Undo the selector move-to-front coding.
            val table = selectorOrder[j]
            while (j > 0) { selectorOrder[j] = selectorOrder[j - 1]; j-- }
            selectorOrder[0] = table
            if (i < kept) selectors[i] = table.toByte()
        }

        val tables = Array(nGroups) {
            val lengths = IntArray(alphaSize)
            var current = bits.bits(5)
            for (symbol in 0 until alphaSize) {
                while (true) {
                    if (current !in 1..MAX_CODE_LENGTH) fail("invalid code length $current")
                    if (bits.bits(1) == 0) break
                    current += if (bits.bits(1) == 0) 1 else -1
                }
                lengths[symbol] = current
            }
            DecodeTable(lengths, alphaSize)
        }

        // Huffman, move-to-front and RUNA/RUNB decoding into tt's low bytes.
        val counts = IntArray(256)
        val mtf = IntArray(256) { it }
        var groupIndex = -1
        var groupLeft = 0
        var table = tables[0]
        fun nextSymbol(): Int {
            if (groupLeft == 0) {
                if (++groupIndex >= kept) fail("selectors exhausted")
                table = tables[selectors[groupIndex].toInt()]
                groupLeft = GROUP_SIZE
            }
            groupLeft--
            return table.decode(bits) ?: fail("invalid Huffman code")
        }
        var size = 0
        var symbol = nextSymbol()
        while (symbol != endOfBlock) {
            if (symbol == RUN_A || symbol == RUN_B) {
                var run = 0
                var weight = 1
                while (symbol == RUN_A || symbol == RUN_B) {
                    run += weight shl symbol // RUNA adds 1×weight, RUNB 2×weight
                    weight = weight shl 1
                    if (weight > maxBlock || run > maxBlock) fail("zero run exceeds the block size")
                    symbol = nextSymbol()
                }
                val value = seqToUnseq[mtf[0]]
                if (run > maxBlock - size) fail("block exceeds its declared size")
                counts[value] += run
                tt.fill(value, size, size + run)
                size += run
            } else {
                if (symbol > endOfBlock) fail("invalid symbol $symbol")
                if (size >= maxBlock) fail("block exceeds its declared size")
                var position = symbol - 1
                val moved = mtf[position]
                while (position > 0) { mtf[position] = mtf[position - 1]; position-- }
                mtf[0] = moved
                val value = seqToUnseq[moved]
                counts[value]++
                tt[size++] = value
                symbol = nextSymbol()
            }
        }
        if (origPtr !in 0 until size) fail("original pointer $origPtr outside block of $size")

        // Inverse Burrows-Wheeler transform: link each position to its successor.
        val starts = IntArray(256)
        var sum = 0
        for (i in 0 until 256) { starts[i] = sum; sum += counts[i] }
        for (i in 0 until size) {
            val value = tt[i] and 0xFF
            tt[starts[value]] = tt[starts[value]] or (i shl 8)
            starts[value]++
        }

        // Walk the chain, undo randomisation and the initial run-length stage, and check the CRC.
        val crc = Bzip2Crc()
        var next = tt[origPtr] ushr 8
        var randomIndex = 0
        var randomCountdown = 0
        var previous = -1
        var repeat = 0
        var produced = 0
        fun step(): Int {
            val entry = tt[next]
            next = entry ushr 8
            var value = entry and 0xFF
            if (randomised) {
                // libbzip2's BZ_RAND_UPD_MASK, then BZ_RAND_MASK.
                if (randomCountdown == 0) {
                    randomCountdown = RNUMS[randomIndex]
                    randomIndex = (randomIndex + 1) and 511
                }
                randomCountdown--
                if (randomCountdown == 1) value = value xor 1
            }
            produced++
            return value
        }
        while (produced < size) {
            val value = step()
            if (repeat == 4) {
                out.repeat(previous, value, crc)
                repeat = 0
                previous = -1
                continue
            }
            if (value == previous) repeat++ else { repeat = 1; previous = value }
            out.write(value, crc)
        }
        val actual = crc.value()
        if (actual != storedCrc) throw ArchiveFormatException("bzip2: block CRC mismatch", blockOffset)
        return actual
    }

    /** Apply the randomisation mask to the first [n] bytes of [block], which is its own inverse. */
    internal fun randomise(block: ByteArray, n: Int) {
        var index = 0
        var countdown = 0
        for (i in 0 until n) {
            if (countdown == 0) {
                countdown = RNUMS[index]
                index = (index + 1) and 511
            }
            countdown--
            if (countdown == 1) block[i] = (block[i].toInt() xor 1).toByte()
        }
    }

    /** Canonical Huffman decoding by code length, as libbzip2's limit/base/perm tables. */
    private class DecodeTable(lengths: IntArray, alphaSize: Int) {
        private val minLength = lengths.min()
        private val maxLength = lengths.max()
        private val limit = IntArray(MAX_CODE_LENGTH + 2)
        private val base = IntArray(MAX_CODE_LENGTH + 2)
        private val perm = IntArray(alphaSize)

        init {
            var p = 0
            for (length in minLength..maxLength) for (symbol in 0 until alphaSize) if (lengths[symbol] == length) perm[p++] = symbol
            val count = IntArray(MAX_CODE_LENGTH + 2)
            for (length in lengths) count[length]++
            var code = 0
            var index = 0
            for (length in minLength..maxLength) {
                base[length] = index - code // index of a code = code + base
                code += count[length]
                index += count[length]
                limit[length] = code - 1
                code = code shl 1
            }
        }

        fun decode(bits: BitReader): Int? {
            var length = minLength
            var code = bits.bits(length)
            while (code > limit[length]) {
                if (++length > maxLength) return null
                code = code shl 1 or bits.bits(1)
            }
            val index = code + base[length]
            return if (index in perm.indices) perm[index] else null
        }
    }

    /** MSB-first bit reader over the whole input. */
    private class BitReader(private val input: ByteArray) {
        private var position = 0
        private var buffer = 0L
        private var count = 0
        val bytePosition: Int get() = position - count / 8
        val remainingBits: Long get() = (input.size - position) * 8L + count

        fun bits(n: Int): Int {
            while (count < n) {
                if (position >= input.size) throw ArchiveFormatException("bzip2: truncated stream", input.size.toLong())
                buffer = buffer shl 8 or (input[position++].toLong() and 0xFF)
                count += 8
            }
            count -= n
            return ((buffer ushr count) and ((1L shl n) - 1)).toInt()
        }

        fun alignToByte() { count -= count % 8 }
    }

    /** Output bounded by the caller's limits before each growth. */
    private class Output(private val limits: ArchiveLimits, private val compressedBytes: Int) {
        private val builder = ByteArrayBuilder(4096)
        private val chunk = ByteArray(4096)
        private var pending = 0
        private var size = 0L

        private fun reserve(n: Int) {
            limits.checkOutputSize(size + n)
            limits.checkRatio(compressedBytes.toLong(), size + n)
            size += n
        }

        fun write(value: Int, crc: Bzip2Crc) {
            if (pending == chunk.size) flush()
            crc.update(value)
            chunk[pending++] = value.toByte()
        }

        fun repeat(value: Int, times: Int, crc: Bzip2Crc) {
            repeat(times) { write(value, crc) }
        }

        private fun flush() {
            reserve(pending)
            builder.append(chunk, 0, pending)
            pending = 0
        }

        fun take(): ByteArray {
            flush()
            return builder.takeByteArray()
        }
    }

    // libbzip2's BZ2_rNums, the offsets between toggled bytes in randomised blocks.
    private val RNUMS = intArrayOf(
        619, 720, 127, 481, 931, 816, 813, 233, 566, 247, 985, 724, 205, 454, 863, 491,
        741, 242, 949, 214, 733, 859, 335, 708, 621, 574, 73, 654, 730, 472, 419, 436,
        278, 496, 867, 210, 399, 680, 480, 51, 878, 465, 811, 169, 869, 675, 611, 697,
        867, 561, 862, 687, 507, 283, 482, 129, 807, 591, 733, 623, 150, 238, 59, 379,
        684, 877, 625, 169, 643, 105, 170, 607, 520, 932, 727, 476, 693, 425, 174, 647,
        73, 122, 335, 530, 442, 853, 695, 249, 445, 515, 909, 545, 703, 919, 874, 474,
        882, 500, 594, 612, 641, 801, 220, 162, 819, 984, 589, 513, 495, 799, 161, 604,
        958, 533, 221, 400, 386, 867, 600, 782, 382, 596, 414, 171, 516, 375, 682, 485,
        911, 276, 98, 553, 163, 354, 666, 933, 424, 341, 533, 870, 227, 730, 475, 186,
        263, 647, 537, 686, 600, 224, 469, 68, 770, 919, 190, 373, 294, 822, 808, 206,
        184, 943, 795, 384, 383, 461, 404, 758, 839, 887, 715, 67, 618, 276, 204, 918,
        873, 777, 604, 560, 951, 160, 578, 722, 79, 804, 96, 409, 713, 940, 652, 934,
        970, 447, 318, 353, 859, 672, 112, 785, 645, 863, 803, 350, 139, 93, 354, 99,
        820, 908, 609, 772, 154, 274, 580, 184, 79, 626, 630, 742, 653, 282, 762, 623,
        680, 81, 927, 626, 789, 125, 411, 521, 938, 300, 821, 78, 343, 175, 128, 250,
        170, 774, 972, 275, 999, 639, 495, 78, 352, 126, 857, 956, 358, 619, 580, 124,
        737, 594, 701, 612, 669, 112, 134, 694, 363, 992, 809, 743, 168, 974, 944, 375,
        748, 52, 600, 747, 642, 182, 862, 81, 344, 805, 988, 739, 511, 655, 814, 334,
        249, 515, 897, 955, 664, 981, 649, 113, 974, 459, 893, 228, 433, 837, 553, 268,
        926, 240, 102, 654, 459, 51, 686, 754, 806, 760, 493, 403, 415, 394, 687, 700,
        946, 670, 656, 610, 738, 392, 760, 799, 887, 653, 978, 321, 576, 617, 626, 502,
        894, 679, 243, 440, 680, 879, 194, 572, 640, 724, 926, 56, 204, 700, 707, 151,
        457, 449, 797, 195, 791, 558, 945, 679, 297, 59, 87, 824, 713, 663, 412, 693,
        342, 606, 134, 108, 571, 364, 631, 212, 174, 643, 304, 329, 343, 97, 430, 751,
        497, 314, 983, 374, 822, 928, 140, 206, 73, 263, 980, 736, 876, 478, 430, 305,
        170, 514, 364, 692, 829, 82, 855, 953, 676, 246, 369, 970, 294, 750, 807, 827,
        150, 790, 288, 923, 804, 378, 215, 828, 592, 281, 565, 555, 710, 82, 896, 831,
        547, 261, 524, 462, 293, 465, 502, 56, 661, 821, 976, 991, 658, 869, 905, 758,
        745, 193, 768, 550, 608, 933, 378, 286, 215, 979, 792, 961, 61, 688, 793, 644,
        986, 403, 106, 366, 905, 644, 372, 567, 466, 434, 645, 210, 389, 550, 919, 135,
        780, 773, 635, 389, 707, 100, 626, 958, 165, 504, 920, 176, 193, 713, 857, 265,
        203, 50, 668, 108, 645, 990, 626, 197, 510, 357, 358, 850, 858, 364, 936, 638,
    )
}
