package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.tar.TarReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TarRecordTypeTest {
    private fun header(name: String, type: Char, sizeField: ByteArray): ByteArray {
        val h = ByteArray(512)
        name.encodeToByteArray().copyInto(h)
        "0000755".encodeToByteArray().copyInto(h, 100)
        sizeField.copyInto(h, 124)
        h[156] = type.code.toByte()
        "ustar\u000000".encodeToByteArray().copyInto(h, 257)
        for (i in 148 until 156) h[i] = 32
        h.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0').encodeToByteArray().copyInto(h, 148)
        h[154] = 0
        h[155] = 32
        return h
    }

    @Test
    fun nonDataTypesDoNotSkipTheFollowingFile() {
        val body = ByteArray(512).also { "DATA".encodeToByteArray().copyInto(it) }
        val regular = header("after.txt", '0', "00000000004".encodeToByteArray()) + body
        val sizes = listOf(
            "00000001000".encodeToByteArray(),
            "77777777777".encodeToByteArray(),
            byteArrayOf(0x80.toByte(), 0, 0, 0, 0x7F, -1, -1, -1, -1, -1, -1, -1),
        )
        for (type in listOf('3', '4', '5', '6')) {
            for (size in sizes) {
                val bytes = header(if (type == '5') "d/" else "special", type, size) + regular + ByteArray(1024)
                val reader = TarReader(ByteArrayRandomAccessSource(bytes))
                val entries = reader.entries()
                assertEquals(if (type == '5') 2 else 1, entries.size)
                val file = entries.last()
                assertEquals("after.txt", file.name)
                assertEquals(4L, file.size)
                assertContentEquals("DATA".encodeToByteArray(), reader.read(file))
                if (type == '5') {
                    assertTrue(entries.first().isDirectory)
                    assertEquals(0L, entries.first().size)
                    assertContentEquals(byteArrayOf(), reader.read(entries.first()))
                }
            }
        }
    }
}
