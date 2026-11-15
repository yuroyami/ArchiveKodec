package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation

/** The provider behind [CryptoProvider.DEFAULT]. It holds no state, so one instance serves every thread. */
@OptIn(ExperimentalArchiveImplementation::class)
internal object BuiltInCryptoProvider : CryptoProvider {
    override fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray =
        HmacSha1.pbkdf2(password, salt, iterations, length)

    override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = HmacSha1.mac(key, data)

    override fun aesEcbEncrypt(key: ByteArray, blocks: ByteArray): ByteArray = ConstantTimeAes(key).encryptEcb(blocks)

    override fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        ConstantTimeAes(key).decryptCbc(iv, data)

    override fun randomBytes(count: Int): ByteArray {
        require(count >= 0) { "count must not be negative: $count" }
        return secureRandomBytes(count)
    }

    override fun toString(): String = "CryptoProvider.DEFAULT"
}

/** [count] bytes from the operating system's cryptographically secure random generator. */
internal expect fun secureRandomBytes(count: Int): ByteArray
