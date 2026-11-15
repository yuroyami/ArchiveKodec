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

/** The test provider and the built-in provider agree with javax.crypto, so the common tests rest on correct primitives. */
class TestCryptoJvmTest {
    private val providers = listOf(TestCrypto(), CryptoProvider.DEFAULT)
    private val random = Random(42)

    @Test
    fun aesMatchesTheJdk() {
        for (crypto in providers) for (keySize in listOf(16, 24, 32)) repeat(20) {
            val key = random.nextBytes(keySize)
            val data = random.nextBytes(16 * (1 + random.nextInt(8)))
            val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            assertContentEquals(ecb.doFinal(data), crypto.aesEcbEncrypt(key, data), "$crypto ECB")
            val iv = random.nextBytes(16)
            val cbc = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            assertContentEquals(cbc.doFinal(data), crypto.aesCbcDecrypt(key, iv, data), "$crypto CBC")
        }
    }

    @Test
    fun hmacAndPbkdf2MatchTheJdk() {
        for (crypto in providers) {
            repeat(20) {
                // The JDK refuses an empty HMAC key, so start at 1 byte.
                val key = random.nextBytes(random.nextInt(1, 100))
                val data = random.nextBytes(random.nextInt(0, 300))
                val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
                assertContentEquals(mac.doFinal(data), crypto.hmacSha1(key, data), "$crypto HMAC")
            }
            // The JDK hashes the password's UTF-8 bytes, the contract of CryptoProvider.pbkdf2HmacSha1.
            val password = "pässword"
            val salt = random.nextBytes(16)
            val spec = PBEKeySpec(password.toCharArray(), salt, 1000, 66 * 8)
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            assertContentEquals(expected, crypto.pbkdf2HmacSha1(password.encodeToByteArray(), salt, 1000, 66), "$crypto PBKDF2")
        }
    }
}
