package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation

/**
 * The cryptographic primitives encrypted archives need. The core library deliberately
 * contains no AES: a table-driven AES written in Kotlin would be slow and would leak key
 * material through cache timing. Supply an implementation backed by the platform instead,
 * such as `CryptographyKotlinProvider` from the `archivekodec-crypto` artifact.
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
    /** PBKDF2 with HMAC-SHA1 (RFC 8018), returning [length] bytes of key material. */
    public fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray

    /** HMAC-SHA1 of [data] under [key], 20 bytes. */
    public fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray

    /** AES encryption of [blocks], a multiple of 16 bytes, in ECB mode without padding. [key] is 16, 24 or 32 bytes. */
    public fun aesEcbEncrypt(key: ByteArray, blocks: ByteArray): ByteArray

    /** AES-CBC decryption of [data], a multiple of 16 bytes, without padding. */
    public fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray

    /** [count] bytes from a cryptographically secure random generator. */
    public fun randomBytes(count: Int): ByteArray
}
