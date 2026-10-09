package io.github.yuroyami.archivekodec.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The plain-Kotlin test provider agrees with javax.crypto, so the common tests rest on correct primitives. */
class TestCryptoJvmTest {
    private val crypto = TestCrypto()
    private val random = Random(42)

    @Test
    fun aesMatchesTheJdk() {
        for (keySize in listOf(16, 24, 32)) repeat(20) {
            val key = random.nextBytes(keySize)
            val data = random.nextBytes(16 * (1 + random.nextInt(8)))
            val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            assertContentEquals(ecb.doFinal(data), crypto.aesEcbEncrypt(key, data))
            val iv = random.nextBytes(16)
            val cbc = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            assertContentEquals(cbc.doFinal(data), crypto.aesCbcDecrypt(key, iv, data))
        }
    }

    @Test
    fun hmacAndPbkdf2MatchTheJdk() {
        repeat(20) {
            val key = random.nextBytes(random.nextInt(1, 100))
            val data = random.nextBytes(random.nextInt(0, 300))
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
            assertContentEquals(mac.doFinal(data), crypto.hmacSha1(key, data))
        }
        val password = "pässword"
        val salt = random.nextBytes(16)
        val spec = PBEKeySpec(password.toCharArray(), salt, 1000, 66 * 8)
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        assertContentEquals(expected, crypto.pbkdf2HmacSha1(password.encodeToByteArray(), salt, 1000, 66))
    }
}
