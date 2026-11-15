package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.ArchiveAuthenticationException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchivePasswordException
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipEncryption
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/** The built-in provider: published vectors, agreement with the table-driven [TestCrypto], and archives. */
class BuiltInCryptoProviderTest {
    private val crypto = CryptoProvider.DEFAULT
    private val reference = TestCrypto()

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The S-box from its definition: the inverse in GF(2^8), then the affine transform. */
    private fun referenceSbox(x: Int): Int {
        fun mul(a: Int, b: Int): Int {
            var p = 0
            var aa = a
            var bb = b
            while (bb != 0) {
                if (bb and 1 != 0) p = p xor aa
                aa = (aa shl 1) xor (if (aa and 0x80 != 0) 0x11B else 0)
                bb = bb ushr 1
            }
            return p
        }
        val inv = if (x == 0) 0 else (1..255).first { mul(x, it) == 1 }
        var s = inv
        for (i in 1..4) s = s xor (((inv shl i) or (inv ushr (8 - i))) and 0xFF)
        return s xor 0x63
    }

    @Test
    fun sboxMatchesItsDefinitionForEveryByte() {
        for (x in 0 until 256) {
            val word = x or (x shl 8) or (x shl 16) or (x shl 24)
            assertEquals(referenceSbox(x) * 0x01010101, ConstantTimeAes.subWord(word), "S($x)")
        }
    }

    @Test
    fun inverseLayersUndoTheForwardLayers() {
        val random = Random(1)
        repeat(100) {
            val q = IntArray(8) { random.nextInt() }
            assertContentEquals(q, q.copyOf().also(ConstantTimeAes::sbox).also(ConstantTimeAes::invSbox))
            assertContentEquals(q, q.copyOf().also(ConstantTimeAes::mixColumns).also(ConstantTimeAes::invMixColumns))
        }
    }

    @Test
    fun primitivesMatchPublishedVectors() {
        // FIPS 197 appendix C, RFC 2202 and RFC 6070.
        val plain = hex("00112233445566778899aabbccddeeff")
        val vectors = listOf(
            16 to "69c4e0d86a7b0430d8cdb78070b4c55a",
            24 to "dda97ca4864cdfe06eaf70a0ec0d7191",
            32 to "8ea2b7ca516745bfeafc49904b496089",
        )
        for ((size, cipher) in vectors) {
            val key = ByteArray(size) { it.toByte() }
            assertContentEquals(hex(cipher), crypto.aesEcbEncrypt(key, plain), "AES-${8 * size} encrypt")
            // CBC with a zero IV over one block is the inverse cipher.
            assertContentEquals(plain, crypto.aesCbcDecrypt(key, ByteArray(16), hex(cipher)), "AES-${8 * size} decrypt")
        }
        assertContentEquals(hex("b617318655057264e28bc0b6fb378c8ef146be00"), crypto.hmacSha1(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()))
        // RFC 2202 test case 6: a key longer than the block size.
        assertContentEquals(
            hex("aa4ae5e15272d00e95705637ce8a3b55ed402112"),
            crypto.hmacSha1(ByteArray(80) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()),
        )
        assertContentEquals(
            hex("0c60c80f961f0e71f3a9b524af6012062fe037a6"),
            crypto.pbkdf2HmacSha1("password".encodeToByteArray(), "salt".encodeToByteArray(), 1, 20),
        )
        assertContentEquals(
            hex("4b007901b765489abead49d926f721d065a429c1"),
            crypto.pbkdf2HmacSha1("password".encodeToByteArray(), "salt".encodeToByteArray(), 4096, 20),
        )
        assertContentEquals(
            hex("3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038"),
            crypto.pbkdf2HmacSha1("passwordPASSWORDpassword".encodeToByteArray(), "saltSALTsaltSALTsaltSALTsaltSALTsalt".encodeToByteArray(), 4096, 25),
        )
        // NIST SP 800-38A F.1.1 (ECB-AES128, four blocks) and F.2.2 (CBC-AES128 decryption).
        val key128 = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val fourBlocks = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")
        assertContentEquals(
            hex("3ad77bb40d7a3660a89ecaf32466ef97f5d3d58503b9699de785895a96fdbaaf43b1cd7f598ece23881b00e3ed0306887b0c785e27e8ad3f8223207104725dd4"),
            crypto.aesEcbEncrypt(key128, fourBlocks),
        )
        assertContentEquals(
            fourBlocks,
            crypto.aesCbcDecrypt(
                key128,
                hex("000102030405060708090a0b0c0d0e0f"),
                hex("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b273bed6b8e3c1743b7116e69e222295163ff1caa1681fac09120eca307586e1a7"),
            ),
        )
    }

    @Test
    fun aesMatchesTheReferenceForOddBlockCounts() {
        val random = Random(2)
        for (blocks in 0..9) repeat(6) {
            val key = random.nextBytes(listOf(16, 24, 32)[it % 3])
            val data = random.nextBytes(16 * blocks)
            val iv = random.nextBytes(16)
            assertContentEquals(reference.aesEcbEncrypt(key, data), crypto.aesEcbEncrypt(key, data), "ECB, $blocks blocks")
            assertContentEquals(reference.aesCbcDecrypt(key, iv, data), crypto.aesCbcDecrypt(key, iv, data), "CBC, $blocks blocks")
        }
    }

    @Test
    fun hmacAndPbkdf2MatchTheReferenceAtBlockBoundaries() {
        val random = Random(3)
        // SHA-1 pads at 56 bytes and works in 64-byte blocks; HMAC hashes keys longer than 64 bytes first.
        val lengths = listOf(0, 1, 19, 20, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 129, 200)
        for (keyLength in lengths) for (dataLength in lengths) {
            val key = random.nextBytes(keyLength)
            val data = random.nextBytes(dataLength)
            assertContentEquals(reference.hmacSha1(key, data), crypto.hmacSha1(key, data), "HMAC, key $keyLength, data $dataLength")
        }
        for (passwordLength in listOf(0, 1, 8, 64, 65, 100)) for (length in listOf(0, 1, 16, 20, 21, 40, 41, 66)) {
            val password = random.nextBytes(passwordLength)
            val salt = random.nextBytes(random.nextInt(0, 70))
            val iterations = random.nextInt(1, 12)
            assertContentEquals(
                reference.pbkdf2HmacSha1(password, salt, iterations, length),
                crypto.pbkdf2HmacSha1(password, salt, iterations, length),
                "PBKDF2, password $passwordLength, length $length, $iterations iterations",
            )
        }
    }

    @Test
    fun rejectsBadSizes() {
        assertFailsWith<IllegalArgumentException> { crypto.aesEcbEncrypt(ByteArray(15), ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { crypto.aesEcbEncrypt(ByteArray(16), ByteArray(17)) }
        assertFailsWith<IllegalArgumentException> { crypto.aesCbcDecrypt(ByteArray(16), ByteArray(15), ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { crypto.aesCbcDecrypt(ByteArray(16), ByteArray(16), ByteArray(17)) }
        assertFailsWith<IllegalArgumentException> { crypto.pbkdf2HmacSha1(ByteArray(1), ByteArray(1), 0, 20) }
        assertFailsWith<IllegalArgumentException> { crypto.pbkdf2HmacSha1(ByteArray(1), ByteArray(1), 1, -1) }
        assertFailsWith<IllegalArgumentException> { crypto.randomBytes(-1) }
    }

    @Test
    fun randomBytesHaveTheRequestedSizeAndDiffer() {
        assertEquals(0, crypto.randomBytes(0).size)
        // Above the 65,536-byte limit of one getRandomValues call.
        val large = crypto.randomBytes(70_000)
        assertEquals(70_000, large.size)
        assertNotEquals(large.copyOfRange(0, 32).toList(), large.copyOfRange(69_968, 70_000).toList())
        assertNotEquals(crypto.randomBytes(16).toList(), crypto.randomBytes(16).toList())
    }

    @Test
    fun isTheDefaultEverywhere() {
        assertSame(crypto, ArchiveOpenOptions.DEFAULT.crypto)
        assertSame(crypto, ZipEncryption("x".toCharArray()).crypto)
        assertEquals("CryptoProvider.DEFAULT", crypto.toString())
    }

    @Test
    fun winZipAesAgreesWithTheReferenceAcrossTheCounterChunk() {
        // 200,000 random bytes, stored: the counter runs past the 4096-block (64 KiB) keystream chunk.
        val data = Random(4).nextBytes(200_000)
        val files = listOf(ZipWriter.FileSpec("random.bin", data, ZipMethod.STORED), ZipWriter.FileSpec("small.txt", "hello".encodeToByteArray()))
        val password = ArchivePasswordProvider.of("pässword")
        for ((writer, readerCrypto) in listOf(crypto to reference, reference to crypto)) {
            val zip = ZipWriter.write(files, ZipEncryption("pässword".toCharArray(), 256, writer))
            ArchiveKodec.open(zip, null, ArchiveOpenOptions { this.password = password; this.crypto = readerCrypto }).use { reader ->
                val entries = reader.entries()
                assertEquals(ArchiveEncryption.WINZIP_AES_256, entries[0].encryption)
                assertContentEquals(data, reader.read(entries[0]))
                assertContentEquals("hello".encodeToByteArray(), reader.read(entries[1]))
            }
        }
    }

    @Test
    fun winZipAesRejectsWrongPasswordsAndTamperedData() {
        val text = "an encrypted archive, ".repeat(500).encodeToByteArray()
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", text)), ZipEncryption("correct horse".toCharArray()))
        val options = ArchiveOpenOptions { password = ArchivePasswordProvider.of("correct horse") }
        ArchiveKodec.open(zip, null, options).use { reader -> assertContentEquals(text, reader.read(reader.entries().single())) }
        ArchiveKodec.open(zip, null, options.copy { password = ArchivePasswordProvider.of("wrong") }).use { reader ->
            assertFailsWith<ArchivePasswordException> { reader.read(reader.entries().single()) }
        }
        val tampered = zip.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        ArchiveKodec.open(tampered, null, options).use { reader ->
            assertFailsWith<ArchiveAuthenticationException> { reader.read(reader.entries().single()) }
        }
    }

    @Test
    fun sevenZipKeyCacheDerivesOncePerPasswordAndSalt() {
        val password = "SECRET".toCharArray()
        val salt = byteArrayOf(1, 2, 3)
        val expected = SevenZipAes.deriveKey(password, salt, 6)
        val cache = SevenZipAes.KeyCache()
        val first = cache.get(password, salt, 6)
        assertContentEquals(expected, first)
        // The cache hands out copies, so wiping one does not damage the next.
        first.fill(0)
        assertContentEquals(expected, cache.get(password, salt, 6))
        assertNotEquals(expected.toList(), cache.get(password, byteArrayOf(1, 2, 4), 6).toList())
        assertContentEquals(expected, cache.get(password, salt, 6))
        cache.clear()
        assertContentEquals(expected, cache.get(password, salt, 6))
        // Power 0x3F: no hashing, the salt and UTF-16LE password padded to 32 bytes.
        assertContentEquals(byteArrayOf(1, 2, 'a'.code.toByte(), 0, 'b'.code.toByte(), 0) + ByteArray(26), SevenZipAes.deriveKey("ab".toCharArray(), byteArrayOf(1, 2), 0x3F))
        assertFailsWith<IllegalArgumentException> { SevenZipAes.deriveKey(password, salt, 25) }
    }
}
