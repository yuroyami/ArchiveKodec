package io.github.yuroyami.archivekodec.checksum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XxHash32Test {
    private data class Vector(val length: Int, val zeroSeed: Long, val primeSeed: Long)

    // Published upstream sanity vectors, checked against an independently compiled XXH32.
    // https://github.com/Cyan4973/xxHash/blob/680bf463fa1ca0461b9a7c2dab7556e1f54cf4cf/tests/sanity_test_vectors.h
    private val vectors = listOf(
        Vector(0, 0x02CC5D05L, 0x36B78AE7L),
        Vector(1, 0xCF65B03EL, 0xB4545AA4L),
        Vector(2, 0x1151BEE4L, 0x1EDB879AL),
        Vector(3, 0xC23884F5L, 0x1A269947L),
        Vector(4, 0xA9DE7CE9L, 0x2BAAFE83L),
        Vector(5, 0xEB1734BBL, 0x5874DAB0L),
        Vector(6, 0x659F0C97L, 0x0BCF25C5L),
        Vector(7, 0x5E1056CDL, 0x3ED9D3FCL),
        Vector(8, 0xA3F6F44BL, 0xC2A8E239L),
        Vector(9, 0xFFB82A24L, 0xD35632C6L),
        Vector(10, 0xB1E5032EL, 0x18679D60L),
        Vector(11, 0x0CF2F032L, 0xE0E99838L),
        Vector(12, 0xE89B5F9BL, 0x05A6C4B5L),
        Vector(13, 0xC537DE02L, 0x1298ADD0L),
        Vector(14, 0x1208E7E2L, 0x6AF1D1FEL),
        Vector(15, 0x6B859E14L, 0xAD53090DL),
        Vector(16, 0x93BA3759L, 0xA94FC1E1L),
        Vector(17, 0x89FDC23EL, 0xC9910739L),
        Vector(18, 0x16B53F56L, 0x5E88116EL),
        Vector(19, 0x858FC8EAL, 0x63826A8FL),
        Vector(20, 0x38374A35L, 0xC691A55AL),
        Vector(21, 0x646A88D6L, 0x9668CDABL),
        Vector(22, 0xB5CCC747L, 0x96F6C6A1L),
        Vector(23, 0xCC7B3099L, 0x174910FEL),
        Vector(24, 0xA6276FF0L, 0x7AD49212L),
        Vector(25, 0x221B959BL, 0xA1899908L),
        Vector(26, 0x3F2F5961L, 0x6A936E6BL),
        Vector(27, 0x9501BDDDL, 0x61A4E18BL),
        Vector(28, 0xDA976167L, 0x261F77CFL),
        Vector(29, 0x249CC065L, 0xC8EC8904L),
        Vector(30, 0x36BC3693L, 0xA2800E16L),
        Vector(31, 0x5F40E562L, 0x5C0C3350L),
        Vector(32, 0xD89829ECL, 0xA5C44467L),
        Vector(33, 0x31A427E5L, 0x0DE5B1F9L),
        Vector(255, 0xE048E445L, 0x9A92B749L),
        Vector(256, 0x520CB910L, 0x734E8A8DL),
        Vector(257, 0x9566588EL, 0x4A06AC3FL),
        Vector(512, 0xD485C30AL, 0x6CCF94A9L),
        Vector(513, 0x6C63C50EL, 0xAB0CEFD4L),
        Vector(2367, 0x4C8A9773L, 0x6D5366F6L),
    )

    // Same deterministic input as upstream fillTestBuffer; arithmetic wraps at 64 bits.
    private fun referenceInput(): ByteArray {
        var state = 2654435761uL
        return ByteArray(2367) {
            val byte = (state shr 56).toByte()
            state *= 11400714785074694797uL
            byte
        }
    }

    @Test
    fun emptyAndReferenceTextValues() {
        assertEquals(0x02CC5D05L, XxHash32.hash(ByteArray(0)))
        assertEquals(0x32D153FFL, XxHash32.hash("abc".encodeToByteArray()))
    }

    @Test
    fun upstreamVectorsCoverAccumulatorAndEveryTailBoundary() {
        val input = referenceInput()
        for (vector in vectors) {
            for ((seed, expected) in listOf(0 to vector.zeroSeed, 0x9E3779B1u.toInt() to vector.primeSeed)) {
                val actual = XxHash32.hash(input, length = vector.length, seed = seed)
                assertEquals(expected, actual, "length ${vector.length}, seed $seed")
                assertTrue(actual in 0L..0xFFFFFFFFL, "unsigned 32-bit result: $actual")
            }
        }
    }

    @Test
    fun unalignedSlicesHashOnlyTheSelectedBytes() {
        val input = referenceInput()
        for (offset in listOf(1, 3, 15, 16)) {
            val padded = ByteArray(offset) { 0xA5.toByte() } + input + ByteArray(17) { 0x5A }
            for (vector in vectors) {
                assertEquals(vector.zeroSeed, XxHash32.hash(padded, offset, vector.length),
                    "offset $offset, length ${vector.length}")
                assertEquals(vector.primeSeed,
                    XxHash32.hash(padded, offset, vector.length, 0x9E3779B1u.toInt()),
                    "seeded offset $offset, length ${vector.length}")
            }
        }
    }
}
