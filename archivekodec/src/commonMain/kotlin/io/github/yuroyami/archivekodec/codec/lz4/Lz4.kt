package io.github.yuroyami.archivekodec.codec.lz4

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.byteArrayCapacity
import io.github.yuroyami.archivekodec.util.le16
import io.github.yuroyami.archivekodec.util.le32
import io.github.yuroyami.archivekodec.util.readI32Le
import io.github.yuroyami.archivekodec.util.readI64Le
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le

/**
 * LZ4: block format (the LZ77 token codec) + frame format (`.lz4`, magic
 * `0x184D2204`). Implemented in pure Kotlin, with no entropy stage.
 *
 * Frame decoding supports independent and linked blocks, consumes concatenated frames
 * and skippable metadata, and verifies each
 * frame's declared content size, header and advertised block/content xxHash32 checksums.
 * Encoder writes a minimal frame (block-independent,
 * 4 MiB max block, no optional checksums) with a greedy block compressor that keeps
 * one match candidate per hash-table slot, skips more input after repeated misses, and
 * a stored-block fallback when a block doesn't shrink.
 */
public object Lz4 {

    private const val MAGIC = 0x184D2204L
    private const val LEGACY_MAGIC = 0x184C2102L
    private const val SKIPPABLE_FIRST = 0x184D2A50L
    private const val SKIPPABLE_LAST = 0x184D2A5FL
    private const val MIN_MATCH = 4
    private const val MF_LIMIT = 12          // last 12 bytes are never the start of a match
    private const val LAST_LITERALS = 5      // last 5 bytes are always literals
    private const val MAX_BLOCK = 4 * 1024 * 1024
    private const val HISTORY_WINDOW = 64 * 1024
    private const val HASH_BITS = 16

    /* ─── Block format ───────────────────────────────────────────────────── */

    /**
     * Decompress one LZ4 block in `src[sOff, sEnd)`. [expectedSize] is a reservation hint.
     * A final literal sequence is required; after a match it must contain at least five bytes.
     * Empty output is encoded by a zero token, rather than an empty byte sequence.
     */
    @Throws(ArchiveException::class)
    internal fun blockDecompress(src: ByteArray, sOff: Int, sEnd: Int, expectedSize: Int = -1): ByteArray =
        decodeBlock(src, sOff, sEnd, expectedSize, Int.MAX_VALUE, ArchiveLimits.UNBOUNDED, 0, sEnd - sOff)

    private fun decodeBlock(
        src: ByteArray, sOff: Int, sEnd: Int, expectedSize: Int,
        blockMaximum: Int, limits: ArchiveLimits, outputBase: Int, compressedBytes: Int,
        declaredSize: Long? = null, dictionaryId: Long? = null,
        frameOutputBase: Int = outputBase, sizeOffset: Long = 6,
        history: ByteArrayBuilder? = null,
    ): ByteArray {
        if (sOff < 0 || sEnd < sOff || sEnd > src.size) {
            throw ArchiveFormatException("lz4: invalid block slice [$sOff, $sEnd)", sOff.toLong())
        }
        limits.checkOutputSize(outputBase.toLong())
        limits.checkRatio(compressedBytes.toLong(), frameOutputBase.toLong())
        val ratioBudget = if (compressedBytes > 0) {
            compressedBytes.toLong() * limits.maxCompressionRatio - frameOutputBase
        } else Long.MAX_VALUE
        val budget = minOf(
            blockMaximum.toLong(), limits.maxUncompressedBytes - outputBase,
            ratioBudget, (Int.MAX_VALUE - outputBase).toLong(),
        ).toInt()
        // A size hint never triggers a large reservation before decoding any input.
        val hint = if (expectedSize > 0) expectedSize else 64
        var out = ByteArray(minOf(4096, hint, budget))
        var o = 0
        var s = sOff
        var hasMatch = false
        val historySize = if (history != null) minOf(frameOutputBase, HISTORY_WINDOW) else 0
        fun needInput(n: Int) {
            if (n > sEnd - s) throw ArchiveFormatException("lz4: truncated block", s.toLong())
        }
        fun length(base: Int): Int {
            var n = base.toLong()
            if (base == 15) {
                var b: Int
                do {
                    needInput(1)
                    b = src[s++].toInt() and 0xFF
                    n += b
                    if (n > Int.MAX_VALUE) throw ArchiveFormatException("lz4: length exceeds ByteArray limit", s.toLong())
                } while (b == 255)
            }
            return n.toInt()
        }
        fun ensure(n: Int) {
            val required = o.toLong() + n
            if (required > blockMaximum) {
                throw ArchiveFormatException("lz4: decoded block exceeds maximum $blockMaximum", s.toLong())
            }
            checkContentSize(frameOutputBase.toLong() + required, declaredSize, sizeOffset)
            limits.checkOutputSize(outputBase.toLong() + required)
            limits.checkRatio(compressedBytes.toLong(), frameOutputBase.toLong() + required)
            val capacity = byteArrayCapacity(out.size, o, n, budget)
            if (capacity != out.size) out = out.copyOf(capacity)
        }
        while (s < sEnd) {
            val tokenAt = s
            val token = src[s++].toInt() and 0xFF
            val litLen = length(token ushr 4)
            needInput(litLen)
            if (litLen == sEnd - s && hasMatch && litLen < LAST_LITERALS) {
                throw ArchiveFormatException("lz4: final literals after a match must contain at least $LAST_LITERALS bytes", tokenAt.toLong())
            }
            ensure(litLen)
            src.copyInto(out, o, s, s + litLen)
            o += litLen
            s += litLen
            if (s == sEnd) return out.copyOf(o) // last sequence: literals only

            needInput(2)
            val offset = readU16Le(src, s)
            s += 2
            if (offset == 0) throw ArchiveFormatException("lz4: invalid match offset 0", (s - 2).toLong())
            val availableHistory = o.toLong() + historySize
            if (offset > availableHistory) {
                val message = "lz4: match offset $offset exceeds available history $availableHistory"
                if (dictionaryId != null) {
                    throw ArchiveUnsupportedException("$message; dictionary ID $dictionaryId is not supplied", (s - 2).toLong())
                }
                // Dictionary IDs are optional. Missing history does not establish
                // whether an untagged block needs a dictionary or is malformed.
                throw ArchiveFormatException("$message; an external dictionary may be required", (s - 2).toLong())
            }
            val encodedLength = length(token and 0xF)
            if (encodedLength > Int.MAX_VALUE - MIN_MATCH) throw ArchiveFormatException("lz4: match length exceeds ByteArray limit", s.toLong())
            val matchLen = encodedLength + MIN_MATCH
            ensure(matchLen)
            var m = o - offset
            val historyLength = minOf(matchLen, maxOf(0, -m))
            if (historyLength > 0) {
                // Read only preceding bytes in this frame's bounded history window.
                // The builder owns the current storage even after its buffer grows.
                history!!.copyTo(out, o, outputBase + m, historyLength)
                o += historyLength
                m += historyLength
            }
            repeat(matchLen - historyLength) { out[o++] = out[m++] } // byte-by-byte: overlap-safe
            hasMatch = true
        }
        throw ArchiveFormatException("lz4: missing final literal sequence", sEnd.toLong())
    }

    /** Greedy LZ4 block compressor over a slice. Always produces a conformant block. */
    @Throws(ArchiveException::class)
    internal fun blockCompress(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= data.size && length <= data.size - offset) {
            "lz4: invalid input slice"
        }
        val end = offset + length
        val out = ByteArrayBuilder(maxOf(16, length / 2))
        if (length < MF_LIMIT + 1) {
            emitLastLiterals(out, data, offset, end)
            return out.toByteArray()
        }
        val table = IntArray(1 shl HASH_BITS) { -1 }
        val mfLimit = end - MF_LIMIT
        val matchLimit = end - LAST_LITERALS
        var anchor = offset
        var i = offset
        var misses = 0
        while (i < mfLimit) {
            val h = hash(readI32Le(data, i))
            val ref = table[h]
            table[h] = i
            if (ref >= 0 && i - ref <= 0xFFFF && readI32Le(data, ref) == readI32Le(data, i)) {
                val len = matchLength(data, ref, i, matchLimit - i)
                emitSequence(out, data, anchor, i, i - ref, len)
                i += len
                anchor = i
                misses = 0
            } else {
                misses++
                // Probe every byte near matches, then cross incompressible runs
                // with a growing step. Cap the addition at the search boundary.
                val step = 1 + (misses ushr 6)
                i += minOf(step, mfLimit - i)
            }
        }
        emitLastLiterals(out, data, anchor, end)
        return out.toByteArray()
    }

    /** The first four bytes already match; the final five literals are outside [maximum]. */
    private fun matchLength(data: ByteArray, first: Int, second: Int, maximum: Int): Int {
        var length = MIN_MATCH
        while (length <= maximum - 4) {
            val different = readI32Le(data, first + length) xor readI32Le(data, second + length)
            if (different != 0) {
                // Little-endian words put the first differing byte in the low bits.
                return length + (different.countTrailingZeroBits() ushr 3)
            }
            length += 4
        }
        while (length < maximum && data[first + length] == data[second + length]) length++
        return length
    }

    private fun emitSequence(out: ByteArrayBuilder, data: ByteArray, anchor: Int, matchPos: Int, offset: Int, matchLen: Int) {
        val litLen = matchPos - anchor
        val mlen = matchLen - MIN_MATCH
        val token = ((if (litLen >= 15) 15 else litLen) shl 4) or (if (mlen >= 15) 15 else mlen)
        out.append(token.toByte())
        writeLength(out, litLen)
        out.append(data, anchor, litLen)
        out.le16(offset)
        writeLength(out, mlen)
    }

    private fun emitLastLiterals(out: ByteArrayBuilder, data: ByteArray, anchor: Int, end: Int) {
        val litLen = end - anchor
        val token = (if (litLen >= 15) 15 else litLen) shl 4
        out.append(token.toByte())
        writeLength(out, litLen)
        out.append(data, anchor, litLen)
    }

    /** Write the 255-chunked extension bytes for a length >= 15. */
    private fun writeLength(out: ByteArrayBuilder, length: Int) {
        if (length < 15) return
        var r = length - 15
        while (r >= 255) { out.append(255.toByte()); r -= 255 }
        out.append(r.toByte())
    }

    private fun hash(v: Int): Int = (v * -1640531535) ushr (32 - HASH_BITS)

    /* ─── Frame format (.lz4) ────────────────────────────────────────────── */

    /**
     * Compress [data] into a complete LZ4 frame (no optional checksums, 4 MiB blocks).
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun frameCompress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(maxOf(32, data.size / 2))
        out.le32(MAGIC)
        val flg = 0x60 // version=01, block-independent
        val bd = 0x70  // 4 MiB max block size
        out.append(flg.toByte())
        out.append(bd.toByte())
        val hc = (XxHash32.hash(byteArrayOf(flg.toByte(), bd.toByte())).toInt() ushr 8) and 0xFF
        out.append(hc.toByte())

        var pos = 0
        while (pos < data.size) {
            val chunkLength = minOf(data.size - pos, MAX_BLOCK)
            val comp = blockCompress(data, pos, chunkLength)
            if (comp.size < chunkLength) {
                out.le32(comp.size.toLong())
                out.append(comp)
            } else {
                out.le32(chunkLength.toLong() or 0x80000000L) // high bit = uncompressed
                out.append(data, pos, chunkLength)
            }
            pos += chunkLength
        }
        out.le32(0L) // end mark
        return out.toByteArray()
    }

    /**
     * Decompress the complete sequence of standard and skippable LZ4 frames.
     * Skippable payloads produce no output; malformed or truncated trailing bytes fail.
     * The combined output obeys [limits]; the compression ratio is bounded for each
     * standard frame using its encoded length, excluding other frames and metadata.
     * Each optional content size is checked before growth and must match that frame's
     * output. Version and reserved bits are validated. Dictionary-tagged self-contained
     * blocks remain readable. Linked blocks can refer to the preceding 64 KiB of
     * this frame's output; independent blocks have no preceding-block history.
     * A match requiring unavailable dictionary history raises
     * [ArchiveUnsupportedException] with the declared ID. An untagged missing-history
     * match remains malformed, since an external dictionary cannot be inferred reliably.
     * Legacy frames raise [ArchiveUnsupportedException]. Empty input is malformed.
     *
     * @throws ArchiveFormatException for malformed frames, invalid matches or failed size/checksum validation.
     * @throws ArchiveUnsupportedException for legacy frames or unavailable declared dictionary history.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if an output or compression-ratio limit is exceeded.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun frameDecompress(input: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray {
        var p = 0
        fun needInput(n: Int) {
            if (n > input.size - p) throw ArchiveFormatException("lz4: truncated frame", p.toLong())
        }
        fun nextU32(): Long {
            needInput(4)
            val v = readU32Le(input, p)
            p += 4
            return v
        }
        needInput(4) // Empty input is not an encoded empty frame.
        limits.checkOutputSize(0)
        limits.checkRatio(input.size.toLong(), 0)
        val maximum = minOf(Int.MAX_VALUE.toLong(), limits.maxUncompressedBytes).toInt()
        val out = ByteArrayBuilder(minOf(64, maximum), maximum)
        while (p < input.size) {
            val frameStart = p
            when (val magic = nextU32()) {
                in SKIPPABLE_FIRST..SKIPPABLE_LAST -> {
                    val payloadSize = nextU32()
                    // Compare the unsigned length before converting to Int or adding it.
                    if (payloadSize > input.size - p) {
                        throw ArchiveFormatException("lz4: truncated skippable payload", p.toLong())
                    }
                    p += payloadSize.toInt()
                    continue
                }
                LEGACY_MAGIC -> throw ArchiveUnsupportedException("lz4: legacy frame format is not supported", frameStart.toLong())
                MAGIC -> Unit
                else -> throw ArchiveFormatException("lz4: unrecognized frame magic 0x${magic.toString(16)}", frameStart.toLong())
            }
            val descriptorStart = p
            needInput(2)
            val flg = input[p++].toInt() and 0xFF
            val bd = input[p++].toInt() and 0xFF
            if (flg ushr 6 != 1) throw ArchiveFormatException("lz4: invalid frame version ${flg ushr 6}", descriptorStart.toLong())
            if (flg and 0x02 != 0) throw ArchiveFormatException("lz4: reserved FLG bit is set", descriptorStart.toLong())
            val blockMaximum = when ((bd ushr 4) and 7) {
                4 -> 64 * 1024
                5 -> 256 * 1024
                6 -> 1024 * 1024
                7 -> MAX_BLOCK
                else -> throw ArchiveFormatException("lz4: invalid block maximum descriptor", (p - 1).toLong())
            }
            if (bd and 0x8F != 0) throw ArchiveFormatException("lz4: reserved BD bits are set", (p - 1).toLong())
            val blockChecksum = (flg and 0x10) != 0
            val linkedBlocks = (flg and 0x20) == 0
            val contentSize = (flg and 0x08) != 0
            val contentChecksum = (flg and 0x04) != 0
            val dictId = (flg and 0x01) != 0
            val optional = (if (contentSize) 8 else 0) + (if (dictId) 4 else 0)
            needInput(optional)
            val unsignedSize = if (contentSize) {
                val size = readI64Le(input, p).toULong()
                p += 8
                size
            } else null
            val dictionaryId = if (dictId) nextU32() else null
            needInput(1)
            val headerChecksum = (XxHash32.hash(input, descriptorStart, p - descriptorStart) ushr 8).toInt() and 0xFF
            if (headerChecksum != (input[p].toInt() and 0xFF)) {
                throw ArchiveFormatException("lz4: header checksum mismatch", p.toLong())
            }
            p++

            val outputStart = out.size
            val sizeOffset = frameStart.toLong() + 6
            val declaredSize = unsignedSize?.let { size ->
                // Previous output consumes the shared budget, even in another frame.
                // Unsigned comparison also rejects declarations above Long.MAX_VALUE.
                val remaining = limits.maxUncompressedBytes - outputStart
                if (size > remaining.toULong()) {
                    throw DecompressionBombException(
                        "declared frame output $size B exceeds remaining output limit $remaining B", sizeOffset, null, null,
                    )
                }
                val signedSize = size.toLong()
                // Remaining input is an upper bound until this frame's end is known.
                // An impossible declaration can fail before even reading its blocks.
                limits.checkRatio((input.size - frameStart).toLong(), signedSize)
                if (size > (Int.MAX_VALUE - outputStart).toULong()) {
                    throw ArchiveFormatException("lz4: declared content size $size exceeds remaining ByteArray limit", sizeOffset)
                }
                signedSize
            }
            // Scan only framing, without payload copies or per-block allocations. This
            // supplies the exact ratio denominator before any block can expand output.
            val frameEnd = findFrameEnd(input, p, blockMaximum, blockChecksum, contentChecksum)
            val compressedBytes = frameEnd - frameStart
            if (declaredSize != null) limits.checkRatio(compressedBytes.toLong(), declaredSize)
            while (true) {
                val raw = nextU32()
                if (raw == 0L) break // end mark, distinct from an empty stored block
                val uncompressed = (raw and 0x80000000L) != 0L
                val blockSize = (raw and 0x7FFFFFFFL).toInt()
                if (blockSize > blockMaximum) throw ArchiveFormatException("lz4: block size $blockSize exceeds maximum $blockMaximum", (p - 4).toLong())
                needInput(blockSize)
                if (blockChecksum) {
                    val checksumAt = p + blockSize
                    if (input.size - checksumAt < 4) {
                        throw ArchiveFormatException("lz4: truncated block checksum", checksumAt.toLong())
                    }
                    val expected = readU32Le(input, checksumAt)
                    if (XxHash32.hash(input, p, blockSize) != expected) {
                        throw ArchiveFormatException("lz4: block checksum mismatch", checksumAt.toLong())
                    }
                }
                val frameOutput = out.size - outputStart
                if (uncompressed) {
                    checkContentSize(frameOutput.toLong() + blockSize, declaredSize, sizeOffset)
                    limits.checkOutputSize(out.size.toLong() + blockSize)
                    limits.checkRatio(compressedBytes.toLong(), frameOutput.toLong() + blockSize)
                    out.append(input, p, blockSize)
                } else {
                    out.append(decodeBlock(
                        input, p, p + blockSize, -1, blockMaximum, limits, out.size,
                        compressedBytes, declaredSize, dictionaryId, frameOutput, sizeOffset,
                        history = if (linkedBlocks) out else null,
                    ))
                }
                p += blockSize
                if (blockChecksum) p += 4
            }
            val frameOutput = out.size - outputStart
            if (declaredSize != null && frameOutput.toLong() != declaredSize) {
                throw contentSizeMismatch(frameOutput.toLong(), declaredSize, sizeOffset)
            }
            val checksumAt = p
            val expectedContent = if (contentChecksum) nextU32() else null
            if (expectedContent != null && out.checksum { bytes, size ->
                    XxHash32.hash(bytes, outputStart, size - outputStart)
                } != expectedContent) {
                throw ArchiveFormatException("lz4: content checksum mismatch", checksumAt.toLong())
            }
        }
        return out.toByteArray()
    }

    private fun findFrameEnd(
        input: ByteArray, blocksStart: Int, blockMaximum: Int,
        blockChecksum: Boolean, contentChecksum: Boolean,
    ): Int {
        var p = blocksStart
        fun needInput(n: Int, field: String = "frame") {
            if (n > input.size - p) throw ArchiveFormatException("lz4: truncated $field", p.toLong())
        }
        while (true) {
            needInput(4)
            val raw = readU32Le(input, p)
            p += 4
            if (raw == 0L) {
                if (contentChecksum) {
                    needInput(4, "content checksum")
                    p += 4
                }
                return p
            }
            val blockSize = (raw and 0x7FFFFFFFL).toInt()
            if (blockSize > blockMaximum) throw ArchiveFormatException("lz4: block size $blockSize exceeds maximum $blockMaximum", (p - 4).toLong())
            needInput(blockSize)
            p += blockSize
            if (blockChecksum) {
                needInput(4, "block checksum")
                p += 4
            }
        }
    }

    private fun checkContentSize(required: Long, declared: Long?, offset: Long) {
        if (declared != null && required > declared) throw contentSizeMismatch(required, declared, offset)
    }

    private fun contentSizeMismatch(required: Long, declared: Long, offset: Long) =
        ArchiveFormatException("lz4: content size mismatch: required $required B, declared $declared B", offset)
}
