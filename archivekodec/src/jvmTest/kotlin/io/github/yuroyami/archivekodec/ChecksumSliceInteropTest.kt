package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.checksum.*
import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Adler32 as JdkAdler32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChecksumSliceInteropTest {
    private val pairs: List<Pair<() -> Checksum, () -> java.util.zip.Checksum>> = listOf(
        ::Crc32 to ::CRC32, ::Crc32c to ::CRC32C, ::Adler32 to ::JdkAdler32,
    )

    @Test
    fun validSlicesAndIncrementalPartitionsMatchJdkChecksums() {
        val data = ByteArray(17000) { (it * 71 + 255).toByte() }
        for ((create, reference) in pairs) {
            for (offset in listOf(0, 1, 3, 15, 5551, 5552)) {
                for (length in listOf(0, 1, 15, 16, 17, 5551, 5552, 5553, 11000)) {
                    val expected = reference().also { it.update(data, offset, length) }.value
                    assertEquals(expected, create().also { it.update(data, offset, length) }.value())
                }
            }
            val actual = create()
            val expected = reference()
            var offset = 0
            val chunks = listOf(1, 15, 16, 17, 5551, 5552, 5553, 3)
            var index = 0
            while (offset < data.size) {
                val length = minOf(chunks[index++ % chunks.size], data.size - offset)
                actual.update(data, offset, length)
                expected.update(data, offset, length)
                assertEquals(expected.value, actual.value())
                offset += length
            }
        }
    }

    @Test
    fun rejectedUpdatesPreserveStateLikeJdkAndCanResume() {
        val data = ByteArray(6000) { 255.toByte() }
        for ((create, reference) in pairs) {
            val actual = create().also { it.update(data, 0, 100) }
            val expected = reference().also { it.update(data, 0, 100) }
            for ((offset, length) in listOf(0 to -1, 1 to Int.MAX_VALUE, -1 to 1, 0 to 6001, 6001 to 0)) {
                assertFailsWith<IllegalArgumentException> { actual.update(data, offset, length) }
                assertFailsWith<IndexOutOfBoundsException> { expected.update(data, offset, length) }
                assertEquals(expected.value, actual.value())
            }
            actual.update(data, 100, 5900)
            expected.update(data, 100, 5900)
            assertEquals(expected.value, actual.value())
        }
    }

    @Test
    fun concreteAndInterfaceSlicesDeclareTheSameArgumentError() {
        for (type in listOf(Checksum::class.java, Crc32::class.java, Crc32c::class.java, Crc64::class.java, Adler32::class.java)) {
            val method = type.getMethod("update", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            assertTrue(IllegalArgumentException::class.java in method.exceptionTypes, method.toString())
        }
        val hash = XxHash32::class.java.getMethod("hash", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        assertTrue(IllegalArgumentException::class.java in hash.exceptionTypes)
    }
}
