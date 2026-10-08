package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.util.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ByteOrderInteropTest {
    @Test fun binaryFieldsAgreeWithJdkByteBuffers() {
        val random = Random(116)
        repeat(128) {
            val bytes = ByteArray(32).also(random::nextBytes)
            val jdk = ByteBuffer.wrap(bytes)
            for (offset in listOf(0, 1, 7, 24)) {
                jdk.order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(jdk.getShort(offset).toInt() and 0xffff, readU16Le(bytes, offset))
                assertEquals(jdk.getInt(offset), readI32Le(bytes, offset))
                assertEquals(jdk.getInt(offset).toLong() and 0xffffffffL, readU32Le(bytes, offset))
                assertEquals(jdk.getLong(offset), readI64Le(bytes, offset))
                jdk.order(ByteOrder.BIG_ENDIAN)
                assertEquals(jdk.getShort(offset).toInt() and 0xffff, readU16Be(bytes, offset))
                assertEquals(jdk.getInt(offset).toLong() and 0xffffffffL, readU32Be(bytes, offset))
            }
            val short = random.nextInt()
            val int = random.nextInt()
            val long = random.nextLong()
            val out = ByteArrayBuilder()
            out.le16(short)
            out.le32(int.toLong())
            out.le64(long)
            out.be32(int.toLong())
            val expected = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(short.toShort()).putInt(int).putLong(long)
                .order(ByteOrder.BIG_ENDIAN).putInt(int).array()
            assertContentEquals(expected, out.toByteArray())
        }
    }
}
