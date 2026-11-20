package io.github.yuroyami.archivekodec.codec.rar

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.ppmd.Ppmd7
import io.github.yuroyami.archivekodec.codec.ppmd.RangeDecoderRar
import io.github.yuroyami.archivekodec.security.DecompressionBombException

/**
 * The RAR 2.9 decompressor, which RAR 3.x and 4.x use for every method but storing: LZ77 blocks
 * with Huffman codes over a 4 MiB window, PPMd var.H blocks with RAR's range coder, and filters
 * that RAR runs on the output, as bytecode for its virtual machine or as one of its standard
 * filters (delta, audio, x86). Ported from XADMaster's XADRAR30Handle, with the permission of
 * its copyright holders; see reference/REFERENCES.md.
 *
 * One unpacker decodes one solid stream: [unpack] is called for each member in order, and the
 * window, codes, PPMd model and filters carry from one member to the next. A member's data ends
 * at an end-of-file marker, after which the stream says whether the next member starts with new
 * codes.
 */
internal class Rar29Unpacker(private val maxPpmdMemory: Long) : RarDecoder {
    private val window = ByteArray(WINDOW)
    private var position = 0L // bytes decoded into the window

    // ---- Bit input, most significant bit first ----

    private var input = ByteArray(0)
    private var bit = 0L
    private var end = 0

    private fun fail(message: String): Nothing = throw ArchiveFormatException("RAR: $message", bit ushr 3)

    private fun inputLeft(): Long = end.toLong() * 8 - bit

    private fun bits(count: Int): Int {
        if (count == 0) return 0
        if (inputLeft() < count) fail("compressed data ends early")
        var value = 0
        var left = count
        while (left > 0) {
            val byte = input[(bit ushr 3).toInt()].toInt() and 0xFF
            val offset = (bit and 7).toInt()
            val take = minOf(left, 8 - offset)
            value = (value shl take) or ((byte ushr (8 - offset - take)) and ((1 shl take) - 1))
            bit += take
            left -= take
        }
        return value
    }

    private fun byte(): Int = bits(8)

    private fun alignToByte() { bit = (bit + 7) and 7L.inv() }

    // ---- Prefix codes: canonical, shortest code first, high bit first ----

    private inner class Code(lengths: IntArray, offset: Int, symbols: Int, name: String) {
        private val firstCode = IntArray(16)
        private val count = IntArray(16)
        private val firstIndex = IntArray(16)
        private val sorted = IntArray(symbols)

        init {
            for (i in 0 until symbols) count[lengths[offset + i]]++
            count[0] = 0
            var code = 0
            var index = 0
            for (length in 1..15) {
                firstCode[length] = code
                firstIndex[length] = index
                index += count[length]
                code += count[length]
                if (code > (1 shl length)) fail("$name code is oversubscribed")
                code = code shl 1
            }
            val next = firstIndex.copyOf()
            for (i in 0 until symbols) {
                val length = lengths[offset + i]
                if (length > 0) sorted[next[length]++] = i
            }
        }

        fun decode(): Int {
            var code = 0
            for (length in 1..15) {
                code = (code shl 1) or bits(1)
                val relative = code - firstCode[length]
                if (relative < count[length]) return sorted[firstIndex[length] + relative]
            }
            fail("invalid prefix code")
        }
    }

    // ---- State ----

    private val lengthTable = IntArray(TABLE_SIZE)
    private var mainCode: Code? = null
    private var offsetCode: Code? = null
    private var lowOffsetCode: Code? = null
    private var lengthCode: Code? = null
    private var lastOffset = 0
    private var lastLength = 0
    private val oldOffsets = IntArray(4)
    private var lastLowOffset = 0
    private var lowOffsetRepeats = 0

    private var ppmBlock = false
    private var ppmd: Ppmd7? = null
    private var ppmCoder: RangeDecoderRar? = null
    private var ppmEscape = 2

    private val vm by lazy { RarVm() }
    private val programs = ArrayList<RarProgram>()
    private val filters = ArrayDeque<RarFilter>()
    private var filterStart = Long.MAX_VALUE
    private var lastFilter = 0
    private val oldFilterLength = IntArray(MAX_PROGRAMS + 1)
    private val usageCount = IntArray(MAX_PROGRAMS + 1)

    private var newTables = true
    private var newFile = false

    // ---- Output ----

    private var flushed = 0L // window position up to which output was produced
    private var out: ByteArray? = null
    private var produced = 0
    private var size = 0

    private fun emit(bytes: ByteArray, from: Int, length: Int) {
        if (length > size - produced) fail("data exceeds the declared size $size")
        out?.let { bytes.copyInto(it, produced, from, from + length) }
        produced += length
    }

    private fun emitWindow(start: Long, stop: Long) {
        var at = start
        while (at < stop) {
            val offset = (at and MASK).toInt()
            val n = minOf(stop - at, (WINDOW - offset).toLong()).toInt()
            emit(window, offset, n)
            at += n
        }
    }

    /**
     * Decode one member, `input[from, to)`, into [size] bytes. With [keep] false the output is
     * only decoded, for an earlier member of a solid stream.
     */
    override fun unpack(input: ByteArray, from: Int, to: Int, size: Int, keep: Boolean): ByteArray? {
        this.input = input
        bit = from.toLong() * 8
        end = to
        this.size = size
        produced = 0
        out = if (keep) ByteArray(size) else null
        newFile = false
        // The file position filters see starts at this member.
        val fileStart = flushed
        if (newTables) readTables()
        newTables = false
        while (produced < size) {
            if (flushed == filterStart) {
                runFilters(fileStart)
                continue
            }
            val target = minOf(filterStart, flushed + CHUNK, flushed + (size - produced).toLong())
            val reached = expand(target)
            if (reached > flushed) {
                emitWindow(flushed, reached)
                flushed = reached
            }
            if (newFile) break
            if (reached == flushed && reached < target && filterStart != flushed) fail("compressed data ends early")
        }
        if (position > flushed && filterStart > flushed) fail("data exceeds the declared size $size")
        // The end-of-file marker usually follows the last byte; read it when it is there.
        if (!newFile && produced == size) readTrailingMarker()
        if (produced != size) fail("data ends after $produced of $size bytes")
        val result = out
        out = null
        return result
    }

    /**
     * After a member's last byte: consume its end-of-file marker, if the data has one, so the
     * next member of a solid stream knows whether it starts with new codes. A member whose data
     * simply ends, as the last one may, gets new codes next time. Decoding anything but the
     * marker means the member holds more than its declared size.
     */
    private fun readTrailingMarker() {
        val savedBit = bit
        try {
            val before = position
            expand(position + 1)
            if (position > before) fail("data exceeds the declared size $size")
        } catch (error: ArchiveFormatException) {
            if (inputLeft() > 0 && error.message?.contains("exceeds the declared size") == true) throw error
            // The data ended without a marker: nothing more to read.
            if (!ppmBlock) bit = savedBit
            newTables = true
        }
    }

    // ---- Filters ----

    private fun runFilters(fileStart: Long) {
        // The filter stays queued while its block decodes, as in XADMaster, so a filter defined
        // meanwhile does not move the start marker.
        val first = filters.first()
        val start = filterStart
        val length = first.length
        val stop = start + length
        filterStart = Long.MAX_VALUE
        if (expand(stop) != stop) fail("filter block runs past the data")
        filters.removeFirst()
        copyWindow(start, length)
        first.run(vm, start - fileStart)
        var address = first.filteredAddress
        var filtered = first.filteredLength
        // Further filters over exactly the same block run on the previous one's output.
        while (filters.isNotEmpty()) {
            val next = filters.first()
            if (next.start != start || next.length != filtered) break
            vm.memory.copyInto(vm.memory, 0, address, address + filtered)
            next.run(vm, start - fileStart)
            address = next.filteredAddress
            filtered = next.filteredLength
            filters.removeFirst()
        }
        if (filters.isNotEmpty()) {
            filterStart = filters.first().start
            if (filterStart < stop) fail("filters overlap")
        }
        emit(vm.memory, address, filtered)
        flushed = stop
    }

    private fun copyWindow(start: Long, length: Int) {
        var done = 0
        while (done < length) {
            val offset = ((start + done) and MASK).toInt()
            val n = minOf(length - done, WINDOW - offset)
            window.copyInto(vm.memory, done, offset, offset + n)
            done += n
        }
    }

    private fun readFilter(next: () -> Int) {
        val flags = next()
        var length = (flags and 7) + 1
        if (length == 7) length = next() + 7
        else if (length == 8) length = (next() shl 8) or next()
        val code = ByteArray(length) { next().toByte() }
        parseFilter(code, flags)
    }

    private fun parseFilter(bytes: ByteArray, flags: Int) {
        val input = RarBits(bytes)
        var number: Int
        var isNew = false
        if (flags and 0x80 != 0) {
            number = input.vmNumber() - 1
            if (number == -1) {
                number = 0
                programs.clear()
                filters.clear()
                filterStart = Long.MAX_VALUE
            }
            if (number < 0 || number > programs.size || number > MAX_PROGRAMS) fail("invalid filter number")
            if (number == programs.size) {
                isNew = true
                oldFilterLength[number] = 0
                usageCount[number] = -1
            }
            lastFilter = number
        } else number = lastFilter
        if (number >= programs.size && !isNew) fail("filter $number is not defined")
        usageCount[number]++

        var start = (input.vmNumber().toLong() and 0xFFFFFFFFL) + position
        if (flags and 0x40 != 0) start += 258
        val length = if (flags and 0x20 != 0) input.vmNumber().also { oldFilterLength[number] = it } else oldFilterLength[number]
        if (length < 0 || length > VM_WORK_SIZE) fail("filter block of ${length.toLong() and 0xFFFFFFFFL} bytes is too large")
        if (start < flushed) fail("filter starts before the data already written")

        val registers = IntArray(8)
        registers[3] = VM_SYSTEM_GLOBAL_ADDRESS
        registers[4] = length
        registers[5] = usageCount[number]
        registers[7] = VM_MEMORY_SIZE
        if (flags and 0x10 != 0) {
            val mask = input.bits(7)
            for (i in 0 until 7) if (mask and (1 shl i) != 0) registers[i] = input.vmNumber()
        }

        val program = if (isNew) {
            val codeLength = input.vmNumber()
            if (codeLength <= 0 || codeLength > 0x10000) fail("invalid filter code length")
            val code = ByteArray(codeLength) { input.bits(8).toByte() }
            (RarProgram.parse(code) ?: fail("invalid filter program")).also { programs.add(it) }
        } else programs[number]

        var global = ByteArray(VM_SYSTEM_GLOBAL_SIZE)
        if (flags and 8 != 0) {
            val dataLength = input.vmNumber()
            if (dataLength < 0 || dataLength > VM_USER_GLOBAL_SIZE) fail("filter data is too large")
            global = ByteArray(dataLength + VM_SYSTEM_GLOBAL_SIZE)
            for (i in 0 until dataLength) global[i + VM_SYSTEM_GLOBAL_SIZE] = input.bits(8).toByte()
        }
        val filter = RarFilter(program, start, length, registers, global)
        for (i in 0 until 7) filter.setGlobal(i * 4, registers[i])
        filter.setGlobal(0x1C, length)
        filter.setGlobal(0x20, 0)
        filter.setGlobal(0x2C, usageCount[number])
        if (filters.size >= MAX_FILTERS) fail("too many pending filters")
        filters.addLast(filter)
        if (filters.size == 1) filterStart = start
    }

    // ---- Tables ----

    private fun readTables() {
        mainCode = null; offsetCode = null; lowOffsetCode = null; lengthCode = null
        alignToByte()
        ppmBlock = bits(1) == 1
        if (ppmBlock) {
            val flags = bits(7)
            var memory = 0
            if (flags and 0x20 != 0) memory = byte()
            if (flags and 0x40 != 0) ppmEscape = byte()
            if (flags and 0x20 != 0) {
                var order = (flags and 0x1F) + 1
                if (order > 16) order = 16 + (order - 16) * 3
                if (order == 1) fail("PPMd block marks the end of the data")
                val bytes = (memory + 1).toLong() shl 20
                if (bytes > maxPpmdMemory) {
                    throw DecompressionBombException("RAR: a $bytes B PPMd model exceeds the decoder memory limit $maxPpmdMemory B")
                }
                ppmd = Ppmd7(bytes.toInt(), order)
            } else if (ppmd == null) fail("PPMd block continues a model that was never started")
            ppmCoder = RangeDecoderRar { byte() }
            return
        }
        lastLowOffset = 0
        lowOffsetRepeats = 0
        if (bits(1) == 0) lengthTable.fill(0)
        val preLengths = IntArray(20)
        var i = 0
        while (i < 20) {
            val length = bits(4)
            if (length == 15) {
                val count = bits(4) + 2
                if (count == 2) preLengths[i++] = 15
                else { var j = 0; while (j < count && i < 20) { preLengths[i++] = 0; j++ } }
            } else preLengths[i++] = length
        }
        val preCode = Code(preLengths, 0, 20, "precode")
        i = 0
        while (i < TABLE_SIZE) {
            val value = preCode.decode()
            when {
                value < 16 -> { lengthTable[i] = (lengthTable[i] + value) and 15; i++ }
                value < 18 -> {
                    if (i == 0) fail("length table repeats before its first length")
                    val n = if (value == 16) bits(3) + 3 else bits(7) + 11
                    var j = 0
                    while (j < n && i < TABLE_SIZE) { lengthTable[i] = lengthTable[i - 1]; i++; j++ }
                }
                else -> {
                    val n = if (value == 18) bits(3) + 3 else bits(7) + 11
                    var j = 0
                    while (j < n && i < TABLE_SIZE) { lengthTable[i++] = 0; j++ }
                }
            }
        }
        mainCode = Code(lengthTable, 0, MAIN_SYMBOLS, "main")
        offsetCode = Code(lengthTable, MAIN_SYMBOLS, OFFSET_SYMBOLS, "offset")
        lowOffsetCode = Code(lengthTable, MAIN_SYMBOLS + OFFSET_SYMBOLS, LOW_OFFSET_SYMBOLS, "low offset")
        lengthCode = Code(lengthTable, MAIN_SYMBOLS + OFFSET_SYMBOLS + LOW_OFFSET_SYMBOLS, LENGTH_SYMBOLS, "length")
    }

    // ---- Decoding ----

    private fun literal(value: Int) {
        window[(position and MASK).toInt()] = value.toByte()
        position++
    }

    private fun match(offset: Int, length: Int) {
        if (offset <= 0) fail("invalid match distance")
        var from = position - offset
        repeat(length) {
            window[(position and MASK).toInt()] = window[(from and MASK).toInt()]
            position++
            from++
        }
    }

    /**
     * Decode until the window reaches [target], a filter's start, or an end-of-file marker.
     * Returns how far output may go: never past [target] or the filter's start, though a match
     * may have decoded beyond them, for the next call to use.
     */
    private fun expand(target: Long): Long {
        var stop = minOf(target, filterStart)
        while (position < stop) {
            if (newFile) return minOf(position, stop)
            if (ppmBlock) {
                val model = ppmd!!
                val coder = ppmCoder!!
                val symbol = model.decodeSymbol(coder)
                if (symbol < 0) fail("PPMd data is corrupt")
                if (symbol != ppmEscape) { literal(symbol); continue }
                when (model.decodeSymbol(coder)) {
                    0 -> readTables()
                    2 -> { newFile = true; newTables = true; return minOf(position, stop) }
                    3 -> { readFilter { model.decodeSymbol(coder).also { if (it < 0) fail("PPMd data is corrupt") } }; stop = minOf(stop, filterStart) }
                    4 -> {
                        var offset = 0
                        repeat(3) { offset = (offset shl 8) or ppmByte(model, coder) }
                        val length = ppmByte(model, coder)
                        match(offset + 2, length + 32)
                    }
                    5 -> match(1, ppmByte(model, coder) + 4)
                    -1 -> fail("PPMd data is corrupt")
                    else -> literal(symbol)
                }
                continue
            }
            val main = mainCode ?: fail("no codes before the compressed data")
            val symbol = main.decode()
            var offset: Int
            var length: Int
            when {
                symbol < 256 -> { literal(symbol); continue }
                symbol == 256 -> {
                    if (bits(1) == 0) {
                        newFile = true
                        newTables = bits(1) == 1
                        return minOf(position, stop)
                    }
                    readTables()
                    continue
                }
                symbol == 257 -> { readFilter { bits(8) }; stop = minOf(stop, filterStart); continue }
                symbol == 258 -> {
                    if (lastLength == 0) continue
                    offset = lastOffset
                    length = lastLength
                }
                symbol <= 262 -> {
                    val index = symbol - 259
                    offset = oldOffsets[index]
                    val lengthSymbol = lengthCode!!.decode()
                    length = LENGTH_BASES[lengthSymbol] + 2 + bits(LENGTH_BITS[lengthSymbol])
                    for (i in index downTo 1) oldOffsets[i] = oldOffsets[i - 1]
                    oldOffsets[0] = offset
                }
                symbol <= 270 -> {
                    offset = SHORT_BASES[symbol - 263] + 1 + bits(SHORT_BITS[symbol - 263])
                    length = 2
                    for (i in 3 downTo 1) oldOffsets[i] = oldOffsets[i - 1]
                    oldOffsets[0] = offset
                }
                else -> {
                    if (symbol - 271 >= LENGTH_BASES.size) fail("invalid length symbol")
                    length = LENGTH_BASES[symbol - 271] + 3 + bits(LENGTH_BITS[symbol - 271])
                    val offsetSymbol = offsetCode!!.decode()
                    offset = OFFSET_BASES[offsetSymbol] + 1
                    val extra = OFFSET_BITS[offsetSymbol]
                    if (extra > 0) {
                        if (offsetSymbol > 9) {
                            if (extra > 4) offset += bits(extra - 4) shl 4
                            if (lowOffsetRepeats > 0) {
                                lowOffsetRepeats--
                                offset += lastLowOffset
                            } else {
                                val low = lowOffsetCode!!.decode()
                                if (low == 16) {
                                    lowOffsetRepeats = 15
                                    offset += lastLowOffset
                                } else {
                                    offset += low
                                    lastLowOffset = low
                                }
                            }
                        } else offset += bits(extra)
                    }
                    if (offset >= 0x40000) length++
                    if (offset >= 0x2000) length++
                    for (i in 3 downTo 1) oldOffsets[i] = oldOffsets[i - 1]
                    oldOffsets[0] = offset
                }
            }
            lastOffset = offset
            lastLength = length
            match(offset, length)
        }
        return minOf(position, stop)
    }

    private fun ppmByte(model: Ppmd7, coder: RangeDecoderRar): Int {
        val value = model.decodeSymbol(coder)
        if (value < 0) fail("PPMd data is corrupt")
        return value
    }

    private companion object {
        const val WINDOW = 0x400000
        const val MASK = (WINDOW - 1).toLong()
        const val CHUNK = 0x40000L
        const val MAIN_SYMBOLS = 299
        const val OFFSET_SYMBOLS = 60
        const val LOW_OFFSET_SYMBOLS = 17
        const val LENGTH_SYMBOLS = 28
        const val TABLE_SIZE = MAIN_SYMBOLS + OFFSET_SYMBOLS + LOW_OFFSET_SYMBOLS + LENGTH_SYMBOLS
        const val MAX_PROGRAMS = 1024
        const val MAX_FILTERS = 8192

        val LENGTH_BASES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224)
        val LENGTH_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5)
        val OFFSET_BASES = intArrayOf(
            0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096,
            6144, 8192, 12288, 16384, 24576, 32768, 49152, 65536, 98304, 131072, 196608, 262144, 327680, 393216, 458752,
            524288, 589824, 655360, 720896, 786432, 851968, 917504, 983040, 1048576, 1310720, 1572864, 1835008, 2097152,
            2359296, 2621440, 2883584, 3145728, 3407872, 3670016, 3932160,
        )
        val OFFSET_BITS = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15,
            16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18,
        )
        val SHORT_BASES = intArrayOf(0, 4, 8, 16, 32, 64, 128, 192)
        val SHORT_BITS = intArrayOf(2, 2, 3, 4, 5, 6, 6, 6)
    }
}
