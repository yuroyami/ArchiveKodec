package io.github.yuroyami.kitearchive.archive

import kotlin.test.Test
import kotlin.test.assertEquals

class MagicTest {

    @Test fun gzip() =
        assertEquals(ArchiveFormat.GZIP, Magic.sniff(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)))

    @Test fun zip() =
        assertEquals(ArchiveFormat.ZIP, Magic.sniff(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))

    @Test fun xz() =
        assertEquals(ArchiveFormat.XZ, Magic.sniff(byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00)))

    @Test fun zstd() =
        assertEquals(ArchiveFormat.ZSTD, Magic.sniff(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())))

    @Test fun bzip2() =
        assertEquals(ArchiveFormat.BZIP2, Magic.sniff(byteArrayOf(0x42, 0x5a, 0x68, 0x39)))

    @Test fun lz4() =
        assertEquals(ArchiveFormat.LZ4, Magic.sniff(byteArrayOf(0x04, 0x22, 0x4d, 0x18)))

    @Test fun sevenZip() =
        assertEquals(
            ArchiveFormat.SEVEN_ZIP,
            Magic.sniff(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)),
        )

    @Test fun zlib() =
        assertEquals(ArchiveFormat.ZLIB, Magic.sniff(byteArrayOf(0x78, 0x9c.toByte())))

    @Test fun tar() {
        val b = ByteArray(512)
        "ustar".encodeToByteArray().copyInto(b, 257)
        assertEquals(ArchiveFormat.TAR, Magic.sniff(b))
    }

    @Test fun unknown() =
        assertEquals(ArchiveFormat.UNKNOWN, Magic.sniff(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
}
