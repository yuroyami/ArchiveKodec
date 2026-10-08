package io.github.yuroyami.archivekodec.util

import io.github.yuroyami.archivekodec.ArchiveSizeException

internal fun checkedArraySize(required: Long): Int {
    if (required < 0 || required > Int.MAX_VALUE) {
        throw ArchiveSizeException("result of $required bytes cannot fit in a ByteArray (maximum ${Int.MAX_VALUE})")
    }
    return required.toInt()
}

/** Grow geometrically; use a size hint only within twice the space already required. */
internal fun byteArrayCapacity(current: Int, used: Int, extra: Int, maximum: Int = Int.MAX_VALUE, sizeHint: Int = 0): Int {
    require(current >= 0 && used in 0..current && extra >= 0 && maximum >= 0 && sizeHint >= 0)
    val needed = checkedArraySize(used.toLong() + extra)
    if (needed > maximum) throw ArchiveSizeException("result of $needed bytes exceeds buffer maximum $maximum")
    if (needed <= current) return current
    // A nearby hint avoids a final growth/trim pair (for example at 2^n + 1),
    // without reserving a file-controlled size before substantial output exists.
    if (sizeHint in needed..maximum && sizeHint.toLong() <= needed * 2L) return sizeHint
    val doubled = current.toLong() * 2
    val growth = if (doubled > Int.MAX_VALUE) {
        current + (Int.MAX_VALUE - current) / 2
    } else {
        doubled.toInt()
    }
    val preferred = maxOf(16, growth, needed)
    return minOf(preferred, maximum)
}
