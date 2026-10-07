package io.github.yuroyami.archivekodec.util

import io.github.yuroyami.archivekodec.ArchiveSizeException

internal fun checkedArraySize(required: Long): Int {
    if (required < 0 || required > Int.MAX_VALUE) {
        throw ArchiveSizeException("result of $required bytes cannot fit in a ByteArray (maximum ${Int.MAX_VALUE})")
    }
    return required.toInt()
}

/** Grow geometrically when possible, then use half the remaining array headroom. */
internal fun byteArrayCapacity(current: Int, used: Int, extra: Int, maximum: Int = Int.MAX_VALUE): Int {
    require(current >= 0 && used in 0..current && extra >= 0 && maximum >= 0)
    val needed = checkedArraySize(used.toLong() + extra)
    if (needed > maximum) throw ArchiveSizeException("result of $needed bytes exceeds buffer maximum $maximum")
    if (needed <= current) return current
    val doubled = current.toLong() * 2
    val growth = if (doubled > Int.MAX_VALUE) {
        current + (Int.MAX_VALUE - current) / 2
    } else {
        doubled.toInt()
    }
    val preferred = maxOf(16, growth, needed)
    return minOf(preferred, maximum)
}
