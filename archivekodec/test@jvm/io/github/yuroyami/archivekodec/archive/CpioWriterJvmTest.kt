package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CpioWriterJvmTest {
    @Test
    fun crcByteSumWrapsAtThirtyTwoBits() {
        // 16,843,010 * 255 = 2^32 + 254; exercise a real overflow with unsigned bytes.
        val data = ByteArray(16_843_010) { 0xff.toByte() }
        val archive = CpioWriter.write(listOf(CpioWriter.FileSpec("wrapped", data)), CpioWriter.Dialect.CRC)
        assertEquals("000000fe", archive.decodeToString(102, 110))
        val reader = CpioReader(ByteArrayRandomAccessSource(archive))
        try {
            assertContentEquals(data, reader.read(reader.entries().single()))
        } finally {
            reader.close()
        }
    }
}
