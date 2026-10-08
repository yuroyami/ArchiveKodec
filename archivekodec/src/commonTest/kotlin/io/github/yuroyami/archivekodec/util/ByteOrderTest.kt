package io.github.yuroyami.archivekodec.util

import io.github.yuroyami.archivekodec.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ByteOrderTest {
    @Test fun readsUnalignedSignedAndUnsignedFields() {
        val bytes = hex("0098badcfe674523f100")
        assertEquals(0xba98, readU16Le(bytes, 1))
        assertEquals(0x98ba, readU16Be(bytes, 1))
        assertEquals(0xfedcba98.toInt(), readI32Le(bytes, 1))
        assertEquals(0xfedcba98L, readU32Le(bytes, 1))
        assertEquals(0x98badcfeL, readU32Be(bytes, 1))
        assertEquals(0xf1234567fedcba98uL, readI64Le(bytes, 1).toULong())
        assertEquals(Long.MIN_VALUE, readI64Le(hex("0000000000000080"), 0))
        assertEquals(-1L, readI64Le(hex("ffffffffffffffff"), 0))
    }

    @Test fun writesExactBytesIncludingHighBits() {
        val out = ByteArrayBuilder()
        out.le16(0x80ff)
        out.le32(0xfedcba98L)
        out.le64(Long.MIN_VALUE)
        out.be32(0x89abcdefL)
        assertContentEquals(hex("ff8098badcfe000000000000008089abcdef"), out.toByteArray())
    }

    @Test fun variableWidthFieldsRemainUnsigned() {
        val bytes = hex("0098badcfeff")
        val values = listOf(0x98L, 0xba98L, 0xdcba98L, 0xfedcba98L)
        for (count in 1..4) {
            assertEquals(values[count - 1], readUIntLe(bytes, 1, count))
            val out = ByteArrayBuilder()
            out.leBytes(values[count - 1], count)
            assertContentEquals(bytes.copyOfRange(1, count + 1), out.toByteArray())
        }
    }
}
