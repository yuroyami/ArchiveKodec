package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.ArchiveAuthenticationException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchivePasswordException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.Sha256

/** Password text as the ZIP schemes hash it: UTF-8, as 7-Zip and WinZip write it. */
internal fun CharArray.utf8(): ByteArray = concatToString().encodeToByteArray()

/** Compare without an early exit, so the time taken does not reveal where tags differ. */
internal fun constantTimeEquals(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, length: Int): Boolean {
    var difference = 0
    for (i in 0 until length) difference = difference or (a[aOffset + i].toInt() xor b[bOffset + i].toInt())
    return difference == 0
}

/**
 * PKWARE's traditional ZIP encryption, decrypt only: three 32-bit keys stirred with CRC-32
 * and a linear congruence. It is broken and is never written.
 */
internal class ZipCrypto(password: ByteArray) {
    private var k0 = 0x12345678
    private var k1 = 0x23456789
    private var k2 = 0x34567890

    init { for (b in password) update(b.toInt() and 0xFF) }

    private fun update(plain: Int) {
        k0 = crc(k0, plain)
        k1 = (k1 + (k0 and 0xFF)) * 134775813 + 1
        k2 = crc(k2, k1 ushr 24)
    }

    private fun decryptByte(cipher: Int): Int {
        val t = (k2 or 2) and 0xFFFF
        val plain = (cipher xor ((t * (t xor 1)) ushr 8)) and 0xFF
        update(plain)
        return plain
    }

    /**
     * Decrypt [data]: a 12-byte header, then the payload. The header's last byte must match
     * [check] (the CRC's high byte) or, for streamed entries, [timeCheck] (the DOS time's
     * high byte); otherwise the password is wrong.
     */
    fun decrypt(data: ByteArray, check: Int, timeCheck: Int?): ByteArray {
        if (data.size < 12) throw ArchiveFormatException("ZIP: encrypted data is shorter than its 12-byte header")
        var last = 0
        for (i in 0 until 12) last = decryptByte(data[i].toInt() and 0xFF)
        if (last != check && last != timeCheck) throw ArchivePasswordException("ZIP: wrong password")
        return ByteArray(data.size - 12) { decryptByte(data[12 + it].toInt() and 0xFF).toByte() }
    }

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        fun crc(crc: Int, b: Int) = TABLE[(crc xor b) and 0xFF] xor (crc ushr 8)
    }
}

/**
 * WinZip AES (AE-1 and AE-2): PBKDF2-HMAC-SHA1 with 1000 iterations derives an AES key,
 * an HMAC key and a 2-byte password verifier from a random salt. Data is AES in counter
 * mode with a little-endian counter starting at 1, followed by the first 10 bytes of
 * HMAC-SHA1 over the ciphertext.
 */
internal object WinZipAes {
    private const val ITERATIONS = 1000
    private const val MAC_SIZE = 10
    private const val CHUNK_BLOCKS = 4096

    fun keyBytes(strength: Int): Int = when (strength) {
        1 -> 16
        2 -> 24
        3 -> 32
        else -> throw ArchiveUnsupportedException("ZIP: unknown WinZip AES strength $strength")
    }

    fun overhead(strength: Int): Int = keyBytes(strength) / 2 + 2 + MAC_SIZE

    /**
     * Check the password and the authentication code, then decrypt. Nothing is decrypted, let
     * alone decompressed, unless the HMAC over the ciphertext matches.
     */
    fun decrypt(crypto: CryptoProvider, password: CharArray, strength: Int, data: ByteArray): ByteArray {
        val keyLength = keyBytes(strength)
        val saltLength = keyLength / 2
        if (data.size < saltLength + 2 + MAC_SIZE) throw ArchiveFormatException("ZIP: WinZip AES data is shorter than its salt and tag")
        val salt = data.copyOfRange(0, saltLength)
        val material = crypto.pbkdf2HmacSha1(password.utf8(), salt, ITERATIONS, 2 * keyLength + 2)
        if (!constantTimeEquals(material, 2 * keyLength, data, saltLength, 2)) throw ArchivePasswordException("ZIP: wrong password")
        val cipher = data.copyOfRange(saltLength + 2, data.size - MAC_SIZE)
        val mac = crypto.hmacSha1(material.copyOfRange(keyLength, 2 * keyLength), cipher)
        if (!constantTimeEquals(mac, 0, data, data.size - MAC_SIZE, MAC_SIZE)) {
            throw ArchiveAuthenticationException("ZIP: WinZip AES authentication code does not match; the data was altered")
        }
        counterMode(crypto, material.copyOf(keyLength), cipher)
        return cipher
    }

    /** Encrypt [plain] with a fresh salt: salt, verifier, ciphertext and tag. */
    fun encrypt(crypto: CryptoProvider, password: CharArray, strength: Int, plain: ByteArray): ByteArray {
        val keyLength = keyBytes(strength)
        val saltLength = keyLength / 2
        val salt = crypto.randomBytes(saltLength)
        require(salt.size == saltLength) { "crypto provider returned ${salt.size} random bytes, not $saltLength" }
        val material = crypto.pbkdf2HmacSha1(password.utf8(), salt, ITERATIONS, 2 * keyLength + 2)
        val cipher = plain.copyOf()
        counterMode(crypto, material.copyOf(keyLength), cipher)
        val mac = crypto.hmacSha1(material.copyOfRange(keyLength, 2 * keyLength), cipher)
        return salt + material.copyOfRange(2 * keyLength, 2 * keyLength + 2) + cipher + mac.copyOf(MAC_SIZE)
    }

    /** XOR [data] in place with the keystream of counter blocks 1, 2, 3, ... (little-endian). */
    private fun counterMode(crypto: CryptoProvider, key: ByteArray, data: ByteArray) {
        var counter = 1L
        var position = 0
        while (position < data.size) {
            val blocks = minOf(CHUNK_BLOCKS, (data.size - position + 15) / 16)
            val counters = ByteArray(blocks * 16)
            for (b in 0 until blocks) {
                for (i in 0 until 8) counters[16 * b + i] = (counter ushr (8 * i)).toByte()
                counter++
            }
            val stream = crypto.aesEcbEncrypt(key, counters)
            if (stream.size != counters.size) throw IllegalStateException("crypto provider returned ${stream.size} bytes for ${counters.size}")
            val n = minOf(stream.size, data.size - position)
            for (i in 0 until n) data[position + i] = (data[position + i].toInt() xor stream[i].toInt()).toByte()
            position += n
        }
    }
}

/**
 * 7z AES-256-CBC (coder 06 F1 07 01). The key is SHA-256 over 2^cycles rounds of salt,
 * UTF-16LE password and a 64-bit round counter; the properties carry the cycle count,
 * salt and IV. 7z adds no authentication: a wrong password shows up as undecodable data.
 */
internal object SevenZipAes {
    /** 7-Zip writes 19; each step doubles the work, so a hostile header cannot ask for more than this. */
    private const val MAX_CYCLES_POWER = 24

    fun decrypt(crypto: CryptoProvider, password: CharArray, properties: ByteArray, data: ByteArray): ByteArray {
        if (properties.isEmpty()) throw ArchiveFormatException("7z: missing AES properties")
        val b0 = properties[0].toInt() and 0xFF
        val power = b0 and 0x3F
        var salt = ByteArray(0)
        val iv = ByteArray(16)
        if (b0 and 0xC0 != 0) {
            if (properties.size < 2) throw ArchiveFormatException("7z: truncated AES properties")
            val b1 = properties[1].toInt() and 0xFF
            val saltSize = ((b0 ushr 7) and 1) + (b1 ushr 4)
            val ivSize = ((b0 ushr 6) and 1) + (b1 and 0x0F)
            if (properties.size < 2 + saltSize + ivSize || ivSize > 16) throw ArchiveFormatException("7z: invalid AES properties")
            salt = properties.copyOfRange(2, 2 + saltSize)
            properties.copyInto(iv, 0, 2 + saltSize, 2 + saltSize + ivSize)
        }
        if (power != 0x3F && power > MAX_CYCLES_POWER) {
            throw ArchiveUnsupportedException("7z: AES key derivation with 2^$power rounds is above the supported 2^$MAX_CYCLES_POWER")
        }
        if (data.size % 16 != 0) throw ArchiveFormatException("7z: AES data is not a whole number of blocks")
        return crypto.aesCbcDecrypt(key(password, salt, power), iv, data)
    }

    fun key(password: CharArray, salt: ByteArray, power: Int): ByteArray {
        val utf16 = ByteArray(password.size * 2)
        for (i in password.indices) {
            utf16[2 * i] = password[i].code.toByte()
            utf16[2 * i + 1] = (password[i].code ushr 8).toByte()
        }
        if (power == 0x3F) {
            val key = ByteArray(32)
            val material = salt + utf16
            material.copyInto(key, 0, 0, minOf(32, material.size))
            return key
        }
        val round = ByteArray(salt.size + utf16.size + 8)
        salt.copyInto(round)
        utf16.copyInto(round, salt.size)
        val counterAt = salt.size + utf16.size
        val sha = Sha256()
        for (r in 0L until (1L shl power)) {
            for (i in 0 until 8) round[counterAt + i] = (r ushr (8 * i)).toByte()
            sha.update(round)
        }
        return sha.digest()
    }
}
