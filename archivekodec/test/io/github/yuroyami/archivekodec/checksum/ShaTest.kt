package io.github.yuroyami.archivekodec.checksum

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** SHA-1 and SHA-256 against FIPS 180 vectors, and the same hash for any split of the input. */
class ShaTest {
    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun publishedVectors() {
        val abc = "abc".encodeToByteArray()
        val twoBlocks = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
        val million = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertContentEquals(hex("a9993e364706816aba3e25717850c26c9cd0d89d"), Sha1.of(abc))
        assertContentEquals(hex("84983e441c3bd26ebaae4aa1f95129e5e54670f1"), Sha1.of(twoBlocks))
        assertContentEquals(hex("34aa973cd4c4daa4f61eeb2bdbad27316534016f"), Sha1.of(million))
        assertContentEquals(hex("da39a3ee5e6b4b0d3255bfef95601890afd80709"), Sha1.of(ByteArray(0)))
        assertContentEquals(hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), Sha256().apply { update(abc) }.digest())
        assertContentEquals(
            hex("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"),
            Sha256().apply { update(twoBlocks) }.digest(),
        )
        assertContentEquals(
            hex("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"),
            Sha256().apply { update(million) }.digest(),
        )
    }

    @Test
    fun anySplitGivesTheSameHash() {
        val random = Random(5)
        repeat(60) {
            val data = random.nextBytes(random.nextInt(0, 400))
            val sha1 = Sha1()
            val sha256 = Sha256()
            var at = 0
            while (at < data.size) {
                val n = random.nextInt(0, minOf(130, data.size - at) + 1)
                sha1.update(data, at, n)
                sha256.update(data, at, n)
                at += n
            }
            assertContentEquals(Sha1.of(data), sha1.digest(), "SHA-1, ${data.size} bytes")
            assertContentEquals(Sha256().apply { update(data) }.digest(), sha256.digest(), "SHA-256, ${data.size} bytes")
        }
    }
}
