package io.github.yuroyami.archivekodec.checksum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChecksumSliceTest {
    private val factories: List<() -> Checksum> = listOf(::Crc32, ::Crc32c, ::Crc64, ::Adler32)
    private val data = ByteArray(32) { (it * 17).toByte() }
    private val prefix = "prefix".encodeToByteArray()
    private val invalid = listOf(-1 to 0, -1 to 1, 0 to -1, 0 to Int.MIN_VALUE,
        1 to Int.MAX_VALUE, Int.MAX_VALUE to 0, Int.MAX_VALUE to 1, Int.MIN_VALUE to 0,
        33 to 0, 32 to 1, 2 to 32, 0 to 33)

    @Test
    fun sliceArithmeticIsSafeAtTheLargestArrayBoundary() {
        assertEquals(Int.MAX_VALUE, checkedChecksumEnd(Int.MAX_VALUE, 1, Int.MAX_VALUE - 1))
        assertEquals(Int.MAX_VALUE, checkedChecksumEnd(Int.MAX_VALUE, Int.MAX_VALUE, 0))
        assertEquals(Int.MAX_VALUE, checkedChecksumEnd(Int.MAX_VALUE, Int.MAX_VALUE - 4, 4))
        for ((offset, length) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MIN_VALUE to 0, -1 to 0)) {
            assertFailsWith<IllegalArgumentException> { checkedChecksumEnd(Int.MAX_VALUE, offset, length) }
        }
    }

    @Test
    fun invalidExplicitUpdatesPreserveStateAndAllowRecovery() {
        for (create in factories) {
            val checksum = create().also { it.update(prefix) }
            val before = checksum.value()
            for ((offset, length) in invalid) {
                assertFailsWith<IllegalArgumentException> { checksum.update(data, offset, length) }
                assertEquals(before, checksum.value())
            }
            checksum.update(data, 3, 17)
            assertEquals(create().also { it.update(prefix + data.copyOfRange(3, 20)) }.value(), checksum.value())
        }
    }

    @Test
    fun implicitLengthsSelectTheSuffixOnInterfaceAndConcreteCalls() {
        for (create in factories) for (offset in 0..data.size) {
            val actual = create().also { it.update(data, offset = offset) }.value()
            val expected = create().also { it.update(data.copyOfRange(offset, data.size)) }.value()
            assertEquals(expected, actual)
        }
        assertEquals(Crc32.of(data.copyOfRange(3, data.size)), Crc32().also { it.update(data, offset = 3) }.value())
        assertEquals(Crc32c.of(data.copyOfRange(3, data.size)), Crc32c().also { it.update(data, offset = 3) }.value())
        assertEquals(Crc64.of(data.copyOfRange(3, data.size)), Crc64().also { it.update(data, offset = 3) }.value())
        assertEquals(Adler32.of(data.copyOfRange(3, data.size)), Adler32().also { it.update(data, offset = 3) }.value())
    }

    @Test
    fun invalidImplicitOffsetsPreserveStreamingState() {
        for (create in factories) {
            val checksum = create().also { it.update(prefix) }
            val before = checksum.value()
            for (offset in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, data.size + 1)) {
                assertFailsWith<IllegalArgumentException> { checksum.update(data, offset = offset) }
                assertEquals(before, checksum.value())
            }
        }
    }

    @Test
    fun allSmallValidSlicesHashOnlySelectedBytes() {
        for (create in factories) for (offset in 0..data.size) for (length in 0..data.size - offset) {
            val checksum = create().also { it.update(prefix); it.update(data, offset, length) }
            val expected = create().also { it.update(prefix + data.copyOfRange(offset, offset + length)) }.value()
            assertEquals(expected, checksum.value(), "offset $offset length $length")
        }
    }

    @Test
    fun emptyAndEofSlicesDoNotChangeStreamingState() {
        for (create in factories) {
            val checksum = create().also { it.update(prefix) }
            val before = checksum.value()
            checksum.update(byteArrayOf())
            checksum.update(data, data.size, 0)
            checksum.update(data, offset = data.size)
            assertEquals(before, checksum.value())
            checksum.reset()
            assertEquals(create().value(), checksum.value())
        }
    }

    @Test
    fun xxHashRejectsInvalidExplicitAndImplicitRanges() {
        for (seed in listOf(0, -1, Int.MIN_VALUE)) {
            for ((offset, length) in invalid) {
                assertFailsWith<IllegalArgumentException> { XxHash32.hash(data, offset, length, seed) }
            }
            for (offset in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, data.size + 1)) {
                assertFailsWith<IllegalArgumentException> { XxHash32.hash(data, offset = offset, seed = seed) }
            }
        }
    }

    @Test
    fun xxHashValidSlicesAndSuffixesMatchCopiedInput() {
        for (seed in listOf(0, -1, Int.MIN_VALUE, 0x9E3779B1u.toInt())) {
            for (offset in 0..data.size) {
                assertEquals(XxHash32.hash(data.copyOfRange(offset, data.size), seed = seed),
                    XxHash32.hash(data, offset = offset, seed = seed))
                for (length in 0..data.size - offset) {
                    assertEquals(XxHash32.hash(data.copyOfRange(offset, offset + length), seed = seed),
                        XxHash32.hash(data, offset, length, seed))
                }
            }
        }
    }
}
