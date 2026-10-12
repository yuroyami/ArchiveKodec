package io.github.yuroyami.archivekodec.codec.ppmd

import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * PPMd var.I revision 1, the model ZIP method 98 uses, with Subbotin's carryless range
 * coder. A context is 12 bytes: NumStats (symbols - 1, 8 bits), Flags, SummFreq (16 bits),
 * Stats and Suffix. The restart and cut-off restore methods are supported; freeze, which
 * the reference also leaves out, is not.
 */
internal class Ppmd8(size: Int, private val maxOrder: Int, private val restoreMethod: Int) : PpmdHeap(size, 0, 24 * 32) {
    private var minContext = 0
    private var maxContext = 0
    private var foundState = 0
    private var orderFall = 0
    private var initEsc = 0
    private var prevSuccess = 0
    private var runLength = 0
    private var initRL = 0
    private val stamps = IntArray(NUM_INDEXES)
    private val binSumm = IntArray(25 * 64)

    private fun numStats(c: Int) = u8(c)
    private fun setNumStats(c: Int, v: Int) = setU8(c, v)
    private fun flags(c: Int) = u8(c + 1)
    private fun setFlags(c: Int, v: Int) = setU8(c + 1, v)
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

    // ---- Allocator. Node: Stamp, Next, NU (32 bits each). ----

    private fun insertNode(node: Int, index: Int) {
        setU32(node, EMPTY_NODE)
        setU32(node + 4, freeList[index])
        setU32(node + 8, i2u(index))
        freeList[index] = node
        stamps[index]++
    }

    private fun removeNode(index: Int): Int {
        val node = freeList[index]
        freeList[index] = u32(node + 4)
        stamps[index]--
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
        var head = 0
        var prev = -1 // -1 is the head variable, otherwise the offset of a Next field
        glueCount = 1 shl 13
        stamps.fill(0)
        if (loUnit != hiUnit) setU32(loUnit, 0)

        for (i in 0 until NUM_INDEXES) {
            var next = freeList[i]
            freeList[i] = 0
            while (next != 0) {
                val node = next
                if (u32(node + 8) != 0) {
                    if (prev == -1) head = next else setU32(prev, next)
                    prev = node + 4
                    while (true) {
                        val node2 = node + u32(node + 8) * UNIT_SIZE
                        if (u32(node2) != EMPTY_NODE) break
                        setU32(node + 8, u32(node + 8) + u32(node2 + 8))
                        setU32(node2 + 8, 0)
                    }
                }
                next = u32(node + 4)
            }
        }
        if (prev == -1) head = 0 else setU32(prev, 0)

        while (head != 0) {
            var node = head
            head = u32(node + 4)
            var nu = u32(node + 8)
            if (nu == 0) continue
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

    private fun freeUnits(ptr: Int, nu: Int) = insertNode(ptr, u2i(nu))

    private fun specialFreeUnit(ptr: Int) {
        if (ptr != unitsStart) insertNode(ptr, 0) else unitsStart += UNIT_SIZE
    }

    private fun moveUnitsUp(oldPtr: Int, nu: Int): Int {
        val index = u2i(nu)
        if (oldPtr > unitsStart + 16 * 1024 || ugt(oldPtr, freeList[index])) return oldPtr
        val ptr = removeNode(index)
        copyUnits(ptr, oldPtr, nu)
        if (oldPtr != unitsStart) insertNode(oldPtr, index) else unitsStart += u2b(i2u(index))
        return ptr
    }

    private fun expandTextArea() {
        val count = IntArray(NUM_INDEXES)
        if (loUnit != hiUnit) setU32(loUnit, 0)
        var node = unitsStart
        while (u32(node) == EMPTY_NODE) {
            setU32(node, 0)
            count[u2i(u32(node + 8))]++
            node += u32(node + 8) * UNIT_SIZE
        }
        unitsStart = node

        for (i in 0 until NUM_INDEXES) {
            // A Next field: -1 is freeList[i] itself, otherwise the offset of a node's Next.
            var next = -1
            fun read(field: Int) = if (field == -1) freeList[i] else u32(field)
            fun write(field: Int, v: Int) { if (field == -1) freeList[i] = v else setU32(field, v) }
            while (count[i] != 0) {
                var n = read(next)
                while (u32(n) == 0) {
                    write(next, u32(n + 4))
                    n = read(next)
                    stamps[i]--
                    if (--count[i] == 0) break
                }
                next = n + 4
            }
        }
    }

    // ---- Model ----

    private fun restartModel() {
        restarts++
        freeList.fill(0)
        stamps.fill(0)
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
        setNumStats(minContext, 255)
        setFlags(minContext, 0)
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

        var i = 0
        for (m in 0 until 25) {
            while (NS2INDX[i] == m) i++
            for (k in 0 until 8) {
                val value = BIN_SCALE - INIT_BIN_ESC[k] / (i + 1)
                var r = 0
                while (r < 64) { binSumm[m * 64 + k + r] = value; r += 8 }
            }
        }
        i = 0
        for (m in 0 until 24) {
            while (NS2INDX[i + 3] == m + 3) i++
            for (k in 0 until 32) {
                val see = m * 32 + k
                seeShift[see] = PERIOD_BITS - 4
                seeSumm[see] = (2 * i + 5) shl seeShift[see]
                seeCountdown[see] = 7
            }
        }
    }

    private fun refresh(ctx: Int, oldNU: Int, scale: Int) {
        var i = numStats(ctx)
        var s = shrinkUnits(stats(ctx), oldNU, (i + 2) shr 1)
        setStats(ctx, s)
        var flags = (flags(ctx) and (0x10 + 0x04 * scale)) + 0x08 * (if (symbol(s) >= 0x40) 1 else 0)
        var escFreq = summFreq(ctx) - freq(s)
        setFreq(s, (freq(s) + scale) shr scale)
        var sumFreq = freq(s)
        do {
            s += 6
            escFreq -= freq(s)
            setFreq(s, (freq(s) + scale) shr scale)
            sumFreq += freq(s)
            flags = flags or 0x08 * (if (symbol(s) >= 0x40) 1 else 0)
        } while (--i != 0)
        setSummFreq(ctx, sumFreq + ((escFreq + scale) ushr scale))
        setFlags(ctx, flags)
    }

    private fun cutOff(ctx: Int, order: Int): Int {
        if (numStats(ctx) == 0) {
            val s = oneState(ctx)
            if (successor(s) >= unitsStart) {
                if (order < maxOrder) setSuccessor(s, cutOff(successor(s), order + 1)) else setSuccessor(s, 0)
                if (successor(s) != 0 || order <= 9) return ctx
            }
            specialFreeUnit(ctx)
            return 0
        }
        val tmp = (numStats(ctx) + 2) shr 1
        setStats(ctx, moveUnitsUp(stats(ctx), tmp))
        var i = numStats(ctx)
        var s = stats(ctx) + i * 6
        while (s >= stats(ctx)) {
            if (successor(s) < unitsStart) {
                val s2 = stats(ctx) + (i--) * 6
                setSuccessor(s, 0)
                swapStates(s, s2)
            } else if (order < maxOrder) {
                setSuccessor(s, cutOff(successor(s), order + 1))
            } else {
                setSuccessor(s, 0)
            }
            s -= 6
        }
        if (i != numStats(ctx) && order != 0) {
            setNumStats(ctx, i)
            s = stats(ctx)
            if (i < 0) {
                freeUnits(s, tmp)
                specialFreeUnit(ctx)
                return 0
            }
            if (i == 0) {
                setFlags(ctx, (flags(ctx) and 0x10) + 0x08 * (if (symbol(s) >= 0x40) 1 else 0))
                copyState(oneState(ctx), s)
                freeUnits(s, tmp)
                setFreq(oneState(ctx), (freq(oneState(ctx)) + 11) shr 3)
            } else {
                refresh(ctx, tmp, if (summFreq(ctx) > 16 * i) 1 else 0)
            }
        }
        return ctx
    }

    private fun usedMemory(): Int {
        var v = 0
        for (i in 0 until NUM_INDEXES) v += stamps[i] * i2u(i)
        return size - (hiUnit - loUnit) - (unitsStart - text) - u2b(v)
    }

    private fun restoreModel(c1: Int) {
        text = alignOffset
        var c = maxContext
        while (c != c1) {
            setNumStats(c, numStats(c) - 1)
            if (numStats(c) == 0) {
                val s = stats(c)
                setFlags(c, (flags(c) and 0x10) + 0x08 * (if (symbol(s) >= 0x40) 1 else 0))
                copyState(oneState(c), s)
                specialFreeUnit(s)
                setFreq(oneState(c), (freq(oneState(c)) + 11) shr 3)
            } else {
                refresh(c, (numStats(c) + 3) shr 1, 0)
            }
            c = suffix(c)
        }
        while (c != minContext) {
            if (numStats(c) == 0) {
                val one = oneState(c)
                setFreq(one, freq(one) - (freq(one) shr 1))
            } else {
                setSummFreq(c, summFreq(c) + 4)
                if (summFreq(c) > 128 + 4 * numStats(c)) refresh(c, (numStats(c) + 2) shr 1, 1)
            }
            c = suffix(c)
        }

        if (restoreMethod == RESTORE_RESTART || ugt(size ushr 1, usedMemory())) {
            restartModel()
        } else {
            cutOffs++
            while (suffix(maxContext) != 0) maxContext = suffix(maxContext)
            do {
                cutOff(maxContext, 0)
                expandTextArea()
            } while (ugt(usedMemory(), 3 * (size ushr 2)))
            glueCount = 0
            orderFall = maxOrder
        }
    }

    private val ps = IntArray(MAX_ORDER + 1)

    private fun createSuccessors(skip: Boolean, firstState: Int, start: Int): Int {
        var s1 = firstState
        var c = start
        val upBranch = successor(foundState)
        val foundSymbol = symbol(foundState)
        var numPs = 0
        if (!skip) ps[numPs++] = foundState

        while (suffix(c) != 0) {
            c = suffix(c)
            var s: Int
            if (s1 != 0) {
                s = s1
                s1 = 0
            } else if (numStats(c) != 0) {
                s = stats(c)
                while (symbol(s) != foundSymbol) s += 6
                if (freq(s) < MAX_FREQ - 9) {
                    setFreq(s, freq(s) + 1)
                    setSummFreq(c, summFreq(c) + 1)
                }
            } else {
                s = oneState(c)
                setFreq(s, freq(s) + (if (numStats(suffix(c)) == 0 && freq(s) < 24) 1 else 0))
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
        val newFlags = 0x10 * (if (foundSymbol >= 0x40) 1 else 0) + 0x08 * (if (upSymbol >= 0x40) 1 else 0)
        val upFreq: Int
        if (numStats(c) == 0) {
            upFreq = freq(oneState(c))
        } else {
            var s = stats(c)
            while (symbol(s) != upSymbol) s += 6
            val cf = (freq(s) - 1).toLong()
            val s0 = (summFreq(c) - numStats(c) - cf) and 0xFFFFFFFFL
            upFreq = 1 + (if (2 * cf <= s0) (if (5 * cf > s0) 1 else 0) else (((cf + 2 * s0 - 3) and 0xFFFFFFFFL) / s0).toInt())
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
            setNumStats(c1, 0)
            setFlags(c1, newFlags)
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

    private fun reduceOrder(firstState: Int, start: Int): Int {
        var s1 = firstState
        var c = start
        var s = 0
        val c1 = c
        val upBranch = text
        val foundSymbol = symbol(foundState)
        setSuccessor(foundState, upBranch)
        orderFall++
        while (true) {
            if (s1 != 0) {
                c = suffix(c)
                s = s1
                s1 = 0
            } else {
                if (suffix(c) == 0) return c
                c = suffix(c)
                if (numStats(c) != 0) {
                    s = stats(c)
                    if (symbol(s) != foundSymbol) {
                        do { s += 6 } while (symbol(s) != foundSymbol)
                    }
                    if (freq(s) < MAX_FREQ - 9) {
                        setFreq(s, freq(s) + 2)
                        setSummFreq(c, summFreq(c) + 2)
                    }
                } else {
                    s = oneState(c)
                    setFreq(s, freq(s) + (if (freq(s) < 32) 1 else 0))
                }
            }
            if (successor(s) != 0) break
            setSuccessor(s, upBranch)
            orderFall++
        }
        if (successor(s) <= upBranch) {
            val s2 = foundState
            foundState = s
            val succ = createSuccessors(false, 0, c)
            setSuccessor(s, succ)
            foundState = s2
        }
        if (orderFall == 1 && c1 == maxContext) {
            setSuccessor(foundState, successor(s))
            text--
        }
        return successor(s)
    }

    private fun updateModel() {
        var fSuccessor = successor(foundState)
        val fFreq = freq(foundState)
        val fSymbol = symbol(foundState)
        var s = 0

        if (fFreq < MAX_FREQ / 4 && suffix(minContext) != 0) {
            val c = suffix(minContext)
            if (numStats(c) == 0) {
                s = oneState(c)
                if (freq(s) < 32) setFreq(s, freq(s) + 1)
            } else {
                s = stats(c)
                if (symbol(s) != fSymbol) {
                    do { s += 6 } while (symbol(s) != fSymbol)
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

        var c = maxContext
        if (orderFall == 0 && fSuccessor != 0) {
            val cs = createSuccessors(true, s, minContext)
            if (cs == 0) {
                setSuccessor(foundState, 0)
                restoreModel(c)
            } else {
                setSuccessor(foundState, cs)
                maxContext = cs
            }
            return
        }

        setU8(text++, fSymbol)
        var successor = text
        if (text >= unitsStart) { restoreModel(c); return }

        if (fSuccessor == 0) {
            val cs = reduceOrder(s, minContext)
            if (cs == 0) { restoreModel(c); return }
            fSuccessor = cs
        } else if (fSuccessor < unitsStart) {
            val cs = createSuccessors(false, s, minContext)
            if (cs == 0) { restoreModel(c); return }
            fSuccessor = cs
        }

        if (--orderFall == 0) {
            successor = fSuccessor
            if (maxContext != minContext) text--
        }

        val ns = numStats(minContext)
        val s0 = (summFreq(minContext) - ns - fFreq).toLong() and 0xFFFFFFFFL
        val flag = 0x08 * (if (fSymbol >= 0x40) 1 else 0)

        while (c != minContext) {
            val ns1 = numStats(c)
            if (ns1 != 0) {
                if (ns1 and 1 != 0) {
                    val oldNU = (ns1 + 1) shr 1
                    val i = u2i(oldNU)
                    if (i != u2i(oldNU + 1)) {
                        val ptr = allocUnits(i + 1)
                        if (ptr == 0) { restoreModel(c); return }
                        val oldPtr = stats(c)
                        copyUnits(ptr, oldPtr, oldNU)
                        insertNode(oldPtr, i)
                        setStats(c, ptr)
                    }
                }
                setSummFreq(c, summFreq(c) + (if (3 * ns1 + 1 < ns) 1 else 0))
            } else {
                val s2 = allocUnits(0)
                if (s2 == 0) { restoreModel(c); return }
                copyState(s2, oneState(c))
                setStats(c, s2)
                if (freq(s2) < MAX_FREQ / 4 - 1) setFreq(s2, freq(s2) shl 1) else setFreq(s2, MAX_FREQ - 4)
                setSummFreq(c, freq(s2) + initEsc + (if (ns > 2) 1 else 0))
            }
            val cf0 = 2L * fFreq * (summFreq(c) + 6)
            val sf = (s0 + summFreq(c)) and 0xFFFFFFFFL
            val cf: Int
            if (cf0 < ((6 * sf) and 0xFFFFFFFFL)) {
                cf = 1 + (if (cf0 > sf) 1 else 0) + (if (cf0 >= ((4 * sf) and 0xFFFFFFFFL)) 1 else 0)
                setSummFreq(c, summFreq(c) + 4)
            } else {
                cf = 4 + (if (cf0 > ((9 * sf) and 0xFFFFFFFFL)) 1 else 0) + (if (cf0 > ((12 * sf) and 0xFFFFFFFFL)) 1 else 0) +
                    (if (cf0 > ((15 * sf) and 0xFFFFFFFFL)) 1 else 0)
                setSummFreq(c, summFreq(c) + cf)
            }
            val s2 = stats(c) + (ns1 + 1) * 6
            setSuccessor(s2, successor)
            setU8(s2, fSymbol)
            setFreq(s2, cf)
            setFlags(c, flags(c) or flag)
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

        var i = numStats(minContext)
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
            if (numStats(minContext) == 0) {
                mem.copyInto(tmp, 0, stats, stats + 6)
                val f0 = tmp[1].toInt() and 0xFF
                var f = (((2L * f0 + escFreq - 1) and 0xFFFFFFFFL) / (escFreq.toLong() and 0xFFFFFFFFL)).toInt()
                if (f > MAX_FREQ / 3) f = MAX_FREQ / 3
                tmp[1] = f.toByte()
                insertNode(stats, u2i((numStats + 2) shr 1))
                val symbol = tmp[0].toInt() and 0xFF
                setFlags(minContext, (flags(minContext) and 0x10) + 0x08 * (if (symbol >= 0x40) 1 else 0))
                foundState = oneState(minContext)
                tmp.copyInto(mem, foundState)
                return
            }
            val n0 = (numStats + 2) shr 1
            val n1 = (numStats(minContext) + 2) shr 1
            if (n0 != n1) setStats(minContext, shrinkUnits(stats, n0, n1))
            setFlags(minContext, flags(minContext) and 0x08.inv())
            s = stats(minContext)
            setFlags(minContext, flags(minContext) or 0x08 * (if (symbol(s) >= 0x40) 1 else 0))
            i = numStats(minContext)
            do {
                s += 6
                setFlags(minContext, flags(minContext) or 0x08 * (if (symbol(s) >= 0x40) 1 else 0))
            } while (--i != 0)
        }
        setSummFreq(minContext, sumFreq + escFreq - (escFreq ushr 1))
        setFlags(minContext, flags(minContext) or 0x04)
        foundState = stats(minContext)
    }

    private var escFreq = 0

    private fun makeEscFreq(numMasked1: Int): Int {
        val ns = numStats(minContext)
        if (ns == 0xFF) { escFreq = 1; return dummySee }
        val see = (NS2INDX[ns + 2] - 3) * 32 +
            (if (summFreq(minContext) > 11 * (ns + 1)) 1 else 0) +
            2 * (if (2 * ns < numStats(suffix(minContext)) + numMasked1) 1 else 0) +
            flags(minContext)
        escFreq = seeEscape(see)
        return see
    }

    private fun nextContext() {
        val c = successor(foundState)
        if (orderFall == 0 && c >= unitsStart) {
            minContext = c
            maxContext = c
        } else {
            updateModel()
            minContext = maxContext
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
        prevSuccess = if (2 * freq(foundState) >= summFreq(minContext)) 1 else 0
        runLength += prevSuccess
        setSummFreq(minContext, summFreq(minContext) + 4)
        setFreq(foundState, freq(foundState) + 4)
        if (freq(foundState) > MAX_FREQ) rescale()
        nextContext()
    }

    private fun updateBin() {
        setFreq(foundState, freq(foundState) + (if (freq(foundState) < 196) 1 else 0))
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
        minContext = maxContext
    }

    private fun binSummIndex(): Int =
        NS2INDX[freq(oneState(minContext)) - 1] * 64 + NS2BSINDX[numStats(suffix(minContext))] +
            prevSuccess + flags(minContext) + ((runLength shr 26) and 0x20)

    // ---- Decoding ----

    private val charMask = IntArray(256)
    private val found = IntArray(256)

    /** Decode one symbol; -1 is the end marker and -2 a data error. */
    fun decodeSymbol(rc: RangeDecoder8): Int {
        if (numStats(minContext) != 0) {
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
            var i = numStats(minContext)
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
            rc.decode(hiCnt, summFreq(minContext) - hiCnt)
            charMask.fill(-1)
            charMask[symbol(s)] = 0
            i = numStats(minContext)
            do { s -= 6; charMask[symbol(s)] = 0 } while (--i != 0)
        } else {
            val prob = binSummIndex()
            if (rc.binaryThreshold() < binSumm[prob]) {
                rc.decode(0, binSumm[prob])
                binSumm[prob] = probUpdate0(binSumm[prob]) and 0xFFFF
                foundState = oneState(minContext)
                val symbol = symbol(foundState)
                updateBin()
                return symbol
            }
            rc.decode(binSumm[prob], (1 shl 14) - binSumm[prob])
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

    // ---- Encoding ----

    /** Encode [symbol], or the end marker for -1. */
    fun encodeSymbol(rc: RangeEncoder8, symbol: Int) {
        if (numStats(minContext) != 0) {
            var s = stats(minContext)
            if (symbol(s) == symbol) {
                rc.encode(0, freq(s), summFreq(minContext))
                foundState = s
                update1First()
                return
            }
            prevSuccess = 0
            var sum = freq(s)
            var i = numStats(minContext)
            do {
                s += 6
                if (symbol(s) == symbol) {
                    rc.encode(sum, freq(s), summFreq(minContext))
                    foundState = s
                    update1()
                    return
                }
                sum += freq(s)
            } while (--i != 0)
            charMask.fill(-1)
            charMask[symbol(s)] = 0
            i = numStats(minContext)
            do { s -= 6; charMask[symbol(s)] = 0 } while (--i != 0)
            rc.encode(sum, summFreq(minContext) - sum, summFreq(minContext))
        } else {
            val prob = binSummIndex()
            val s = oneState(minContext)
            if (symbol(s) == symbol) {
                rc.encodeBit0(binSumm[prob])
                binSumm[prob] = probUpdate0(binSumm[prob]) and 0xFFFF
                foundState = s
                updateBin()
                return
            }
            rc.encodeBit1(binSumm[prob])
            binSumm[prob] = probUpdate1(binSumm[prob]) and 0xFFFF
            initEsc = EXP_ESCAPE[binSumm[prob] shr 10]
            charMask.fill(-1)
            charMask[symbol(s)] = 0
            prevSuccess = 0
        }
        while (true) {
            val numMasked = numStats(minContext)
            do {
                orderFall++
                if (suffix(minContext) == 0) return
                minContext = suffix(minContext)
            } while (numStats(minContext) == numMasked)

            val see = makeEscFreq(numMasked)
            var s = stats(minContext)
            var sum = 0
            var i = numStats(minContext) + 1
            do {
                val cur = symbol(s)
                if (cur == symbol) {
                    val low = sum
                    val s1 = s
                    do {
                        sum += freq(s) and charMask[symbol(s)]
                        s += 6
                    } while (--i != 0)
                    rc.encode(low, freq(s1), sum + escFreq)
                    seeUpdate(see)
                    foundState = s1
                    update2()
                    return
                }
                sum += freq(s) and charMask[cur]
                charMask[cur] = 0
                s += 6
            } while (--i != 0)
            rc.encode(sum, escFreq, sum + escFreq)
            seeSumm[see] = (seeSumm[see] + sum + escFreq) and 0xFFFF
        }
    }

    companion object {
        const val MAX_ORDER = 16
        const val RESTORE_RESTART = 0
        const val RESTORE_CUT_OFF = 1
        private const val EMPTY_NODE = -1
        private val NS2BSINDX = IntArray(256) { if (it == 0) 0 else if (it == 1) 2 else if (it < 11) 4 else 6 }
        private val NS2INDX = IntArray(260).also {
            for (i in 0 until 5) it[i] = i
            var m = 5
            var k = 1
            for (i in 5 until 260) {
                it[i] = m
                if (--k == 0) k = ++m - 4
            }
        }
    }
}

/** Subbotin's carryless range decoder, as PPMd var.I uses it. */
internal class RangeDecoder8(private val input: PpmdInput) {
    private var low = 0L
    private var range = 0xFFFFFFFFL
    private var code = 0L

    fun init(): Boolean {
        repeat(4) { code = (code shl 8) or input.next().toLong() }
        return code < 0xFFFFFFFFL
    }

    val finishedOk: Boolean get() = code == 0L

    fun threshold(total: Int): Int {
        range /= total
        val value = code / range
        return if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
    }

    fun binaryThreshold(): Int {
        range = range ushr 14
        val value = code / range
        return if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
    }

    fun decode(start: Int, size: Int) {
        val shift = start.toLong() * range
        low = (low + shift) and MASK
        code = (code - shift) and MASK
        range = (range * size) and MASK
        while (true) {
            if ((low xor ((low + range) and MASK)) >= TOP) {
                if (range >= BOT) break
                range = (0 - low) and (BOT - 1)
            }
            code = ((code shl 8) or input.next().toLong()) and MASK
            range = (range shl 8) and MASK
            low = (low shl 8) and MASK
        }
    }

    private companion object {
        const val TOP = 1L shl 24
        const val BOT = 1L shl 15
        const val MASK = 0xFFFFFFFFL
    }
}

/** The matching carryless range encoder. */
internal class RangeEncoder8(private val out: ByteArrayBuilder) {
    private var low = 0L
    private var range = 0xFFFFFFFFL

    private fun normalize() {
        while (true) {
            if ((low xor ((low + range) and MASK)) >= TOP) {
                if (range >= BOT) break
                range = (0 - low) and (BOT - 1)
            }
            out.append((low ushr 24).toByte())
            range = (range shl 8) and MASK
            low = (low shl 8) and MASK
        }
    }

    fun encode(start: Int, size: Int, total: Int) {
        range /= total
        low = (low + start * range) and MASK
        range = (range * size) and MASK
        normalize()
    }

    fun encodeBit0(size0: Int) {
        range = range ushr 14
        range = (range * size0) and MASK
        normalize()
    }

    fun encodeBit1(size0: Int) {
        range = range ushr 14
        low = (low + size0 * range) and MASK
        range = (range * ((1 shl 14) - size0)) and MASK
        normalize()
    }

    fun flush() {
        repeat(4) {
            out.append((low ushr 24).toByte())
            low = (low shl 8) and MASK
        }
    }

    private companion object {
        const val TOP = 1L shl 24
        const val BOT = 1L shl 15
        const val MASK = 0xFFFFFFFFL
    }
}
