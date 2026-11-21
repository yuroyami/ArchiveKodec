package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.crypto.ArchiveEncryption
import io.github.yuroyami.archivekodec.crypto.CryptoProvider
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * WinZip AES encryption for [ZipWriter]: every file's compressed data is encrypted with
 * AES in counter mode under a key derived from [password] with PBKDF2 and a fresh salt,
 * and authenticated with HMAC-SHA1. Entries are written as AE-2, which stores no CRC, so
 * the plaintext's checksum does not leak. Directories are not encrypted.
 *
 * [insecureZipCrypto] writes PKWARE's traditional encryption instead, only for tools that
 * read nothing else. It is broken: a few known bytes of any file recover the keys.
 *
 * @property keyBits AES key size: 128, 192 or 256 (the default).
 * @property crypto Supplies AES, HMAC, PBKDF2 and secure random salts. The default is the
 *   built-in [CryptoProvider.DEFAULT].
 * @throws IllegalArgumentException if [password] is empty or [keyBits] is not 128, 192 or 256.
 */
public class ZipEncryption @Throws(IllegalArgumentException::class) @JvmOverloads public constructor(
    password: CharArray,
    public val keyBits: Int = 256,
    public val crypto: CryptoProvider = CryptoProvider.DEFAULT,
) {
    private val secret = password.copyOf()

    init {
        require(password.isNotEmpty()) { "ZIP encryption password must not be empty" }
        require(keyBits == 128 || keyBits == 192 || keyBits == 256) { "WinZip AES key size must be 128, 192 or 256 bits" }
    }

    /** The scheme written: a WinZip AES size, or [ArchiveEncryption.ZIP_CRYPTO_INSECURE]. */
    public val scheme: ArchiveEncryption
        get() = when {
            zipCrypto -> ArchiveEncryption.ZIP_CRYPTO_INSECURE
            keyBits == 128 -> ArchiveEncryption.WINZIP_AES_128
            keyBits == 192 -> ArchiveEncryption.WINZIP_AES_192
            else -> ArchiveEncryption.WINZIP_AES_256
        }

    internal var zipCrypto: Boolean = false
        private set

    /** WinZip's strength code: 1, 2 or 3 for 128, 192 or 256 bits. */
    internal val strength: Int get() = (keyBits - 64) / 64

    internal fun password(): CharArray = secret.copyOf()

    /** Describe the settings without the password. */
    override fun toString(): String = if (zipCrypto) "ZipEncryption(ZipCrypto)" else "ZipEncryption(keyBits=$keyBits)"

    public companion object {
        /**
         * PKWARE's traditional ZIP encryption, which every ZIP tool reads. It is broken: use it
         * only for a reader that supports nothing else. [crypto] supplies the random header bytes.
         * @throws IllegalArgumentException if [password] is empty.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public fun insecureZipCrypto(password: CharArray, crypto: CryptoProvider = CryptoProvider.DEFAULT): ZipEncryption =
            ZipEncryption(password, 256, crypto).also { it.zipCrypto = true }
    }
}
