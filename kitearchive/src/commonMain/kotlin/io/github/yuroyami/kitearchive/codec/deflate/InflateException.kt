package io.github.yuroyami.kitearchive.codec.deflate

/**
 * Error codes for DEFLATE and gzip inflation. The integer [code] is exactly the value
 * Mark Adler's `puff()` returns, plus two wrapper-level codes `puff()` never produces.
 * A reader who cross-references the canonical C source can map an exception back to
 * the branch that raised it.
 */
enum class InflateError(val code: Int, val message: String) {
    /** puff 2: `bits()`/`decode()` read past the end of the input (longjmp path). */
    DATA_DID_NOT_TERMINATE(2, "available inflate data did not terminate"),

    /** puff 1: only reachable with a bounded output buffer. This decoder grows instead. */
    SPACE_EXHAUSTED(1, "output space exhausted before completing inflate"),

    /** puff -1: block type field was 3 (reserved). */
    INVALID_BLOCK_TYPE(-1, "invalid block type (type == 3)"),

    /** puff -2: stored block LEN did not match ~LEN. */
    INVALID_STORED_BLOCK_LENGTH(-2, "stored block length did not match one's complement"),

    /** puff -3: dynamic header declared more codes than the format allows. */
    TOO_MANY_LENGTH_OR_DISTANCE_CODES(-3, "dynamic block code description: too many length or distance codes"),

    /** puff -4: the code-length code itself was not a complete set. */
    CODE_LENGTHS_CODES_INCOMPLETE(-4, "dynamic block code description: code lengths codes incomplete"),

    /** puff -5: a repeat-previous (16) instruction with no previous length. */
    REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH(-5, "dynamic block code description: repeat lengths with no first length"),

    /** puff -6: a run-length instruction overran the declared symbol count. */
    REPEAT_MORE_THAN_SPECIFIED_LENGTHS(-6, "dynamic block code description: repeat more than specified lengths"),

    /** puff -7: literal/length code lengths formed an invalid (over or incomplete) set. */
    INVALID_LITERAL_LENGTH_CODE_LENGTHS(-7, "dynamic block code description: invalid literal/length code lengths"),

    /** puff -8: distance code lengths formed an invalid set. */
    INVALID_DISTANCE_CODE_LENGTHS(-8, "dynamic block code description: invalid distance code lengths"),

    /** puff -9: the dynamic block had no end-of-block symbol. */
    MISSING_END_OF_BLOCK_CODE(-9, "dynamic block code description: missing end-of-block code"),

    /** puff -10: an invalid Huffman code was decoded in a fixed or dynamic block. */
    INVALID_LITERAL_OR_DISTANCE_CODE(-10, "invalid literal/length or distance code in fixed or dynamic block"),

    /** puff -11: a back-reference distance pointed before the start of output. */
    DISTANCE_TOO_FAR_BACK(-11, "distance is too far back in fixed or dynamic block"),

    /** wrapper-level: the gzip or zlib header magic, method or flags were malformed. */
    INVALID_GZIP_HEADER(100, "invalid gzip/zlib header"),

    /** wrapper-level: inflated output would exceed the caller-supplied maximum. */
    INFLATED_DATA_TOO_LARGE(101, "inflated data too large"),

    /** Catch-all. */
    UNKNOWN(999, "unknown inflate error"),
    ;

    companion object {
        /** Map a raw `puff()` return value to the corresponding [InflateError]. */
        fun fromPuff(code: Int): InflateError = when (code) {
            2 -> DATA_DID_NOT_TERMINATE
            1 -> SPACE_EXHAUSTED
            -1 -> INVALID_BLOCK_TYPE
            -2 -> INVALID_STORED_BLOCK_LENGTH
            -3 -> TOO_MANY_LENGTH_OR_DISTANCE_CODES
            -4 -> CODE_LENGTHS_CODES_INCOMPLETE
            -5 -> REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH
            -6 -> REPEAT_MORE_THAN_SPECIFIED_LENGTHS
            -7 -> INVALID_LITERAL_LENGTH_CODE_LENGTHS
            -8 -> INVALID_DISTANCE_CODE_LENGTHS
            -9 -> MISSING_END_OF_BLOCK_CODE
            -10 -> INVALID_LITERAL_OR_DISTANCE_CODE
            -11 -> DISTANCE_TOO_FAR_BACK
            else -> UNKNOWN
        }
    }
}

/**
 * Thrown by [Inflate] and the gzip/zlib framers when decompression fails. Carries
 * the specific [error] so callers can branch on the exact failure mode.
 */
class InflateException(val error: InflateError) : Exception(error.message)
