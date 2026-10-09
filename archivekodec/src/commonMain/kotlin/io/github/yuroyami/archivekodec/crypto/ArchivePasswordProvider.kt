package io.github.yuroyami.archivekodec.crypto

import kotlin.jvm.JvmStatic

/**
 * Supplies passwords for encrypted members. Readers call it when they meet encrypted
 * data: with the member's name, or with null for archive-wide encryption such as 7z's
 * encrypted headers. Returning null means no password is known, which fails that read
 * with [io.github.yuroyami.archivekodec.ArchivePasswordException].
 *
 * The returned array is read and not retained; the caller may clear it afterwards.
 */
public fun interface ArchivePasswordProvider {
    /** The password for [entryName], or null when there is none. */
    public fun password(entryName: String?): CharArray?

    /** Fixed-password providers. */
    public companion object {
        /** A provider that answers [password] for every member; the array is copied. */
        @JvmStatic
        public fun of(password: CharArray): ArchivePasswordProvider {
            val copy = password.copyOf()
            return ArchivePasswordProvider { copy.copyOf() }
        }

        /** A provider that answers [password] for every member. */
        @JvmStatic
        public fun of(password: String): ArchivePasswordProvider = of(password.toCharArray())
    }
}
