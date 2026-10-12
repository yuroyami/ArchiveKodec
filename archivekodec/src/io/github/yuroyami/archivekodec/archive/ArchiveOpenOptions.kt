package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.codec.CodecRegistry
import io.github.yuroyami.archivekodec.crypto.ArchivePasswordProvider
import io.github.yuroyami.archivekodec.crypto.CryptoProvider
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Immutable settings passed to a registered reader factory. Build with
 * `ArchiveOpenOptions { limits = policy; codecs = customCodecs }`. Future settings
 * can be added without expanding a public constructor or copy signature.
 * Callback and codec instances are shared and retain their own thread-safety rules.
 */
public class ArchiveOpenOptions private constructor(builder: Builder) {
    /** Policy for wrapper decoding, listing and each read, applied separately. */
    public val limits: ArchiveLimits = builder.limits
    /** Explicit filename decoder, or null to retain each format's default. */
    public val nameDecoder: ArchiveNameDecoder? = builder.nameDecoder
    /** Codec bindings used by built-in ZIP readers. */
    public val codecs: CodecRegistry = builder.codecs
    /** Supplies members stored outside the archive, such as GNU thin `ar` members; null refuses them. */
    public val externalMembers: ExternalMemberResolver? = builder.externalMembers
    /** Supplies passwords for encrypted ZIP and 7z members; null refuses encrypted data. */
    public val password: ArchivePasswordProvider? = builder.password
    /** AES, HMAC and PBKDF2 for WinZip AES and 7z AES; null refuses them. ZipCrypto needs none. */
    public val crypto: CryptoProvider? = builder.crypto

    /** Mutable single-thread configuration; [build] snapshots the property values. */
    public class Builder public constructor() {
        /** Policy passed to each reader and gzip decoding stage. */
        public var limits: ArchiveLimits = ArchiveLimits.DEFAULT
        /** Null preserves ZIP's CP437 and the other readers' UTF-8 defaults. */
        public var nameDecoder: ArchiveNameDecoder? = null
        /** Registered implementations for ZIP payload decoding. */
        public var codecs: CodecRegistry = CodecRegistry.DEFAULT
        /** Resolver for externally stored members, initially null. */
        public var externalMembers: ExternalMemberResolver? = null
        /** Password source for encrypted members, initially null. */
        public var password: ArchivePasswordProvider? = null
        /** Crypto primitives for AES-encrypted members, initially null. */
        public var crypto: CryptoProvider? = null

        internal constructor(source: ArchiveOpenOptions) : this() {
            limits = source.limits
            nameDecoder = source.nameDecoder
            codecs = source.codecs
            externalMembers = source.externalMembers
            password = source.password
            crypto = source.crypto
        }

        /** Snapshot settings, reserving an argument-validation error contract for future options. */
        @Throws(IllegalArgumentException::class)
        public fun build(): ArchiveOpenOptions = ArchiveOpenOptions(this)
    }

    /**
     * Copy settings through an independent builder.
     * @throws IllegalArgumentException if [configure] rejects an invalid setting.
     */
    @Throws(IllegalArgumentException::class)
    @JvmOverloads
    public fun copy(configure: Builder.() -> Unit = {}): ArchiveOpenOptions = Builder(this).apply(configure).build()

    /** Default settings and builder-based construction. */
    public companion object {
        /** Preserve each reader's default names, limits and codecs. */
        @JvmField public val DEFAULT: ArchiveOpenOptions = Builder().build()

        /**
         * Configure settings before taking an immutable snapshot.
         * @throws IllegalArgumentException if [configure] rejects an invalid setting.
         */
        @Throws(IllegalArgumentException::class)
        @JvmStatic
        @JvmOverloads
        public operator fun invoke(configure: Builder.() -> Unit = {}): ArchiveOpenOptions = Builder().apply(configure).build()
    }
}
