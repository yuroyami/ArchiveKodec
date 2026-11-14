package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * One smallest stream for each way a DEFLATE block header can be wrong. zlib 1.3 rejects
 * every one of them, with the message noted beside it.
 */
class InflateErrorTest {
    private fun errorOf(stream: String): InflateError = assertFailsWith<InflateException> { Inflate.inflate(hex(stream)) }.error

    @Test
    fun storedBlockLengthMustMatchItsComplement() {
        // LEN 0 with NLEN 0. zlib: invalid stored block lengths.
        assertEquals(InflateError.INVALID_STORED_BLOCK_LENGTH, errorOf("0100000000"))
    }

    @Test
    fun dynamicHeaderCannotDeclareMoreThan286LiteralLengthCodes() {
        // HLIT 30 declares 287 codes. zlib: too many length or distance symbols.
        assertEquals(InflateError.TOO_MANY_LENGTH_OR_DISTANCE_CODES, errorOf("f500000000"))
    }

    @Test
    fun codeLengthCodeMustBeComplete() {
        // One code of one bit, then three codes of one bit. zlib: invalid code lengths set.
        assertEquals(InflateError.CODE_LENGTHS_CODES_INCOMPLETE, errorOf("050000040000"))
        assertEquals(InflateError.CODE_LENGTHS_CODES_INCOMPLETE, errorOf("050092000000"))
    }

    @Test
    fun repeatNeedsAPreviousLength() {
        // Symbol 16 comes first. zlib: invalid bit length repeat.
        assertEquals(InflateError.REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH, errorOf("050002240000"))
    }

    @Test
    fun repeatCannotRunPastTheDeclaredCodes() {
        // Two runs of 138 zeros for 258 codes. zlib: invalid bit length repeat.
        assertEquals(InflateError.REPEAT_MORE_THAN_SPECIFIED_LENGTHS, errorOf("050080e4ff1f0000"))
    }

    @Test
    fun endOfBlockNeedsACode() {
        // All 258 lengths are zero. zlib: invalid code -- missing end-of-block.
        assertEquals(InflateError.MISSING_END_OF_BLOCK_CODE, errorOf("050080e47f1b0000"))
    }

    @Test
    fun literalLengthCodeCanBeNeitherOverSubscribedNorIncomplete() {
        // Three codes of one bit, then two codes of two bits. zlib: invalid literal/lengths set.
        assertEquals(InflateError.INVALID_LITERAL_LENGTH_CODE_LENGTHS, errorOf("05c001090000000010fe9f160000"))
        assertEquals(InflateError.INVALID_LITERAL_LENGTH_CODE_LENGTHS, errorOf("0580010900000040fc5f2d0000"))
    }

    @Test
    fun distanceCodeCannotBeOverSubscribed() {
        // Three distance codes of one bit. zlib: invalid distances set.
        assertEquals(InflateError.INVALID_DISTANCE_CODE_LENGTHS, errorOf("05c201090000000010ff57030000"))
    }

    @Test
    fun theTwoLegalIncompleteDistanceCodesDecode() {
        // RFC 1951 3.2.7: a block of literals may have no distance code, or one code of one bit.
        assertContentEquals(byteArrayOf(0, 0), Inflate.inflate(hex("05c001090000000010ff578b")))
        assertContentEquals(byteArrayOf(0), Inflate.inflate(hex("05c001090000000010ff5723")))
    }
}
