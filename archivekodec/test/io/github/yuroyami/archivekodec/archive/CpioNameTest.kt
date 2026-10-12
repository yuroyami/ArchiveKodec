package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CpioNameTest {
    private fun newc(name: ByteArray, data: ByteArray = byteArrayOf()): ByteArray {
        val fields = longArrayOf(1, 0x81A4, 0, 0, 1, 0, data.size.toLong(), 0, 0, 0, 0, name.size.toLong(), 0)
        val header = ("070701" + fields.joinToString("") { it.toString(16).padStart(8, '0') }).encodeToByteArray()
        val named = header + name
        return named + ByteArray((4 - named.size % 4) % 4) + data + ByteArray((4 - data.size % 4) % 4)
    }

    @Test
    fun paddingDoesNotEnterTheNameOrChangePayloadAlignment() {
        val data = "DATA".encodeToByteArray()
        for (padding in listOf(1, 2, 3, 4, 7)) {
            val bytes = newc("file.txt".encodeToByteArray() + ByteArray(padding), data) +
                newc("after\u0000".encodeToByteArray(), byteArrayOf(65)) +
                newc("TRAILER!!!".encodeToByteArray() + ByteArray(padding)) +
                newc("outside\u0000".encodeToByteArray())
            val reader = CpioReader(ByteArrayRandomAccessSource(bytes))
            val entries = reader.entries()
            assertEquals(listOf("file.txt", "after"), entries.map { it.name })
            assertContentEquals(data, reader.read(entries[0]))
            assertContentEquals(byteArrayOf(65), reader.read(entries[1]))
        }
    }

    @Test
    fun unterminatedNameFieldRemainsMalformed() {
        assertFailsWith<ArchiveFormatException> {
            CpioReader(ByteArrayRandomAccessSource(newc("bad".encodeToByteArray()))).entries()
        }
    }
}
