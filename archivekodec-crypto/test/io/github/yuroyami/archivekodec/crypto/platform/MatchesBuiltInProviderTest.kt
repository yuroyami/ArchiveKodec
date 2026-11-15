package io.github.yuroyami.archivekodec.crypto.platform

import io.github.yuroyami.archivekodec.crypto.CryptoProvider
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The platform provider and the core's built-in provider give the same bytes on random inputs. */
class MatchesBuiltInProviderTest {
    private val builtIn = CryptoProvider.DEFAULT
    private val platform = CryptographyKotlinProvider()

    @Test
    fun aesMatches() {
        val random = Random(3)
        repeat(200) {
            val key = random.nextBytes(listOf(16, 24, 32)[it % 3])
            val data = random.nextBytes(16 * random.nextInt(0, 9))
            val iv = random.nextBytes(16)
            assertContentEquals(platform.aesEcbEncrypt(key, data), builtIn.aesEcbEncrypt(key, data))
            assertContentEquals(platform.aesCbcDecrypt(key, iv, data), builtIn.aesCbcDecrypt(key, iv, data))
        }
    }

    @Test
    fun hmacAndPbkdf2Match() {
        val random = Random(4)
        val alphabet = "abcXYZ019 äöüßéñ€日本語🙂"
        repeat(50) {
            // The JDK refuses an empty HMAC key, so start at 1 byte.
            val key = random.nextBytes(random.nextInt(1, 130))
            val data = random.nextBytes(random.nextInt(0, 300))
            assertContentEquals(platform.hmacSha1(key, data), builtIn.hmacSha1(key, data))
            // PBKDF2 passwords are the UTF-8 bytes of the password text; the JDK decodes them as UTF-8.
            val password = buildString { repeat(random.nextInt(1, 40)) { append(alphabet[random.nextInt(alphabet.length)]) } }
                .encodeToByteArray()
            val salt = random.nextBytes(random.nextInt(1, 40))
            val length = random.nextInt(1, 70)
            val iterations = random.nextInt(1, 20)
            assertContentEquals(platform.pbkdf2HmacSha1(password, salt, iterations, length), builtIn.pbkdf2HmacSha1(password, salt, iterations, length))
        }
    }
}
