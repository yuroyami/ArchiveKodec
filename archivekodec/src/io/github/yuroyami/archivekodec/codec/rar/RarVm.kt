package io.github.yuroyami.archivekodec.codec.rar

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.Crc32

/*
 * The RAR 2.9 virtual machine, which runs the filters a RAR 3.x or 4.x archive stores as
 * bytecode, and the standard filters RAR ships, recognized by their bytecode and run natively.
 * Ported from XADMaster (RARVirtualMachine.c, XADRARVirtualMachine.m, XADRARFilters.m and
 * RARAudioDecoder.c), with the permission of its copyright holders; see
 * reference/REFERENCES.md. Unlike the original, a program runs under an instruction budget.
 */

internal const val VM_MEMORY_SIZE = 0x40000
internal const val VM_MEMORY_MASK = VM_MEMORY_SIZE - 1
internal const val VM_WORK_SIZE = 0x3C000
internal const val VM_GLOBAL_SIZE = 0x2000
internal const val VM_SYSTEM_GLOBAL_ADDRESS = VM_WORK_SIZE
internal const val VM_SYSTEM_GLOBAL_SIZE = 64
internal const val VM_USER_GLOBAL_ADDRESS = VM_SYSTEM_GLOBAL_ADDRESS + VM_SYSTEM_GLOBAL_SIZE
internal const val VM_USER_GLOBAL_SIZE = VM_GLOBAL_SIZE - VM_SYSTEM_GLOBAL_SIZE

/** A bit reader over filter bytes, most significant bit first, failing past the end. */
internal class RarBits(private val bytes: ByteArray, private val from: Int = 0, private val to: Int = bytes.size) {
    var position = from.toLong() * 8
        private set

    val bitsLeft: Long get() = to.toLong() * 8 - position

    fun bits(count: Int): Int {
        if (count == 0) return 0
        if (position + count > to.toLong() * 8) throw ArchiveFormatException("RAR: filter data ends early")
        var value = 0
        for (i in 0 until count) {
            val p = position + i
            value = (value shl 1) or ((bytes[(p ushr 3).toInt()].toInt() ushr (7 - (p and 7).toInt())) and 1)
        }
        position += count
        return value
    }

    fun bit(): Int = bits(1)

    /** A RAR VM number: 2 bits select 4, 8 (or a negative 12-bit), 16 or 32 bits. */
    fun vmNumber(): Int = when (bits(2)) {
        0 -> bits(4)
        1 -> {
            val value = bits(8)
            if (value >= 16) value else (0xFFFFFF00.toInt() or (value shl 4) or bits(4))
        }
        2 -> bits(16)
        else -> (bits(16) shl 16) or bits(16)
    }
}

/** One decoded instruction: opcode, byte mode and two operands as addressing mode and value. */
internal class RarOpcode(val instruction: Int, val byteMode: Boolean) {
    var mode1 = 0
    var value1 = 0
    var mode2 = 0
    var value2 = 0
}

/** A filter program: its instructions, static data, and the global data it keeps between runs. */
internal class RarProgram private constructor(
    val opcodes: Array<RarOpcode>, val staticData: ByteArray?, val fingerprint: Long,
) {
    var globalBackup = ByteArray(0)

    companion object {
        /** Parse [code], or null when its checksum, instructions or operands are invalid. */
        fun parse(code: ByteArray): RarProgram? {
            if (code.isEmpty()) return null
            var xor = 0
            for (i in 1 until code.size) xor = xor xor (code[i].toInt() and 0xFF)
            if (xor != (code[0].toInt() and 0xFF)) return null
            val fingerprint = Crc32.of(code) or (code.size.toLong() shl 32)
            val input = RarBits(code, 1)
            var staticData: ByteArray? = null
            if (input.bit() == 1) {
                val length = input.vmNumber() + 1
                if (length <= 0 || length > VM_MEMORY_SIZE) return null
                staticData = ByteArray(length) { input.bits(8).toByte() }
            }
            val opcodes = ArrayList<RarOpcode>()
            while (input.bitsLeft >= 8) {
                var instruction = input.bits(4)
                if (instruction and 8 != 0) instruction = ((instruction shl 2) or input.bits(2)) - 24
                if (instruction !in 0 until INSTRUCTIONS) return null
                val byteMode = hasByteMode(instruction) && input.bit() == 1
                val opcode = RarOpcode(instruction, byteMode)
                val operands = operands(instruction)
                if (operands >= 1) {
                    val (mode, value) = operand(input, byteMode, isRelativeJump(instruction), opcodes.size)
                    opcode.mode1 = mode; opcode.value1 = value
                }
                if (operands == 2) {
                    val (mode, value) = operand(input, byteMode, false, 0)
                    opcode.mode2 = mode; opcode.value2 = value
                }
                opcodes.add(opcode)
            }
            // A program that does not end in a jump or return gets a return.
            if (opcodes.isEmpty() || !isUnconditionalJump(opcodes.last().instruction)) opcodes.add(RarOpcode(RET, false))
            for (op in opcodes) {
                val n = operands(op.instruction)
                if (n >= 1) {
                    if (op.mode1 == IMMEDIATE && writesFirst(op.instruction)) return null
                    if (op.mode1 == ABSOLUTE) op.value1 = op.value1 and VM_MEMORY_MASK
                }
                if (n == 2) {
                    if (op.mode2 == IMMEDIATE && writesSecond(op.instruction)) return null
                    if (op.mode2 == ABSOLUTE) op.value2 = op.value2 and VM_MEMORY_MASK
                }
            }
            return RarProgram(opcodes.toTypedArray(), staticData, fingerprint)
        }

        private fun operand(input: RarBits, byteMode: Boolean, relative: Boolean, offset: Int): Pair<Int, Int> {
            if (input.bit() == 1) return input.bits(3) to 0 // register
            if (input.bit() == 1) {
                if (input.bit() == 1) {
                    return if (input.bit() == 1) ABSOLUTE to input.vmNumber()
                    else {
                        val register = input.bits(3)
                        (16 + register) to input.vmNumber() // indexed absolute
                    }
                }
                return (8 + input.bits(3)) to 0 // register indirect
            }
            var value = if (byteMode) input.bits(8) else input.vmNumber()
            if (relative) {
                // Values from 256 up are absolute; smaller ones are relative to this instruction.
                val unsigned = value.toLong() and 0xFFFFFFFFL
                value = if (unsigned >= 256) value - 256 else {
                    when {
                        value >= 136 -> value - 264
                        value >= 16 -> value - 8
                        value >= 8 -> value - 16
                        else -> value
                    } + offset
                }
            }
            return IMMEDIATE to value
        }
    }
}

/** The machine: 256 KiB of memory (plus a few bytes so word access at the end stays in bounds), eight registers and flags. */
internal class RarVm {
    val memory = ByteArray(VM_MEMORY_SIZE + 4)
    val registers = IntArray(8)
    private var flags = 0

    fun read32(address: Int): Int {
        val a = address and VM_MEMORY_MASK
        return (memory[a].toInt() and 0xFF) or ((memory[a + 1].toInt() and 0xFF) shl 8) or
            ((memory[a + 2].toInt() and 0xFF) shl 16) or ((memory[a + 3].toInt() and 0xFF) shl 24)
    }

    fun write32(address: Int, value: Int) {
        val a = address and VM_MEMORY_MASK
        memory[a] = value.toByte(); memory[a + 1] = (value ushr 8).toByte()
        memory[a + 2] = (value ushr 16).toByte(); memory[a + 3] = (value ushr 24).toByte()
    }

    private fun read8(address: Int): Int = memory[address and VM_MEMORY_MASK].toInt() and 0xFF
    private fun write8(address: Int, value: Int) { memory[address and VM_MEMORY_MASK] = value.toByte() }

    private fun get(mode: Int, value: Int, byte: Boolean): Int = when {
        mode < 8 -> if (byte) registers[mode] and 0xFF else registers[mode]
        mode < 16 -> if (byte) read8(registers[mode - 8]) else read32(registers[mode - 8])
        mode < 24 -> if (byte) read8(value + registers[mode - 16]) else read32(value + registers[mode - 16])
        mode == ABSOLUTE -> if (byte) read8(value) else read32(value)
        else -> value
    }

    private fun set(mode: Int, value: Int, data: Int, byte: Boolean) {
        when {
            mode < 8 -> registers[mode] = if (byte) data and 0xFF else data
            mode < 16 -> if (byte) write8(registers[mode - 8], data) else write32(registers[mode - 8], data)
            mode < 24 -> if (byte) write8(value + registers[mode - 16], data) else write32(value + registers[mode - 16], data)
            mode == ABSOLUTE -> if (byte) write8(value, data) else write32(value, data)
            else -> throw ArchiveFormatException("RAR: a filter program writes to an immediate operand")
        }
    }

    /** Run [program] from its first instruction; false when it jumps outside itself. */
    fun execute(program: RarProgram, budget: Long = INSTRUCTION_BUDGET): Boolean {
        val ops = program.opcodes
        if (ops.isEmpty() || !isUnconditionalJump(ops.last().instruction)) return false
        flags = 0
        var index = 0
        var steps = 0L
        fun unsignedLess(a: Int, b: Int) = Integer2.compareUnsigned(a, b) < 0
        while (true) {
            if (++steps > budget) throw ArchiveFormatException("RAR: a filter program runs longer than $budget instructions")
            if (index < 0 || index >= ops.size) return false
            val op = ops[index]
            // movzx and movsx read a byte and write a word; byte mode reads and writes bytes.
            val readByte = op.byteMode || op.instruction == MOVZX || op.instruction == MOVSX
            val writeByte = op.byteMode
            fun a() = get(op.mode1, op.value1, readByte)
            fun b() = get(op.mode2, op.value2, readByte)
            fun setA(v: Int) = set(op.mode1, op.value1, v, writeByte)
            fun setB(v: Int) = set(op.mode2, op.value2, v, writeByte)
            fun flagsOf(result: Int, carry: Boolean) { flags = (if (result == 0) ZERO else result and SIGN) or (if (carry) CARRY else 0) }
            fun byteFlagsOf(result: Int, carry: Boolean) {
                flags = (if (result == 0) ZERO else (result.toByte().toInt()) and SIGN) or (if (carry) CARRY else 0)
            }
            var next = index + 1
            when (op.instruction) {
                MOV -> setA(b())
                CMP -> { val t = a(); val r = t - b(); flagsOf(r, unsignedLess(t, r)) }
                ADD -> {
                    val t = a()
                    if (op.byteMode) { val r = (t + b()) and 0xFF; byteFlagsOf(r, unsignedLess(r, t)); setA(r) }
                    else { val r = t + b(); flagsOf(r, unsignedLess(r, t)); setA(r) }
                }
                SUB -> { val t = a(); val r = t - b(); flagsOf(r, unsignedLess(t, r)); setA(r) }
                JZ -> if (flags and ZERO != 0) next = a()
                JNZ -> if (flags and ZERO == 0) next = a()
                INC -> { val r = if (op.byteMode) (a() + 1) and 0xFF else a() + 1; flagsOf(r, false); setA(r) }
                DEC -> { val r = if (op.byteMode) (a() - 1) and 0xFF else a() - 1; flagsOf(r, false); setA(r) }
                JMP -> next = a()
                XOR -> { val r = a() xor b(); flagsOf(r, false); setA(r) }
                AND -> { val r = a() and b(); flagsOf(r, false); setA(r) }
                OR -> { val r = a() or b(); flagsOf(r, false); setA(r) }
                TEST -> flagsOf(a() and b(), false)
                JS -> if (flags and SIGN != 0) next = a()
                JNS -> if (flags and SIGN == 0) next = a()
                JB -> if (flags and CARRY != 0) next = a()
                JBE -> if (flags and (CARRY or ZERO) != 0) next = a()
                JA -> if (flags and (CARRY or ZERO) == 0) next = a()
                JAE -> if (flags and CARRY == 0) next = a()
                PUSH -> { registers[7] -= 4; write32(registers[7], a()) }
                POP -> { setA(read32(registers[7])); registers[7] += 4 }
                CALL -> { registers[7] -= 4; write32(registers[7], index + 1); next = a() }
                RET -> {
                    if (Integer2.compareUnsigned(registers[7], VM_MEMORY_SIZE) >= 0) return true
                    next = read32(registers[7])
                    registers[7] += 4
                }
                NOT -> setA(a().inv())
                SHL -> { val x = a(); val n = b(); val r = x shl n; flagsOf(r, (x shl (n - 1)) and SIGN != 0); setA(r) }
                SHR -> { val x = a(); val n = b(); val r = x ushr n; flagsOf(r, (x ushr (n - 1)) and 1 != 0); setA(r) }
                SAR -> { val x = a(); val n = b(); val r = x shr n; flagsOf(r, (x ushr (n - 1)) and 1 != 0); setA(r) }
                NEG -> { val r = -a(); flagsOf(r, r != 0); setA(r) }
                PUSHA -> { for (i in 0 until 8) write32(registers[7] - 4 - i * 4, registers[i]); registers[7] -= 32 }
                POPA -> { val base = registers[7]; for (i in 0 until 8) registers[i] = read32(base + 28 - i * 4) }
                PUSHF -> { registers[7] -= 4; write32(registers[7], flags) }
                POPF -> { flags = read32(registers[7]); registers[7] += 4 }
                MOVZX -> set(op.mode1, op.value1, b(), false)
                MOVSX -> set(op.mode1, op.value1, b().toByte().toInt(), false)
                XCHG -> { val x = a(); val y = b(); setA(y); setB(x) }
                MUL -> setA(a() * b())
                DIV -> { val d = b(); if (d != 0) setA(Integer2.divideUnsigned(a(), d)) }
                ADC -> {
                    val t = a(); val c = flags and CARRY
                    val r = if (op.byteMode) (t + b() + c) and 0xFF else t + b() + c
                    flagsOf(r, unsignedLess(r, t) || (r == t && c != 0)); setA(r)
                }
                SBB -> {
                    val t = a(); val c = flags and CARRY
                    val r = if (op.byteMode) (t - b() - c) and 0xFF else t - b() - c
                    flagsOf(r, unsignedLess(t, r) || (r == t && c != 0)); setA(r)
                }
                PRINT -> {}
            }
            index = next
        }
    }

    private object Integer2 {
        fun compareUnsigned(a: Int, b: Int): Int = (a xor Int.MIN_VALUE).compareTo(b xor Int.MIN_VALUE)
        fun divideUnsigned(a: Int, b: Int): Int = ((a.toLong() and 0xFFFFFFFFL) / (b.toLong() and 0xFFFFFFFFL)).toInt()
    }
}

/**
 * One filter call: a program, its initial registers and global data, over [length] bytes of
 * output starting at window position [start].
 */
internal class RarFilter(
    val program: RarProgram, val start: Long, val length: Int,
    private val registers: IntArray, private var global: ByteArray,
) {
    var filteredAddress = 0
        private set
    var filteredLength = 0
        private set

    fun setGlobal(offset: Int, value: Int) {
        global[offset] = value.toByte(); global[offset + 1] = (value ushr 8).toByte()
        global[offset + 2] = (value ushr 16).toByte(); global[offset + 3] = (value ushr 24).toByte()
    }

    /** Run on the block already copied to the machine's memory at 0; [position] is its offset in the file. */
    fun run(vm: RarVm, position: Long) {
        if (interpretStandardFilters) return interpret(vm, position)
        when (program.fingerprint) {
            DELTA_FILTER -> native { delta(vm) }
            AUDIO_FILTER -> native { audio(vm) }
            E8_FILTER -> native { e8e9(vm, position, false) }
            E8E9_FILTER -> native { e8e9(vm, position, true) }
            else -> interpret(vm, position)
        }
    }

    private inline fun native(block: () -> Unit) = block()

    private fun interpret(vm: RarVm, position: Long) {
        // RAR restores the global data a program kept, then sets the file position again.
        if (program.globalBackup.size > VM_SYSTEM_GLOBAL_SIZE) global = program.globalBackup.copyOf()
        registers[6] = position.toInt()
        setGlobal(0x24, position.toInt())
        setGlobal(0x28, (position ushr 32).toInt())
        val globalLength = minOf(global.size, VM_SYSTEM_GLOBAL_SIZE)
        global.copyInto(vm.memory, VM_SYSTEM_GLOBAL_ADDRESS, 0, globalLength)
        program.staticData?.let { data ->
            val length = minOf(data.size, VM_USER_GLOBAL_SIZE - globalLength)
            data.copyInto(vm.memory, VM_USER_GLOBAL_ADDRESS, 0, length)
        }
        registers.copyInto(vm.registers)
        if (!vm.execute(program)) throw ArchiveFormatException("RAR: a filter program jumps outside itself")
        val kept = minOf(vm.read32(VM_SYSTEM_GLOBAL_ADDRESS + 0x30).toLong() and 0xFFFFFFFFL, VM_USER_GLOBAL_SIZE.toLong()).toInt()
        global = if (kept > 0) vm.memory.copyOfRange(VM_SYSTEM_GLOBAL_ADDRESS, VM_SYSTEM_GLOBAL_ADDRESS + VM_SYSTEM_GLOBAL_SIZE + kept) else ByteArray(0)
        filteredAddress = vm.read32(VM_SYSTEM_GLOBAL_ADDRESS + 0x20) and VM_MEMORY_MASK
        filteredLength = vm.read32(VM_SYSTEM_GLOBAL_ADDRESS + 0x1C) and VM_MEMORY_MASK
        if (filteredAddress + filteredLength >= VM_MEMORY_SIZE) { filteredAddress = 0; filteredLength = 0 }
        program.globalBackup = if (global.size > VM_SYSTEM_GLOBAL_SIZE) global.copyOf() else ByteArray(0)
    }

    /** The length RAR's standard filters use: register 4, as RAR reads it, not the block length. */
    private val registerLength: Int get() = registers[4]

    private fun delta(vm: RarVm) {
        val length = registerLength
        val channels = registers[0]
        if (length < 0 || length > VM_WORK_SIZE / 2 || channels <= 0) return
        filteredAddress = length
        filteredLength = length
        val memory = vm.memory
        var src = 0
        for (i in 0 until channels) {
            var last = 0
            var dest = i
            while (dest < length) {
                last = (last - memory[src++]) and 0xFF
                memory[length + dest] = last.toByte()
                dest += channels
            }
        }
    }

    private fun audio(vm: RarVm) {
        val length = registerLength
        val channels = registers[0]
        if (length < 0 || length > VM_WORK_SIZE / 2 || channels <= 0) return
        filteredAddress = length
        filteredLength = length
        val memory = vm.memory
        var src = 0
        for (i in 0 until channels) {
            val state = Rar30Audio()
            var dest = i
            while (dest < length) {
                memory[length + dest] = state.decode(memory[src++].toInt() and 0xFF).toByte()
                dest += channels
            }
        }
    }

    private fun e8e9(vm: RarVm, position: Long, handleE9: Boolean) {
        val length = registerLength
        if (length > VM_WORK_SIZE || length < 4) return
        filteredAddress = 0
        filteredLength = length
        rarE8E9(vm.memory, length, position, handleE9)
    }

    companion object {
        /** For tests: run RAR's standard filters through the machine too, to check it against them. */
        internal var interpretStandardFilters = false

        const val DELTA_FILTER = 0x1d0e06077dL
        const val AUDIO_FILTER = 0xd8bc85e701L
        const val E8_FILTER = 0x35ad576887L
        const val E8E9_FILTER = 0x393cd7e57eL
    }
}

/** x86 call and jump addresses back from absolute to relative, as RAR 2.9 stores them. */
internal fun rarE8E9(memory: ByteArray, length: Int, position: Long, handleE9: Boolean) {
    if (length < 5) return
    val fileSize = 0x1000000
    var i = 0
    while (i <= length - 5) {
        val b = memory[i].toInt() and 0xFF
        if (b == 0xE8 || (handleE9 && b == 0xE9)) {
            val current = position.toInt() + i + 1
            val address = (memory[i + 1].toInt() and 0xFF) or ((memory[i + 2].toInt() and 0xFF) shl 8) or
                ((memory[i + 3].toInt() and 0xFF) shl 16) or ((memory[i + 4].toInt() and 0xFF) shl 24)
            val replacement = if (address < 0) {
                if (address + current >= 0) address + fileSize else null
            } else {
                if (address < fileSize) address - current else null
            }
            if (replacement != null) {
                memory[i + 1] = replacement.toByte(); memory[i + 2] = (replacement ushr 8).toByte()
                memory[i + 3] = (replacement ushr 16).toByte(); memory[i + 4] = (replacement ushr 24).toByte()
            }
            i += 4
        }
        i++
    }
}

/** RAR 2.9's audio predictor for one channel. */
internal class Rar30Audio {
    private var weight1 = 0; private var weight2 = 0; private var weight3 = 0
    private var delta1 = 0; private var delta2 = 0; private var delta3 = 0
    private var lastDelta = 0; private var lastByte = 0; private var count = 0
    private val error = IntArray(7)

    fun decode(delta: Int): Int {
        delta3 = delta2
        delta2 = lastDelta - delta1
        delta1 = lastDelta
        val predicted = ((8 * lastByte + weight1 * delta1 + weight2 * delta2 + weight3 * delta3) shr 3) and 0xFF
        val byte = (predicted - delta) and 0xFF
        val predictionError = (delta.toByte().toInt()) shl 3
        error[0] += kotlin.math.abs(predictionError)
        error[1] += kotlin.math.abs(predictionError - delta1)
        error[2] += kotlin.math.abs(predictionError + delta1)
        error[3] += kotlin.math.abs(predictionError - delta2)
        error[4] += kotlin.math.abs(predictionError + delta2)
        error[5] += kotlin.math.abs(predictionError - delta3)
        error[6] += kotlin.math.abs(predictionError + delta3)
        lastDelta = (byte - lastByte).toByte().toInt()
        lastByte = byte
        if (count and 0x1F == 0) {
            var minIndex = 0
            for (i in 1 until 7) if (error[i] < error[minIndex]) minIndex = i
            error.fill(0)
            when (minIndex) {
                1 -> if (weight1 >= -16) weight1--
                2 -> if (weight1 < 16) weight1++
                3 -> if (weight2 >= -16) weight2--
                4 -> if (weight2 < 16) weight2++
                5 -> if (weight3 >= -16) weight3--
                6 -> if (weight3 < 16) weight3++
            }
        }
        count++
        return byte
    }
}

// ---- Instruction set ----

internal const val ABSOLUTE = 24
internal const val IMMEDIATE = 25
private const val CARRY = 1
private const val ZERO = 2
private const val SIGN = Int.MIN_VALUE
private const val INSTRUCTION_BUDGET = 50_000_000L

private const val MOV = 0; private const val CMP = 1; private const val ADD = 2; private const val SUB = 3
private const val JZ = 4; private const val JNZ = 5; private const val INC = 6; private const val DEC = 7
private const val JMP = 8; private const val XOR = 9; private const val AND = 10; private const val OR = 11
private const val TEST = 12; private const val JS = 13; private const val JNS = 14; private const val JB = 15
private const val JBE = 16; private const val JA = 17; private const val JAE = 18; private const val PUSH = 19
private const val POP = 20; private const val CALL = 21; private const val RET = 22; private const val NOT = 23
private const val SHL = 24; private const val SHR = 25; private const val SAR = 26; private const val NEG = 27
private const val PUSHA = 28; private const val POPA = 29; private const val PUSHF = 30; private const val POPF = 31
private const val MOVZX = 32; private const val MOVSX = 33; private const val XCHG = 34; private const val MUL = 35
private const val DIV = 36; private const val ADC = 37; private const val SBB = 38; private const val PRINT = 39
private const val INSTRUCTIONS = 40

// Operand count (bits 0-1), byte mode (4), unconditional jump (8), relative jump (16), writes first (32) and second (64).
private val FLAGS = intArrayOf(
    2 or 4 or 32, 2 or 4, 2 or 4 or 32, 2 or 4 or 32, 1 or 8 or 16, 1 or 16, 1 or 4 or 32, 1 or 4 or 32,
    1 or 16, 2 or 4 or 32, 2 or 4 or 32, 2 or 4 or 32, 2 or 4, 1 or 16, 1 or 16, 1 or 16,
    1 or 16, 1 or 16, 1 or 16, 1, 1, 1 or 16, 0 or 8, 1 or 4 or 32,
    2 or 4 or 32, 2 or 4 or 32, 2 or 4 or 32, 1 or 4 or 32, 0, 0, 0, 0,
    2 or 32, 2 or 32, 2 or 32 or 64 or 4, 2 or 4 or 32, 2 or 4 or 32, 2 or 4 or 32, 2 or 4 or 32, 0,
)

private fun operands(instruction: Int) = FLAGS[instruction] and 3
private fun hasByteMode(instruction: Int) = FLAGS[instruction] and 4 != 0
// RAR counts jz, not only jmp and ret, as ending a program; kept for compatibility.
private fun isUnconditionalJump(instruction: Int) = FLAGS[instruction] and 8 != 0
private fun isRelativeJump(instruction: Int) = FLAGS[instruction] and 16 != 0
private fun writesFirst(instruction: Int) = FLAGS[instruction] and 32 != 0
private fun writesSecond(instruction: Int) = FLAGS[instruction] and 64 != 0
