package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.ArchiveAuthenticationException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchivePasswordException
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipEncryption
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CryptographyKotlinProviderTest {
    private val crypto = CryptographyKotlinProvider()

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun primitivesMatchPublishedVectors() {
        // FIPS 197 appendix C, RFC 2202 and RFC 6070.
        val block = hex("00112233445566778899aabbccddeeff")
        assertContentEquals(hex("69c4e0d86a7b0430d8cdb78070b4c55a"), crypto.aesEcbEncrypt(ByteArray(16) { it.toByte() }, block))
        assertContentEquals(hex("dda97ca4864cdfe06eaf70a0ec0d7191"), crypto.aesEcbEncrypt(ByteArray(24) { it.toByte() }, block))
        assertContentEquals(hex("8ea2b7ca516745bfeafc49904b496089"), crypto.aesEcbEncrypt(ByteArray(32) { it.toByte() }, block))
        assertContentEquals(hex("b617318655057264e28bc0b6fb378c8ef146be00"), crypto.hmacSha1(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()))
        assertContentEquals(hex("4b007901b765489abead49d926f721d065a429c1"),
            crypto.pbkdf2HmacSha1("password".encodeToByteArray(), "salt".encodeToByteArray(), 4096, 20))
        // NIST SP 800-38A F.2.2: CBC-AES128 decryption.
        assertContentEquals(
            hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"),
            crypto.aesCbcDecrypt(hex("2b7e151628aed2a6abf7158809cf4f3c"), hex("000102030405060708090a0b0c0d0e0f"),
                hex("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2")),
        )
        assertEquals(32, crypto.randomBytes(32).size)
        assertNotEquals(crypto.randomBytes(16).toList(), crypto.randomBytes(16).toList())
    }

    @Test
    fun writesAndReadsWinZipAes() {
        val text = "an encrypted archive, ".repeat(500).encodeToByteArray()
        val zip = ZipWriter.write(
            listOf(ZipWriter.FileSpec("a.txt", text), ZipWriter.FileSpec("b.bin", byteArrayOf(1, 2, 3), ZipMethod.STORED)),
            ZipEncryption("correct horse".toCharArray(), crypto),
        )
        val options = ArchiveOpenOptions { password = ArchivePasswordProvider.of("correct horse"); this.crypto = this@CryptographyKotlinProviderTest.crypto }
        ArchiveKodec.open(zip, null, options).use { reader ->
            val entries = reader.entries()
            assertEquals(ArchiveEncryption.WINZIP_AES_256, entries[0].encryption)
            assertContentEquals(text, reader.read(entries[0]))
            assertContentEquals(byteArrayOf(1, 2, 3), reader.read(entries[1]))
        }
        ArchiveKodec.open(zip, null, options.copy { password = ArchivePasswordProvider.of("wrong") }).use { reader ->
            assertFailsWith<ArchivePasswordException> { reader.read(reader.entries()[0]) }
        }
        val tampered = zip.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        ArchiveKodec.open(tampered, null, options).use { reader ->
            assertFailsWith<ArchiveAuthenticationException> { reader.read(reader.entries()[0]) }
        }
    }
}
