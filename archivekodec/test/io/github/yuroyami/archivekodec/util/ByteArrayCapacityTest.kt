package io.github.yuroyami.archivekodec.util

import io.github.yuroyami.archivekodec.ArchiveSizeException
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteArrayCapacityTest {
    @Test
    fun growthDoesNotWrapAtOneGiBOrTheArrayCeiling() {
        val gib = 1 shl 30
        assertEquals(gib + (Int.MAX_VALUE - gib) / 2, byteArrayCapacity(gib, gib, 1))
        assertEquals(Int.MAX_VALUE, byteArrayCapacity(gib, gib, Int.MAX_VALUE - gib))
        assertEquals(Int.MAX_VALUE, byteArrayCapacity(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, 1))
        assertFailsWith<ArchiveSizeException> { byteArrayCapacity(Int.MAX_VALUE, Int.MAX_VALUE, 1) }
        assertFailsWith<ArchiveSizeException> { byteArrayCapacity(gib, gib, gib) }
        assertFailsWith<ArchiveSizeException> { checkedArraySize(Int.MAX_VALUE.toLong() + 1) }
        assertEquals(Int.MAX_VALUE, checkedArraySize(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun growthRespectsTheBudgetIncludingZeroCapacity() {
        assertEquals(0, byteArrayCapacity(0, 0, 0, 0))
        assertEquals(1, byteArrayCapacity(0, 0, 1, 1))
        assertEquals(7, byteArrayCapacity(4, 4, 3, 7))
        assertFailsWith<ArchiveSizeException> { byteArrayCapacity(4, 4, 4, 7) }
        val out = ByteArrayBuilder(64, 3)
        out.append(byteArrayOf(1, 2, 3))
        assertFailsWith<ArchiveSizeException> { out.append(4) }
        assertContentEquals(byteArrayOf(1, 2, 3), out.toByteArray())
    }

    @Test
    fun invalidSlicesAreRejectedBeforeGrowth() {
        val out = ByteArrayBuilder()
        val data = byteArrayOf(1, 2, 3)
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 0 to Int.MAX_VALUE, Int.MAX_VALUE to 0, 2 to 2)) {
            assertFailsWith<IllegalArgumentException> { out.append(data, offset, length) }
        }
        assertEquals(0, out.size)
        out.append(data)
        repeat(100) { out.append(data, 1, 2) }
        assertContentEquals(data + ByteArray(200) { if (it % 2 == 0) 2 else 3 }, out.toByteArray())
    }

    @Test
    fun zipWriterRejectsUnrepresentableHeadersBeforeIteratingOrAllocating() {
        val files = object : AbstractList<ZipWriter.FileSpec>() {
            override val size = 50_000_000
            override fun get(index: Int): ZipWriter.FileSpec = error("oversized list must not be iterated")
        }
        assertFailsWith<ArchiveSizeException> { ZipWriter.write(files) }
    }
}
