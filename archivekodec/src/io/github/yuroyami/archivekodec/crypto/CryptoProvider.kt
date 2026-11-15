package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation
import kotlin.jvm.JvmField

/**
 * The cryptographic primitives encrypted archives need. [DEFAULT] is built into the core and
 * works on every platform, so a reader or writer needs no other artifact for AES.
 * `CryptographyKotlinProvider` from the `archivekodec-crypto` artifact uses the platform's own
 * AES instead, which is faster where the platform has hardware AES.
 *
 * WinZip AES uses [pbkdf2HmacSha1], [hmacSha1], [aesEcbEncrypt] (for its little-endian
 * counter mode) and, when writing, [randomBytes]. 7z AES uses [aesCbcDecrypt]; its key
 * derivation is iterated SHA-256, which the library computes itself. ZipCrypto needs no
 * provider.
 *
 * Implementations must be thread-safe if shared between readers on different threads.
 * Calling is stable; implementing requires opting in to [ExperimentalArchiveImplementation],
 * because members may be added.
 */
@SubclassOptInRequired(ExperimentalArchiveImplementation::class)
public interface CryptoProvider {
    /**
     * PBKDF2 with HMAC-SHA1 (RFC 8018), returning [length] bytes of key material. The library
     * always passes the UTF-8 bytes of the password text as [password].
     */
    public fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray

    /** HMAC-SHA1 of [data] under [key], 20 bytes. */
    public fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray

    /** AES encryption of [blocks], a multiple of 16 bytes, in ECB mode without padding. [key] is 16, 24 or 32 bytes. */
    public fun aesEcbEncrypt(key: ByteArray, blocks: ByteArray): ByteArray

    /** AES-CBC decryption of [data], a multiple of 16 bytes, without padding. */
    public fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray

    /** [count] bytes from a cryptographically secure random generator. */
    public fun randomBytes(count: Int): ByteArray

    /** The provider built into the core. */
    public companion object {
        /**
         * The provider built into the core, on every platform. AES is bitsliced after BearSSL:
         * it uses no lookup tables and no branch on the key or the data. HMAC-SHA1 and PBKDF2
         * run on the library's SHA-1, and random bytes come from the operating system.
         *
         * It is the default of `ArchiveOpenOptions.crypto` and of `ZipEncryption`. It is slower
         * than hardware AES, and each call blocks the calling thread. On JavaScript engines the
         * timing is best effort, because those engines make no promise about it.
         */
        @JvmField public val DEFAULT: CryptoProvider = BuiltInCryptoProvider
    }
}
