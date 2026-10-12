package io.github.yuroyami.archivekodec.crypto

import kotlin.jvm.JvmField

/**
 * Open identifier of an archive encryption scheme, compared by [name]. Like
 * [io.github.yuroyami.archivekodec.codec.CodecId] it is not an enum; keep an `else`
 * branch when dispatching.
 *
 * @throws IllegalArgumentException if [name] is empty or not an ASCII identifier.
 */
public class ArchiveEncryption @Throws(IllegalArgumentException::class) public constructor(name: String) {
    /** Canonical lowercase ASCII identifier. */
    public val name: String

    init {
        fun letterOrDigit(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        require(name.isNotEmpty() && letterOrDigit(name[0]) && name.all { letterOrDigit(it) || it in "._+-" }) {
            "encryption name must be a nonempty ASCII identifier beginning with a letter or digit"
        }
        this.name = name.lowercase()
    }

    /** Compare canonical names. */
    override fun equals(other: Any?): Boolean = other is ArchiveEncryption && name == other.name

    /** Hash the canonical name. */
    override fun hashCode(): Int = name.hashCode()

    /** Return the canonical name. */
    override fun toString(): String = name

    /** Schemes the built-in readers recognise. */
    public companion object {
        /**
         * PKWARE's traditional ZIP encryption. It is broken: known-plaintext attacks recover
         * the keys in minutes. It is read for old archives and never written.
         */
        @JvmField public val ZIP_CRYPTO_INSECURE: ArchiveEncryption = ArchiveEncryption("zipcrypto")
        /** WinZip AES with a 128-bit key: AES-CTR, HMAC-SHA1 authentication, PBKDF2 keys. */
        @JvmField public val WINZIP_AES_128: ArchiveEncryption = ArchiveEncryption("winzip-aes-128")
        /** WinZip AES with a 192-bit key. */
        @JvmField public val WINZIP_AES_192: ArchiveEncryption = ArchiveEncryption("winzip-aes-192")
        /** WinZip AES with a 256-bit key. */
        @JvmField public val WINZIP_AES_256: ArchiveEncryption = ArchiveEncryption("winzip-aes-256")
        /** 7z's AES-256-CBC with an iterated SHA-256 key; it provides no authentication. */
        @JvmField public val SEVEN_ZIP_AES_256: ArchiveEncryption = ArchiveEncryption("7z-aes-256")
        /** ZIP strong encryption (PKWARE SES) and other schemes this library cannot decrypt. */
        @JvmField public val UNSUPPORTED: ArchiveEncryption = ArchiveEncryption("unsupported")
    }
}
