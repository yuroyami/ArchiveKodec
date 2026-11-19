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
 * PKWARE's traditional ZIP encryption: three 32-bit keys stirred with CRC-32 and a linear
 * congruence. It is broken, and written only when a caller asks for it by name, for tools
 * that read nothing else.
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

    private fun encryptByte(plain: Int): Int {
        val t = (k2 or 2) and 0xFFFF
        val cipher = (plain xor ((t * (t xor 1)) ushr 8)) and 0xFF
        update(plain)
        return cipher
    }

    /**
     * Encrypt [data] behind a 12-byte header of [random] bytes whose last byte is [check], the
     * high byte of the plaintext's CRC-32, which readers compare to test the password.
     */
    fun encrypt(data: ByteArray, random: ByteArray, check: Int): ByteArray {
        require(random.size >= 11) { "ZipCrypto needs 11 random header bytes" }
        val out = ByteArray(12 + data.size)
        for (i in 0 until 11) out[i] = encryptByte(random[i].toInt() and 0xFF).toByte()
        out[11] = encryptByte(check and 0xFF).toByte()
        for (i in data.indices) out[12 + i] = encryptByte(data[i].toInt() and 0xFF).toByte()
        return out
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
        val material = derive(crypto, password, salt, keyLength)
        try {
            if (!constantTimeEquals(material, 2 * keyLength, data, saltLength, 2)) throw ArchivePasswordException("ZIP: wrong password")
            val cipher = data.copyOfRange(saltLength + 2, data.size - MAC_SIZE)
            val mac = hmac(crypto, material, keyLength, cipher)
            if (!constantTimeEquals(mac, 0, data, data.size - MAC_SIZE, MAC_SIZE)) {
                throw ArchiveAuthenticationException("ZIP: WinZip AES authentication code does not match; the data was altered")
            }
            counterMode(crypto, material.copyOf(keyLength), cipher)
            return cipher
        } finally {
            material.fill(0)
        }
    }

    /** Encrypt [plain] with a fresh salt: salt, verifier, ciphertext and tag. */
    fun encrypt(crypto: CryptoProvider, password: CharArray, strength: Int, plain: ByteArray): ByteArray {
        val keyLength = keyBytes(strength)
        val saltLength = keyLength / 2
        val salt = crypto.randomBytes(saltLength)
        require(salt.size == saltLength) { "crypto provider returned ${salt.size} random bytes, not $saltLength" }
        val material = derive(crypto, password, salt, keyLength)
        try {
            val cipher = plain.copyOf()
            counterMode(crypto, material.copyOf(keyLength), cipher)
            val mac = hmac(crypto, material, keyLength, cipher)
            return salt + material.copyOfRange(2 * keyLength, 2 * keyLength + 2) + cipher + mac.copyOf(MAC_SIZE)
        } finally {
            material.fill(0)
        }
    }

    /** HMAC-SHA1 of [cipher] with the HMAC key from [material]. The key copy is wiped afterwards. */
    private fun hmac(crypto: CryptoProvider, material: ByteArray, keyLength: Int, cipher: ByteArray): ByteArray {
        val key = material.copyOfRange(keyLength, 2 * keyLength)
        try {
            return crypto.hmacSha1(key, cipher)
        } finally {
            key.fill(0)
        }
    }

    /** The AES key, the HMAC key and the 2-byte verifier, in that order. */
    private fun derive(crypto: CryptoProvider, password: CharArray, salt: ByteArray, keyLength: Int): ByteArray {
        val utf8 = password.utf8()
        try {
            val material = crypto.pbkdf2HmacSha1(utf8, salt, ITERATIONS, 2 * keyLength + 2)
            check(material.size == 2 * keyLength + 2) { "crypto provider returned ${material.size} PBKDF2 bytes, not ${2 * keyLength + 2}" }
            return material
        } finally {
            utf8.fill(0)
        }
    }

    /** XOR [data] in place with the keystream of counter blocks 1, 2, 3, ... (little-endian). */
    private fun counterMode(crypto: CryptoProvider, key: ByteArray, data: ByteArray) {
        // A ByteArray holds fewer than 2^28 blocks, so an Int counter never wraps and its upper bytes stay zero.
        var counter = 1
        var position = 0
        try {
            // The built-in provider XORs the keystream in place, with no counter or keystream arrays.
            if (crypto === BuiltInCryptoProvider) {
                ConstantTimeAes(key).xorCounterKeystream(data, counter)
                return
            }
            while (position < data.size) {
                val blocks = minOf(CHUNK_BLOCKS, (data.size - position + 15) / 16)
                val counters = ByteArray(blocks * 16)
                for (b in 0 until blocks) {
                    for (i in 0 until 4) counters[16 * b + i] = (counter ushr (8 * i)).toByte()
                    counter++
                }
                val stream = crypto.aesEcbEncrypt(key, counters)
                if (stream.size != counters.size) throw IllegalStateException("crypto provider returned ${stream.size} bytes for ${counters.size}")
                val n = minOf(stream.size, data.size - position)
                for (i in 0 until n) data[position + i] = (data[position + i].toInt() xor stream[i].toInt()).toByte()
                position += n
            }
        } finally {
            key.fill(0)
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

    fun decrypt(crypto: CryptoProvider, password: CharArray, properties: ByteArray, data: ByteArray, cache: KeyCache): ByteArray {
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
        val key = cache.get(password, salt, power)
        try {
            return crypto.aesCbcDecrypt(key, iv, data)
        } finally {
            key.fill(0)
        }
    }

    /** The key for [password], [salt] and 2^[power] rounds; a [power] of 0x3F means no hashing. */
    fun deriveKey(password: CharArray, salt: ByteArray, power: Int): ByteArray {
        require(power == 0x3F || power in 0..MAX_CYCLES_POWER) { "7z: AES key derivation power $power is out of range" }
        val utf16 = ByteArray(password.size * 2)
        for (i in password.indices) {
            utf16[2 * i] = password[i].code.toByte()
            utf16[2 * i + 1] = (password[i].code ushr 8).toByte()
        }
        val unit = salt.size + utf16.size + 8
        if (power == 0x3F) {
            val key = ByteArray(32)
            salt.copyInto(key, 0, 0, minOf(32, salt.size))
            if (salt.size < 32) utf16.copyInto(key, salt.size, 0, minOf(32 - salt.size, utf16.size))
            utf16.fill(0)
            return key
        }
        // Each round hashes salt, password and an 8-byte counter. A batch of rounds goes to SHA-256 in one call.
        val rounds = 1 shl power
        val slots = minOf(rounds, maxOf(1, 4096 / unit))
        val batch = ByteArray(slots * unit)
        for (k in 0 until slots) {
            salt.copyInto(batch, k * unit)
            utf16.copyInto(batch, k * unit + salt.size)
        }
        utf16.fill(0)
        try {
            val sha = Sha256()
            var r = 0
            while (r < rounds) {
                val n = minOf(slots, rounds - r)
                // At most 2^24 rounds, so the counter fits in its low 4 bytes and the upper 4 stay zero.
                for (k in 0 until n) {
                    val at = k * unit + unit - 8
                    val counter = r + k
                    batch[at] = counter.toByte()
                    batch[at + 1] = (counter ushr 8).toByte()
                    batch[at + 2] = (counter ushr 16).toByte()
                    batch[at + 3] = (counter ushr 24).toByte()
                }
                sha.update(batch, 0, n * unit)
                r += n
            }
            return sha.digest()
        } finally {
            batch.fill(0)
        }
    }

    /**
     * The last derived key. Every folder of a 7z archive, and its encrypted header, usually
     * shares one password and salt, so the costly derivation runs once per reader. Not thread-safe.
     */
    class KeyCache {
        private var password: CharArray? = null
        private var salt: ByteArray? = null
        private var power = -1
        private var key: ByteArray? = null

        /** A copy of the key for these inputs, derived only when they differ from the last call. */
        fun get(password: CharArray, salt: ByteArray, power: Int): ByteArray {
            val cached = key
            if (cached != null && power == this.power && salt.contentEquals(this.salt) && password.contentEquals(this.password)) {
                return cached.copyOf()
            }
            clear()
            val derived = deriveKey(password, salt, power)
            this.password = password.copyOf()
            this.salt = salt.copyOf()
            this.power = power
            key = derived.copyOf()
            return derived
        }

        /** Wipe the remembered password and key. */
        fun clear() {
            password?.fill('\u0000')
            key?.fill(0)
            password = null
            salt = null
            key = null
            power = -1
        }
    }
}
