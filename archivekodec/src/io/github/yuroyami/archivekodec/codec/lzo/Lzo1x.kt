package io.github.yuroyami.archivekodec.codec.lzo

import io.github.yuroyami.archivekodec.ArchiveFormatException

/**
 * LZO1X decoder, for every LZO1X compression level (they share one format), as SquashFS
 * stores LZO blocks. Each instruction byte is a literal run or a match of one of four kinds
 * (M1 to M4) whose low two bits carry up to three literals that follow it; runs of zero bytes
 * extend long lengths, and the M4 match with offset zero ends the stream.
 */
internal object Lzo1x {
    private const val M2_MAX_OFFSET = 0x0800

    /** Decode [input] from [from] to [to] into at most [size] bytes. */
    fun decode(input: ByteArray, from: Int, to: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var ip = from
        var op = 0

        fun fail(message: String): Nothing = throw ArchiveFormatException("LZO: $message", ip.toLong())
        fun byte(): Int {
            if (ip >= to) fail("compressed data ends early")
            return input[ip++].toInt() and 0xFF
        }
        fun literals(count: Int) {
            if (count > to - ip) fail("literal run passes the end of the data")
            if (count > size - op) fail("literal run passes the end of the output")
            input.copyInto(out, op, ip, ip + count)
            ip += count
            op += count
        }
        fun copy(distance: Int, length: Int) {
            if (distance <= 0 || distance > op) fail("match distance $distance reaches before the output")
            if (length > size - op) fail("match passes the end of the output")
            var source = op - distance
            repeat(length) { out[op++] = out[source++] }
        }
        /** A length continued in zero bytes, each worth 255, then a final nonzero byte. */
        fun extended(base: Int): Int {
            var zeros = 0
            var b = byte()
            while (b == 0) {
                if (++zeros > MAX_ZERO_BYTES) fail("length is too long")
                b = byte()
            }
            return base + zeros * 255 + b
        }

        // "state": literals copied after the last instruction (0, 1 to 3, or 4 for a longer run).
        var state = 0
        var t = byte()
        if (t > 17) {
            val run = t - 17
            literals(run)
            state = if (run < 4) run else 4
            t = byte()
        }
        while (true) {
            var next: Int
            if (t < 16) {
                when (state) {
                    0 -> {
                        val run = (if (t == 0) extended(15) else t) + 3
                        literals(run)
                        state = 4
                        t = byte()
                        continue
                    }
                    4 -> {
                        // A three-byte match within 3 KiB, right after a long literal run.
                        next = t and 3
                        copy(1 + M2_MAX_OFFSET + (t ushr 2) + (byte() shl 2), 3)
                    }
                    else -> {
                        // A two-byte match within 1 KiB, right after a short literal run.
                        next = t and 3
                        copy(1 + (t ushr 2) + (byte() shl 2), 2)
                    }
                }
            } else if (t >= 64) {
                next = t and 3
                val distance = 1 + ((t ushr 2) and 7) + (byte() shl 3)
                copy(distance, (t ushr 5) + 1)
            } else if (t >= 32) {
                val length = (if (t and 31 == 0) extended(31) else t and 31) + 2
                val low = byte()
                val word = low or (byte() shl 8)
                next = word and 3
                copy(1 + (word ushr 2), length)
            } else {
                val length = (if (t and 7 == 0) extended(7) else t and 7) + 2
                val low = byte()
                val word = low or (byte() shl 8)
                next = word and 3
                val distance = ((t and 8) shl 11) + (word ushr 2)
                if (distance == 0) {
                    if (length != 3) fail("end marker has length $length")
                    if (ip != to) fail("${to - ip} bytes follow the end marker")
                    return if (op == size) out else out.copyOf(op)
                }
                copy(distance + 0x4000, length)
            }
            literals(next)
            state = next
            t = byte()
        }
    }

    /**
     * Encode [data] as one LZO1X stream that [decode] and liblzo read: greedy matches from a
     * hash of three bytes, written as M2 (up to 8 bytes within 2 KiB), M3 (within 16 KiB) or M4
     * (within 48 KiB) instructions, never the short M1 forms, and the end marker.
     */
    fun encode(data: ByteArray): ByteArray {
        val out = io.github.yuroyami.archivekodec.util.ByteArrayBuilder(maxOf(64, data.size / 2 + 16))
        fun extendedLength(rest: Int) {
            var remaining = rest
            while (remaining > 255) { out.append(0); remaining -= 255 }
            out.append(remaining.toByte())
        }
        val head = IntArray(1 shl 14) { -1 }
        fun hash(i: Int): Int {
            val v = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8) or ((data[i + 2].toInt() and 0xFF) shl 16)
            return (v * -0x61c88647) ushr 18
        }
        // A match waits until the literal count after it is known: its last bits carry 0 to 3.
        var pendingLength = 0
        var pendingDistance = 0
        var literalStart = 0
        var first = true

        fun writeLiterals(end: Int) {
            val run = end - literalStart
            if (run == 0) return
            if (first && run <= 238) {
                out.append((17 + run).toByte())
            } else if (run <= 3 && !first) {
                // Already counted in the previous match's last bits.
            } else if (run <= 18) {
                out.append((run - 3).toByte())
            } else {
                out.append(0)
                extendedLength(run - 18)
            }
            out.append(data, literalStart, run)
        }

        fun writeMatch(length: Int, distance: Int, next: Int) {
            when {
                length <= 8 && distance <= M2_MAX_OFFSET -> {
                    val d = distance - 1
                    out.append((((length - 1) shl 5) or ((d and 7) shl 2) or next).toByte())
                    out.append((d ushr 3).toByte())
                    return
                }
                distance <= 0x4000 -> {
                    if (length - 2 <= 31) out.append((32 or (length - 2)).toByte()) else { out.append(32); extendedLength(length - 2 - 31) }
                    val word = ((distance - 1) shl 2) or next
                    out.append(word.toByte()); out.append((word ushr 8).toByte())
                }
                else -> {
                    val d = distance - 0x4000
                    val high = (d ushr 11) and 8
                    if (length - 2 <= 7) out.append((16 or high or (length - 2)).toByte()) else { out.append((16 or high).toByte()); extendedLength(length - 2 - 7) }
                    val word = ((d and 0x3FFF) shl 2) or next
                    out.append(word.toByte()); out.append((word ushr 8).toByte())
                }
            }
        }

        fun flushPending(literalsAfter: Int) {
            if (pendingLength == 0) return
            writeMatch(pendingLength, pendingDistance, if (literalsAfter in 1..3) literalsAfter else 0)
            pendingLength = 0
        }

        var i = 0
        val limit = data.size - 3
        while (i <= limit) {
            val h = hash(i)
            val candidate = head[h]
            head[h] = i
            val distance = i - candidate
            if (candidate >= 0 && distance <= 0xBFFF && data[candidate] == data[i] && data[candidate + 1] == data[i + 1] && data[candidate + 2] == data[i + 2]) {
                var length = 3
                while (i + length < data.size && data[candidate + length] == data[i + length]) length++
                // A match needs literals before it the first time, since no history exists yet.
                val run = i - literalStart
                if (first && run == 0) { i++; continue }
                flushPending(run)
                writeLiterals(i)
                first = false
                pendingLength = length
                pendingDistance = distance
                i += length
                literalStart = i
            } else {
                i++
            }
        }
        val tail = data.size - literalStart
        flushPending(tail)
        writeLiterals(data.size)
        // The end marker: an M4 match of length 3 at distance 0.
        out.append(0x11); out.append(0); out.append(0)
        return out.takeByteArray()
    }

    /** Zero bytes allowed in one length, so a hostile stream cannot spin on them. */
    private const val MAX_ZERO_BYTES = 1 shl 20
}
