// SHA-1 and ECB are marked delicate. WinZip AES fixes both: its keys come from
// PBKDF2-HMAC-SHA1, its tag is HMAC-SHA1, and its little-endian counter mode is built on
// single-block ECB encryption. Neither is used as a hash or a mode on its own.
@file:OptIn(DelicateCryptographyApi::class)

package io.github.yuroyami.archivekodec.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA1

/**
 * A [CryptoProvider] backed by cryptography-kotlin, so AES runs in the platform's
 * implementation: hardware-accelerated and constant-time where the platform offers it.
 * [provider] defaults to cryptography-kotlin's default, which this artifact supplies: the
 * JDK on the JVM and Android, OpenSSL 3 on Linux, Windows and Android Native, and Apple's
 * libraries on Apple targets. Random salts come from the platform's secure generator.
 *
 * ```kotlin
 * val options = ArchiveOpenOptions {
 *     password = ArchivePasswordProvider.of(secret)
 *     crypto = CryptographyKotlinProvider()
 * }
 * ArchiveKodec.open(bytes, null, options).use { reader -> ... }
 * ```
 *
 * Instances hold no mutable state and may be shared between threads.
 */
public class CryptographyKotlinProvider public constructor(
    private val provider: CryptographyProvider,
) : CryptoProvider {
    /** Use cryptography-kotlin's default provider. */
    public constructor() : this(CryptographyProvider.Default)

    override fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray =
        provider.get(PBKDF2).secretDerivation(SHA1, iterations, length.bytes, salt).deriveSecretToByteArrayBlocking(password)

    override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray =
        provider.get(HMAC).keyDecoder(SHA1).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator().generateSignatureBlocking(data)

    // WinZip AES counts blocks little-endian, which no standard CTR mode does, so the
    // counter blocks are built by the caller and encrypted here one at a time in ECB.
    override fun aesEcbEncrypt(key: ByteArray, blocks: ByteArray): ByteArray =
        provider.get(AES.ECB).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher(padding = false).encryptBlocking(blocks)

    override fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        provider.get(AES.CBC).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher(padding = false).decryptWithIvBlocking(iv, data)

    override fun randomBytes(count: Int): ByteArray = CryptographySystem.getDefaultRandom().nextBytes(count)

    /** Name the underlying provider. */
    override fun toString(): String = "CryptographyKotlinProvider(${provider.name})"
}
