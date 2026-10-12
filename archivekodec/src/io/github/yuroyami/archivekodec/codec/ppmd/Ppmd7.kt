package io.github.yuroyami.archivekodec.codec.ppmd

/**
 * PPMd var.H, the model 7z uses (method 03 04 01), with 7z's range decoder. A context is
 * 12 bytes: NumStats (16 bits), SummFreq (16), Stats and Suffix references; a context with
 * one symbol keeps that state in place of SummFreq and Stats.
 */
internal class Ppmd7(size: Int, private val maxOrder: Int) : PpmdHeap(size, UNIT_SIZE, 25 * 16) {
    private var minContext = 0
    private var maxContext = 0
    private var foundState = 0
    private var orderFall = 0
    private var initEsc = 0
    private var prevSuccess = 0
    private var hiBitsFlag = 0
    private var runLength = 0
    private var initRL = 0
    private val binSumm = IntArray(128 * 64)

    private fun numStats(c: Int) = u16(c)
    private fun setNumStats(c: Int, v: Int) = setU16(c, v)
    private fun summFreq(c: Int) = u16(c + 2)
    private fun setSummFreq(c: Int, v: Int) = setU16(c + 2, v)
    private fun stats(c: Int) = u32(c + 4)
    private fun setStats(c: Int, v: Int) = setU32(c + 4, v)
    private fun suffix(c: Int) = u32(c + 8)
    private fun setSuffix(c: Int, v: Int) = setU32(c + 8, v)
    private fun oneState(c: Int) = c + 2

    init {
        restartModel()
        resetDummySee()
    }

    // ---- Allocator ----

    private fun insertNode(node: Int, index: Int) {
        setU32(node, freeList[index])
        freeList[index] = node
    }

    private fun removeNode(index: Int): Int {
        val node = freeList[index]
        freeList[index] = u32(node)
        return node
    }

    private fun splitBlock(ptr: Int, oldIndex: Int, newIndex: Int) {
        val nu = i2u(oldIndex) - i2u(newIndex)
        val p = ptr + u2b(i2u(newIndex))
        var i = u2i(nu)
        if (i2u(i) != nu) {
            val k = i2u(--i)
            insertNode(p + u2b(k), nu - k - 1)
        }
        insertNode(p, i)
    }

    private fun glueFreeBlocks() {
        // Node: Stamp (16 bits), NU (16), Next, Prev. The head node sits in the extra unit.
        val head = alignOffset + size
        var n = head
        glueCount = 255
        for (i in 0 until NUM_INDEXES) {
            val nu = i2u(i)
            var next = freeList[i]
            freeList[i] = 0
            while (next != 0) {
                val node = next
                setU32(node + 4, n)
                setU32(n + 8, next)
                n = next
                next = u32(node)
                setU16(node, 0)
                setU16(node + 2, nu)
            }
        }
        setU16(head, 1)
        setU32(head + 4, n)
        setU32(n + 8, head)
        if (loUnit != hiUnit) setU16(loUnit, 1)

        while (n != head) {
            val node = n
            var nu = u16(node + 2)
            while (true) {
                val node2 = node + nu * UNIT_SIZE
                nu += u16(node2 + 2)
                if (u16(node2) != 0 || nu >= 0x10000) break
                setU32(u32(node2 + 8) + 4, u32(node2 + 4))
                setU32(u32(node2 + 4) + 8, u32(node2 + 8))
                setU16(node + 2, nu)
            }
            n = u32(node + 4)
        }

        n = u32(head + 4)
        while (n != head) {
            var node = n
            val next = u32(node + 4)
            var nu = u16(node + 2)
            while (nu > 128) {
                insertNode(node, NUM_INDEXES - 1)
                nu -= 128
                node += 128 * UNIT_SIZE
            }
            var i = u2i(nu)
            if (i2u(i) != nu) {
                val k = i2u(--i)
                insertNode(node + k * UNIT_SIZE, nu - k - 1)
            }
            insertNode(node, i)
            n = next
        }
    }

    private fun allocUnitsRare(index: Int): Int {
        if (glueCount == 0) {
            glueFreeBlocks()
            if (freeList[index] != 0) return removeNode(index)
        }
        var i = index
        do {
            if (++i == NUM_INDEXES) {
                val numBytes = u2b(i2u(index))
                glueCount--
                return if (unitsStart - text > numBytes) { unitsStart -= numBytes; unitsStart } else 0
            }
        } while (freeList[i] == 0)
        val block = removeNode(i)
        splitBlock(block, i, index)
        return block
    }

    private fun allocUnits(index: Int): Int {
        if (freeList[index] != 0) return removeNode(index)
        val numBytes = u2b(i2u(index))
        if (numBytes <= hiUnit - loUnit) {
            val block = loUnit
            loUnit += numBytes
            return block
        }
        return allocUnitsRare(index)
    }

    private fun shrinkUnits(oldPtr: Int, oldNU: Int, newNU: Int): Int {
        val i0 = u2i(oldNU)
        val i1 = u2i(newNU)
        if (i0 == i1) return oldPtr
        if (freeList[i1] != 0) {
            val ptr = removeNode(i1)
            copyUnits(ptr, oldPtr, newNU)
            insertNode(oldPtr, i0)
            return ptr
        }
        splitBlock(oldPtr, i0, i1)
        return oldPtr
    }

    // ---- Model ----

    private fun restartModel() {
        restarts++
        freeList.fill(0)
        text = alignOffset
        hiUnit = text + size
        loUnit = hiUnit - size / 8 / UNIT_SIZE * 7 * UNIT_SIZE
        unitsStart = loUnit
        glueCount = 0

        orderFall = maxOrder
        initRL = -(if (maxOrder < 12) maxOrder else 12) - 1
        runLength = initRL
        prevSuccess = 0

        hiUnit -= UNIT_SIZE
        minContext = hiUnit
        maxContext = hiUnit
        setSuffix(minContext, 0)
        setNumStats(minContext, 256)
        setSummFreq(minContext, 256 + 1)
        foundState = loUnit
        setStats(minContext, foundState)
        for (i in 0 until 256) {
            val s = foundState + 6 * i
            setU8(s, i)
            setFreq(s, 1)
            setSuccessor(s, 0)
        }
        loUnit += u2b(256 / 2)

        for (i in 0 until 128) for (k in 0 until 8) {
            val value = BIN_SCALE - INIT_BIN_ESC[k] / (i + 2)
            var m = 0
            while (m < 64) { binSumm[i * 64 + k + m] = value; m += 8 }
        }
        for (i in 0 until 25) for (k in 0 until 16) {
            val see = i * 16 + k
            seeShift[see] = PERIOD_BITS - 4
            seeSumm[see] = (5 * i + 10) shl seeShift[see]
            seeCountdown[see] = 4
        }
    }

    private val ps = IntArray(MAX_ORDER)

    private fun createSuccessors(skip: Boolean): Int {
        var c = minContext
        val upBranch = successor(foundState)
        var numPs = 0
        if (!skip) ps[numPs++] = foundState
        val foundSymbol = symbol(foundState)
        while (suffix(c) != 0) {
            c = suffix(c)
            var s: Int
            if (numStats(c) != 1) {
                s = stats(c)
                while (symbol(s) != foundSymbol) s += 6
            } else {
                s = oneState(c)
            }
            val succ = successor(s)
            if (succ != upBranch) {
                c = succ
                if (numPs == 0) return c
                break
            }
            ps[numPs++] = s
        }

        val upSymbol = u8(upBranch)
        val upSuccessor = upBranch + 1
        val upFreq: Int
        if (numStats(c) == 1) {
            upFreq = freq(oneState(c))
        } else {
            var s = stats(c)
            while (symbol(s) != upSymbol) s += 6
            val cf = (freq(s) - 1).toLong()
            val s0 = (summFreq(c) - numStats(c) - cf) and 0xFFFFFFFFL
            upFreq = 1 + (if (2 * cf <= s0) (if (5 * cf > s0) 1 else 0) else ((2 * cf + 3 * s0 - 1) / (2 * s0)).toInt())
        }

        do {
            val c1: Int
            if (hiUnit != loUnit) {
                hiUnit -= UNIT_SIZE
                c1 = hiUnit
            } else if (freeList[0] != 0) {
                c1 = removeNode(0)
            } else {
                c1 = allocUnitsRare(0)
                if (c1 == 0) return 0
            }
            setNumStats(c1, 1)
            val one = oneState(c1)
            setU8(one, upSymbol)
            setFreq(one, upFreq)
            setSuccessor(one, upSuccessor)
            setSuffix(c1, c)
            setSuccessor(ps[--numPs], c1)
            c = c1
        } while (numPs != 0)
        return c
    }

    private fun updateModel() {
        var fSuccessor = successor(foundState)
        val foundSymbol = symbol(foundState)

        if (freq(foundState) < MAX_FREQ / 4 && suffix(minContext) != 0) {
            val c = suffix(minContext)
            if (numStats(c) == 1) {
                val s = oneState(c)
                if (freq(s) < 32) setFreq(s, freq(s) + 1)
            } else {
                var s = stats(c)
                if (symbol(s) != foundSymbol) {
                    do { s += 6 } while (symbol(s) != foundSymbol)
                    if (freq(s) >= freq(s - 6)) {
                        swapStates(s, s - 6)
                        s -= 6
                    }
                }
                if (freq(s) < MAX_FREQ - 9) {
                    setFreq(s, freq(s) + 2)
                    setSummFreq(c, summFreq(c) + 2)
                }
            }
        }

        if (orderFall == 0) {
            minContext = createSuccessors(true)
            maxContext = minContext
            if (minContext == 0) { restartModel(); return }
            setSuccessor(foundState, minContext)
            return
        }

        setU8(text++, foundSymbol)
        var successor = text
        if (text >= unitsStart) { restartModel(); return }

        if (fSuccessor != 0) {
            if (fSuccessor <= successor) {
                val cs = createSuccessors(false)
                if (cs == 0) { restartModel(); return }
                fSuccessor = cs
            }
            if (--orderFall == 0) {
                successor = fSuccessor
                if (maxContext != minContext) text--
            }
        } else {
            setSuccessor(foundState, successor)
            fSuccessor = minContext
        }

        val ns = numStats(minContext)
        val s0 = (summFreq(minContext) - ns - (freq(foundState) - 1)).toLong() and 0xFFFFFFFFL

        var c = maxContext
        while (c != minContext) {
            val ns1 = numStats(c)
            if (ns1 != 1) {
                if (ns1 and 1 == 0) {
                    val oldNU = ns1 ushr 1
                    val i = u2i(oldNU)
                    if (i != u2i(oldNU + 1)) {
                        val ptr = allocUnits(i + 1)
                        if (ptr == 0) { restartModel(); return }
                        val oldPtr = stats(c)
                        copyUnits(ptr, oldPtr, oldNU)
                        insertNode(oldPtr, i)
                        setStats(c, ptr)
                    }
                }
                val sf = summFreq(c)
                setSummFreq(c, sf + (if (2 * ns1 < ns) 1 else 0) + 2 * (if (4 * ns1 <= ns && sf <= 8 * ns1) 1 else 0))
            } else {
                val s = allocUnits(0)
                if (s == 0) { restartModel(); return }
                copyState(s, oneState(c))
                setStats(c, s)
                if (freq(s) < MAX_FREQ / 4 - 1) setFreq(s, freq(s) shl 1) else setFreq(s, MAX_FREQ - 4)
                setSummFreq(c, freq(s) + initEsc + (if (ns > 3) 1 else 0))
            }
            val cf0 = 2L * freq(foundState) * (summFreq(c) + 6)
            val sf = (s0 + summFreq(c)) and 0xFFFFFFFFL
            val cf: Int
            if (cf0 < ((6 * sf) and 0xFFFFFFFFL)) {
                cf = 1 + (if (cf0 > sf) 1 else 0) + (if (cf0 >= ((4 * sf) and 0xFFFFFFFFL)) 1 else 0)
                setSummFreq(c, summFreq(c) + 3)
            } else {
                cf = 4 + (if (cf0 >= ((9 * sf) and 0xFFFFFFFFL)) 1 else 0) + (if (cf0 >= ((12 * sf) and 0xFFFFFFFFL)) 1 else 0) +
                    (if (cf0 >= ((15 * sf) and 0xFFFFFFFFL)) 1 else 0)
                setSummFreq(c, summFreq(c) + cf)
            }
            val s = stats(c) + ns1 * 6
            setSuccessor(s, successor)
            setU8(s, foundSymbol)
            setFreq(s, cf)
            setNumStats(c, ns1 + 1)
            c = suffix(c)
        }
        maxContext = fSuccessor
        minContext = fSuccessor
    }

    private val tmp = ByteArray(6)

    private fun rescale() {
        val stats = stats(minContext)
        var s = foundState
        mem.copyInto(tmp, 0, s, s + 6)
        while (s != stats) { copyState(s, s - 6); s -= 6 }
        tmp.copyInto(mem, s)

        var escFreq = summFreq(minContext) - freq(s)
        setFreq(s, freq(s) + 4)
        val adder = if (orderFall != 0) 1 else 0
        setFreq(s, (freq(s) + adder) shr 1)
        var sumFreq = freq(s)

        var i = numStats(minContext) - 1
        do {
            s += 6
            escFreq -= freq(s)
            setFreq(s, (freq(s) + adder) shr 1)
            sumFreq += freq(s)
            if (freq(s) > freq(s - 6)) {
                var s1 = s
                mem.copyInto(tmp, 0, s1, s1 + 6)
                val tmpFreq = tmp[1].toInt() and 0xFF
                do {
                    copyState(s1, s1 - 6)
                    s1 -= 6
                } while (s1 != stats && tmpFreq > freq(s1 - 6))
                tmp.copyInto(mem, s1)
            }
        } while (--i != 0)

        if (freq(s) == 0) {
            val numStats = numStats(minContext)
            do { i++; s -= 6 } while (freq(s) == 0)
            escFreq += i
            setNumStats(minContext, numStats - i)
            if (numStats(minContext) == 1) {
                mem.copyInto(tmp, 0, stats, stats + 6)
                var f = tmp[1].toInt() and 0xFF
                do {
                    f -= f shr 1
                    escFreq = escFreq ushr 1
                } while (ugt(escFreq, 1))
                tmp[1] = f.toByte()
                insertNode(stats, u2i((numStats + 1) shr 1))
                foundState = oneState(minContext)
                tmp.copyInto(mem, foundState)
                return
            }
            val n0 = (numStats + 1) shr 1
            val n1 = (numStats(minContext) + 1) shr 1
            if (n0 != n1) setStats(minContext, shrinkUnits(stats, n0, n1))
        }
        setSummFreq(minContext, sumFreq + escFreq - (escFreq ushr 1))
        foundState = stats(minContext)
    }

    private fun makeEscFreq(numMasked: Int): Int {
        val ns = numStats(minContext)
        val nonMasked = ns - numMasked
        if (ns == 256) { escFreq = 1; return dummySee }
        val suffixNs = numStats(suffix(minContext))
        val see = NS2INDX[nonMasked - 1] * 16 +
            (if (nonMasked.toLong() < ((suffixNs - ns).toLong() and 0xFFFFFFFFL)) 1 else 0) +
            2 * (if (summFreq(minContext) < 11 * ns) 1 else 0) +
            4 * (if (numMasked > nonMasked) 1 else 0) +
            hiBitsFlag
        escFreq = seeEscape(see)
        return see
    }

    private var escFreq = 0

    private fun nextContext() {
        val c = successor(foundState)
        if (orderFall == 0 && c > text) {
            minContext = c
            maxContext = c
        } else {
            updateModel()
        }
    }

    private fun update1() {
        var s = foundState
        setFreq(s, freq(s) + 4)
        setSummFreq(minContext, summFreq(minContext) + 4)
        if (freq(s) > freq(s - 6)) {
            swapStates(s, s - 6)
            s -= 6
            foundState = s
            if (freq(s) > MAX_FREQ) rescale()
        }
        nextContext()
    }

    private fun update1First() {
        prevSuccess = if (2 * freq(foundState) > summFreq(minContext)) 1 else 0
        runLength += prevSuccess
        setSummFreq(minContext, summFreq(minContext) + 4)
        setFreq(foundState, freq(foundState) + 4)
        if (freq(foundState) > MAX_FREQ) rescale()
        nextContext()
    }

    private fun updateBin() {
        setFreq(foundState, freq(foundState) + (if (freq(foundState) < 128) 1 else 0))
        prevSuccess = 1
        runLength++
        nextContext()
    }

    private fun update2() {
        setSummFreq(minContext, summFreq(minContext) + 4)
        setFreq(foundState, freq(foundState) + 4)
        if (freq(foundState) > MAX_FREQ) rescale()
        runLength = initRL
        updateModel()
    }

    private fun binSummIndex(): Int {
        val one = oneState(minContext)
        hiBitsFlag = HB2FLAG[symbol(foundState)]
        return (freq(one) - 1) * 64 + prevSuccess + NS2BSINDX[numStats(suffix(minContext)) - 1] +
            hiBitsFlag + 2 * HB2FLAG[symbol(one)] + ((runLength shr 26) and 0x20)
    }

    // ---- Decoding ----

    private val charMask = IntArray(256)
    private val found = IntArray(256)

    /** Decode one symbol; -1 is the end marker and -2 a data error. */
    fun decodeSymbol(rc: RangeDecoder7): Int {
        if (numStats(minContext) != 1) {
            var s = stats(minContext)
            val count = rc.threshold(summFreq(minContext))
            var hiCnt = freq(s)
            if (count < hiCnt) {
                rc.decode(0, freq(s))
                foundState = s
                val symbol = symbol(s)
                update1First()
                return symbol
            }
            prevSuccess = 0
            var i = numStats(minContext) - 1
            do {
                s += 6
                hiCnt += freq(s)
                if (hiCnt > count) {
                    rc.decode(hiCnt - freq(s), freq(s))
                    foundState = s
                    val symbol = symbol(s)
                    update1()
                    return symbol
                }
            } while (--i != 0)
            if (count >= summFreq(minContext)) return -2
            hiBitsFlag = HB2FLAG[symbol(foundState)]
            rc.decode(hiCnt, summFreq(minContext) - hiCnt)
            charMask.fill(-1)
            charMask[symbol(s)] = 0
            i = numStats(minContext) - 1
            do { s -= 6; charMask[symbol(s)] = 0 } while (--i != 0)
        } else {
            val prob = binSummIndex()
            if (rc.decodeBit(binSumm[prob]) == 0) {
                binSumm[prob] = probUpdate0(binSumm[prob]) and 0xFFFF
                foundState = oneState(minContext)
                val symbol = symbol(foundState)
                updateBin()
                return symbol
            }
            binSumm[prob] = probUpdate1(binSumm[prob]) and 0xFFFF
            initEsc = EXP_ESCAPE[binSumm[prob] shr 10]
            charMask.fill(-1)
            charMask[symbol(oneState(minContext))] = 0
            prevSuccess = 0
        }
        while (true) {
            val numMasked = numStats(minContext)
            do {
                orderFall++
                if (suffix(minContext) == 0) return -1
                minContext = suffix(minContext)
            } while (numStats(minContext) == numMasked)
            var hiCnt = 0
            var s = stats(minContext)
            var i = 0
            val num = numStats(minContext) - numMasked
            do {
                val k = charMask[symbol(s)]
                hiCnt += freq(s) and k
                found[i] = s
                s += 6
                i -= k
            } while (i != num)

            val see = makeEscFreq(numMasked)
            val freqSum = escFreq + hiCnt
            val count = rc.threshold(freqSum)
            if (count < hiCnt) {
                var k = 0
                hiCnt = 0
                while (true) {
                    hiCnt += freq(found[k])
                    if (hiCnt > count) break
                    k++
                }
                s = found[k]
                rc.decode(hiCnt - freq(s), freq(s))
                seeUpdate(see)
                foundState = s
                val symbol = symbol(s)
                update2()
                return symbol
            }
            if (count >= freqSum) return -2
            rc.decode(hiCnt, freqSum - hiCnt)
            seeSumm[see] = (seeSumm[see] + freqSum) and 0xFFFF
            do { charMask[symbol(found[--i])] = 0 } while (i != 0)
        }
    }

    companion object {
        const val MAX_ORDER = 64
        private val NS2BSINDX = IntArray(256) { if (it == 0) 0 else if (it == 1) 2 else if (it < 11) 4 else 6 }
        private val NS2INDX = IntArray(256).also {
            for (i in 0 until 3) it[i] = i
            var m = 3
            var k = 1
            for (i in 3 until 256) {
                it[i] = m
                if (--k == 0) k = ++m - 2
            }
        }
        private val HB2FLAG = IntArray(256) { if (it < 0x40) 0 else 8 }
    }
}

/** The 7z range decoder that PPMd var.H uses: 32-bit range and code. */
internal class RangeDecoder7(private val input: PpmdInput) {
    private var range = 0xFFFFFFFFL
    private var code = 0L

    /** Whether the stream starts as 7z writes it: a zero byte, then a code below 0xFFFFFFFF. */
    fun init(): Boolean {
        if (input.next() != 0) return false
        repeat(4) { code = (code shl 8) or input.next().toLong() }
        return code < 0xFFFFFFFFL
    }

    val finishedOk: Boolean get() = code == 0L

    fun threshold(total: Int): Int {
        range /= total
        return (code / range).toInt().let { if (code / range > Int.MAX_VALUE) Int.MAX_VALUE else it }
    }

    private fun normalize() {
        if (range < TOP) {
            code = ((code shl 8) or input.next().toLong()) and 0xFFFFFFFFL
            range = (range shl 8) and 0xFFFFFFFFL
            if (range < TOP) {
                code = ((code shl 8) or input.next().toLong()) and 0xFFFFFFFFL
                range = (range shl 8) and 0xFFFFFFFFL
            }
        }
    }

    fun decode(start: Int, size: Int) {
        code = (code - start.toLong() * range) and 0xFFFFFFFFL
        range = (range * size) and 0xFFFFFFFFL
        normalize()
    }

    fun decodeBit(size0: Int): Int {
        val bound = (range ushr 14) * size0
        val bit: Int
        if (code < bound) {
            bit = 0
            range = bound
        } else {
            bit = 1
            code -= bound
            range -= bound
        }
        normalize()
        return bit
    }

    private companion object {
        const val TOP = 1L shl 24
    }
}
