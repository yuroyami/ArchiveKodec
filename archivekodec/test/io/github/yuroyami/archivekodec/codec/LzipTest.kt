package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalEncodingApi::class)
class LzipTest {
    // lzip 1.24: `printf 'hello ' | lzip`, then `printf 'world\n' | lzip`, concatenated.
    private val twoMembers = Base64.decode(
        "TFpJUAEMADQZSe6N6T8K3///3RIAAPb5ge0GAAAAAAAAACoAAAAAAAAATFpJUAEMADubyqt0BGO6M3///lVIAKhhON0GAAAAAAAAACoAAAAAAAAA",
    )

    @Test
    fun readsTheReferenceToolsMembersAndRoundTrips() {
        assertContentEquals("hello world\n".encodeToByteArray(), Codecs.LZIP.decompress(twoMembers))
        assertEquals(ArchiveFormat.LZIP, ArchiveKodec.sniff(twoMembers))
        assertEquals(Codecs.LZIP, Codecs.byFormat(ArchiveFormat.LZIP))
        val text = "lzip member trailer window crc ".repeat(3000).encodeToByteArray()
        for (data in listOf(ByteArray(0), "x".encodeToByteArray(), text)) {
            assertContentEquals(data, Codecs.LZIP.decompress(Codecs.LZIP.compress(data)))
        }
        assertContentEquals(text, ArchiveKodec.decompress(Codecs.LZIP.compress(text)))
    }

    @Test
    fun damageAndLimitsFail() {
        val crc = twoMembers.copyOf().also { it[22] = (it[22].toInt() xor 1).toByte() }
        assertFailsWith<ArchiveFormatException> { Codecs.LZIP.decompress(crc) }
        val memberSize = twoMembers.copyOf().also { it[34] = (it[34] + 1).toByte() }
        assertFailsWith<ArchiveFormatException> { Codecs.LZIP.decompress(memberSize) }
        assertFailsWith<ArchiveFormatException> { Codecs.LZIP.decompress(twoMembers.copyOf(30)) }
        assertFailsWith<ArchiveFormatException> { Codecs.LZIP.decompress(twoMembers + byteArrayOf(1, 2, 3)) }
        val zeros = Codecs.LZIP.compress(ByteArray(100_000))
        assertFailsWith<DecompressionBombException> { Codecs.LZIP.decompress(zeros, ArchiveLimits { maxUncompressedBytes = 50_000 }) }
        assertFailsWith<DecompressionBombException> { Codecs.LZIP.decompress(zeros, ArchiveLimits { maxCompressionRatio = 10 }) }
    }

    @Test
    fun aTarLzOpensInOneCall() {
        val tar = TarWriter.write(listOf(TarWriter.FileSpec("a.txt", "inside".encodeToByteArray())))
        ArchiveKodec.open(Codecs.LZIP.compress(tar)).use { reader ->
            assertEquals(ArchiveFormat.TAR, reader.format)
            assertContentEquals("inside".encodeToByteArray(), reader.read(reader.entries().single()))
        }
    }
}
