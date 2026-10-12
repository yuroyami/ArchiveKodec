package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.crypto.CryptoProvider
import kotlin.jvm.JvmOverloads

/**
 * WinZip AES encryption for [ZipWriter]: every file's compressed data is encrypted with
 * AES in counter mode under a key derived from [password] with PBKDF2 and a fresh salt,
 * and authenticated with HMAC-SHA1. Entries are written as AE-2, which stores no CRC, so
 * the plaintext's checksum does not leak. Directories are not encrypted. ZipCrypto is
 * never written: it is broken.
 *
 * @property crypto Supplies AES, HMAC, PBKDF2 and secure random salts.
 * @property keyBits AES key size: 128, 192 or 256 (the default).
 * @throws IllegalArgumentException if [password] is empty or [keyBits] is not 128, 192 or 256.
 */
public class ZipEncryption @Throws(IllegalArgumentException::class) @JvmOverloads public constructor(
    password: CharArray,
    public val crypto: CryptoProvider,
    public val keyBits: Int = 256,
) {
    private val secret = password.copyOf()

    init {
        require(password.isNotEmpty()) { "ZIP encryption password must not be empty" }
        require(keyBits == 128 || keyBits == 192 || keyBits == 256) { "WinZip AES key size must be 128, 192 or 256 bits" }
    }

    /** WinZip's strength code: 1, 2 or 3 for 128, 192 or 256 bits. */
    internal val strength: Int get() = (keyBits - 64) / 64

    internal fun password(): CharArray = secret.copyOf()

    /** Describe the settings without the password. */
    override fun toString(): String = "ZipEncryption(keyBits=$keyBits)"
}
