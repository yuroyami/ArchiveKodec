package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import io.github.yuroyami.archivekodec.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SnappyTest {

    @Test
    fun decodesLiteralVector() {
        // varint(4) | literal len-1=3 | "aaaa"
        assertEquals("aaaa", Snappy.decompress(hex("040c61616161")).decodeToString())
    }

    @Test
    fun decodesCopyVector() {
        // varint(6) | literal "abc" | copy(len=3, offset=3) → "abcabc"
        assertEquals("abcabc", Snappy.decompress(hex("06086162630a0300")).decodeToString())
    }

    @Test
    fun roundTrip() {
        for (s in listOf(ByteArray(0), "x".encodeToByteArray(), ("snappy ".repeat(500)).encodeToByteArray())) {
            assertContentEquals(s, Snappy.decompress(Snappy.compress(s)))
        }
    }

    @Test
    fun decodesLibsnappyStreamWithHighOffsetBits() {
        // libsnappy 1.2.2: four tag-10 copies, then tag-01 length 5 at offset 300.
        val prefix = hex(
            "255cd1a66276fab205cfa4cbf245daa8010d48f4b85ab37dfac5a6a304ccae22b63c6515a6ad137932c4016faa7e76e5" +
            "44de76f78c8af14cccc72a4cdf331e4530b05d3c99ede369503363c850c93e21c48ec2b51516b831f09ba8a9c806f0a3" +
            "05e31f63c1ef11450e5c64a19924fb48d698bf46759babba9ea7999b93c495c1beab0fbcc68eafcb83cc7a34842b98a0" +
            "1232774ae0f6228332207f12801881648791c67b71ec5b93e4dc5a8074c201dbe1075f4b3b45c8983ded25cfec0d47c7" +
            "7bfbd50b43dec2d93a912c989d30e08ec8fcb060a61132282b4bbb173bdc960225472d73de144c51416210c1d19093c4" +
            "198302d47439a5e7a23e5e74cc33dca89318190796db15d3c26dfb09fd7858c2595952a5a359e2df55f5c45413adab94" +
            "89ac9dc3cb6964624ebeb84e",
        )
        val vector = hex(
            "c204f43401255cd1a66276fab205cfa4cbf245daa8010d48f4b85ab37dfac5a6a304ccae22b63c6515a6ad137932c401" +
            "6faa7e76e544de76f78c8af14cccc72a4cdf331e4530b05d3c99ede369503363c850c93e21c48ec2b51516b831f09ba8" +
            "a9c806f0a305e31f63c1ef11450e5c64a19924fb48d698bf46759babba9ea7999b93c495c1beab0fbcc68eafcb83cc7a" +
            "34842b98a01232774ae0f6228332207f12801881648791c67b71ec5b93e4dc5a8074c201dbe1075f4b3b45c8983ded25" +
            "cfec0d47c77bfbd50b43dec2d93a912c989d30e08ec8fcb060a61132282b4bbb173bdc960225472d73de144c51416210" +
            "c1d19093c4198302d47439a5e7a23e5e74cc33dca89318190796db15d3c26dfb09fd7858c2595952a5a359e2df55f5c4" +
            "5413adab9489ac9dc3cb6964624ebeb84e255cd1a66276fab205fe2c01fe2c01fe2c01ee2c01252c2c666f726569676e" +
            "2d7461696c",
        )
        val expected = prefix + prefix.copyOf(266) + "foreign-tail".encodeToByteArray()
        assertContentEquals(expected, Snappy.decompress(vector))
    }

    @Test
    fun decodesForeignCopyAndOverlapVectors() {
        // Published raw streams, each independently decoded by libsnappy 1.2.2.
        val vectors = listOf(
            "14083030311d0114303031313031" to "00111111111111001101".encodeToByteArray(),
            "640061fe01008a0100" to ByteArray(100) { 'a'.code.toByte() },
            "06086162630b03000000" to "abcabc".encodeToByteArray(),
        )
        for ((encoded, expected) in vectors) assertContentEquals(expected, Snappy.decompress(hex(encoded)))
    }

}
