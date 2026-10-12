package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class StoredArchiveRatioTest {
    @Test
    fun uncompressedReadersHonorTheirOneToOneRatioBeforeReadingData() {
        val data = byteArrayOf(65)
        for ((bytes, factory) in archives(data)) {
            var reads = 0
            val delegate = ByteArrayRandomAccessSource(bytes)
            val source = object : RandomAccessSource {
                override val size = delegate.size
                override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                    reads++
                    return delegate.read(offset, into, intoOffset, length)
                }
            }
            val reader = factory(source)
            val entry = reader.entries().single()
            val before = reads
            assertFailsWith<DecompressionBombException> { reader.read(entry, ArchiveLimits { maxCompressionRatio = 0 }) }
            kotlin.test.assertEquals(before, reads, "ratio rejection must precede payload reads")
            assertContentEquals(data, reader.read(entry, ArchiveLimits { maxCompressionRatio = 1 }))
        }
    }

    @Test
    fun emptyStoredEntriesAreValidUnderZeroRatio() {
        for ((bytes, factory) in archives(byteArrayOf())) {
            val reader = factory(ByteArrayRandomAccessSource(bytes))
            assertContentEquals(byteArrayOf(), reader.read(reader.entries().single(), ArchiveLimits { maxCompressionRatio = 0 }))
        }
    }

    private fun archives(data: ByteArray): List<Pair<ByteArray, (RandomAccessSource) -> ArchiveReader>> {
        val arHeader = ByteArray(60) { 32 }
        "a/".encodeToByteArray().copyInto(arHeader)
        data.size.toString().encodeToByteArray().copyInto(arHeader, 48)
        arHeader[58] = 0x60; arHeader[59] = 10
        val ar = "!<arch>\n".encodeToByteArray() + arHeader + data + if (data.size % 2 != 0) byteArrayOf(10) else byteArrayOf()

        val cpioHeader = ByteArray(110) { 48 }
        "070701".encodeToByteArray().copyInto(cpioHeader)
        "000081a4".encodeToByteArray().copyInto(cpioHeader, 14)
        data.size.toString(16).padStart(8, '0').encodeToByteArray().copyInto(cpioHeader, 54)
        "00000002".encodeToByteArray().copyInto(cpioHeader, 94)
        val cpio = cpioHeader + byteArrayOf(97, 0) + data + ByteArray((4 - data.size % 4) % 4)

        val tarHeader = ByteArray(512)
        tarHeader[0] = 97
        data.size.toString(8).padStart(11, '0').encodeToByteArray().copyInto(tarHeader, 124)
        tarHeader[156] = 48
        tarHeader.fill(32, 148, 156)
        tarHeader.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0').encodeToByteArray().copyInto(tarHeader, 148)
        tarHeader[154] = 0
        val tar = tarHeader + data + ByteArray((512 - data.size % 512) % 512) + ByteArray(1024)
        return listOf(ar to ::ArReader, cpio to ::CpioReader, tar to ::TarReader)
    }
}
