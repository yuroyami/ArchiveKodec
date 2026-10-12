package io.github.yuroyami.archivekodec.codec.deflate

/**
 * Optimal length-limited Huffman lengths using binary package-merge. At each
 * level, pair adjacent items and merge their packages with the original leaves.
 * Selecting the cheapest 2n-2 items at the last level solves the binary coding
 * problem; each occurrence of a leaf contributes one bit to that symbol's length.
 *
 * Lists need at most 2n-2 items. Primitive node arrays use O(n * maxLen) space;
 * reverse node traversal expands the selected packages without recursion. Weight
 * ties prefer leaves, then original symbol order, for deterministic output.
 *
 * An encoder that builds many trees passes one [Workspace], so the node arrays are
 * allocated once rather than per block.
 */
internal object LengthLimitedHuffman {
    /** Reusable node arrays; not thread-safe, so keep one per encoder. */
    class Workspace {
        internal var weights = LongArray(0)
        internal var left = IntArray(0)
        internal var right = IntArray(0)
        internal var listA = IntArray(0)
        internal var listB = IntArray(0)
        internal var packages = IntArray(0)
        internal var occurrences = IntArray(0)

        internal fun ensure(count: Int, capacity: Int) {
            if (weights.size < capacity) {
                weights = LongArray(capacity); left = IntArray(capacity); right = IntArray(capacity); occurrences = IntArray(capacity)
            }
            if (listA.size < 2 * count) { listA = IntArray(2 * count); listB = IntArray(2 * count); packages = IntArray(2 * count) }
        }
    }

    fun codeLengths(frequencies: IntArray, maxLen: Int, workspace: Workspace = Workspace()): IntArray {
        require(maxLen in 1..15) { "DEFLATE code length limit must be in 1..15" }
        require(frequencies.all { it >= 0 }) { "negative Huffman frequency" }
        val lengths = IntArray(frequencies.size)
        val count = frequencies.count { it > 0 }
        require(count <= (1 shl maxLen)) { "too many symbols for Huffman length limit" }
        val symbols = IntArray(count)
        var used = 0
        for (symbol in frequencies.indices) if (frequencies[symbol] > 0) symbols[used++] = symbol
        if (count == 0) return lengths
        if (count == 1) {
            lengths[symbols[0]] = 1
            validate(frequencies, lengths, maxLen)
            return lengths
        }
        sortSymbols(symbols, frequencies)

        val levels = minOf(maxLen, count - 1)
        val capacity = count + (levels - 1) * (count - 1)
        workspace.ensure(count, capacity)
        val weights = workspace.weights
        val left = workspace.left
        val right = workspace.right
        val packages = workspace.packages
        for (i in 0 until count) weights[i] = frequencies[symbols[i]].toLong()
        var nodes = count
        var list = workspace.listA
        var spare = workspace.listB
        for (i in 0 until count) list[i] = i
        var listSize = count
        val selectedCount = 2 * count - 2

        repeat(levels - 1) {
            val packageCount = listSize / 2
            for (i in 0 until packageCount) {
                val a = list[2 * i]
                val b = list[2 * i + 1]
                packages[i] = nodes
                weights[nodes] = weights[a] + weights[b]
                left[nodes] = a
                right[nodes] = b
                nodes++
            }
            // Pair sums remain sorted because their source list is sorted.
            val mergedSize = minOf(selectedCount, count + packageCount)
            var leaf = 0
            var pack = 0
            for (i in 0 until mergedSize) {
                spare[i] = if (leaf < count &&
                    (pack == packageCount || weights[leaf] <= weights[packages[pack]])) {
                    leaf++
                } else {
                    packages[pack++]
                }
            }
            val merged = spare
            spare = list
            list = merged
            listSize = mergedSize
        }
        check(listSize == selectedCount) { "incomplete Huffman package list" }
        val occurrences = workspace.occurrences
        occurrences.fill(0, 0, nodes)
        for (i in 0 until listSize) occurrences[list[i]]++
        // Parents have larger ids than their children, so one backward pass
        // propagates every selected occurrence down to its constituent leaves.
        for (node in nodes - 1 downTo count) {
            occurrences[left[node]] += occurrences[node]
            occurrences[right[node]] += occurrences[node]
        }
        for (i in 0 until count) lengths[symbols[i]] = occurrences[i]
        validate(frequencies, lengths, maxLen)
        return lengths
    }

    /** Sort by frequency then symbol without boxing indices or allocating a comparator. */
    private fun sortSymbols(symbols: IntArray, frequencies: IntArray) {
        fun precedes(first: Int, second: Int): Boolean =
            frequencies[first] < frequencies[second] ||
                (frequencies[first] == frequencies[second] && first < second)

        if (symbols.size <= 32) {
            for (i in 1 until symbols.size) {
                val symbol = symbols[i]
                var at = i
                while (at > 0 && precedes(symbol, symbols[at - 1])) {
                    symbols[at] = symbols[at - 1]
                    at--
                }
                symbols[at] = symbol
            }
            return
        }

        fun sift(root: Int, size: Int) {
            val symbol = symbols[root]
            var at = root
            while (at < size / 2) {
                var child = at * 2 + 1
                if (child + 1 < size && precedes(symbols[child], symbols[child + 1])) child++
                if (!precedes(symbol, symbols[child])) break
                symbols[at] = symbols[child]
                at = child
            }
            symbols[at] = symbol
        }
        for (root in symbols.size / 2 - 1 downTo 0) sift(root, symbols.size)
        for (end in symbols.lastIndex downTo 1) {
            val last = symbols[0]
            symbols[0] = symbols[end]
            symbols[end] = last
            sift(0, end)
        }
    }

    private fun validate(frequencies: IntArray, lengths: IntArray, maxLen: Int) {
        var used = 0
        var slots = 0
        for (i in frequencies.indices) {
            val length = lengths[i]
            if (frequencies[i] == 0) {
                check(length == 0) { "unused Huffman symbol has a code" }
            } else {
                check(length in 1..maxLen) { "Huffman symbol has an invalid length" }
                used++
                slots += 1 shl (maxLen - length)
            }
        }
        check(slots == if (used == 1) (1 shl (maxLen - 1)) else (1 shl maxLen)) {
            "invalid Huffman code-space occupancy"
        }
    }
}
