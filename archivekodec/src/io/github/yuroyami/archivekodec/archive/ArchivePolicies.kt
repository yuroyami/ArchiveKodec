package io.github.yuroyami.archivekodec.archive

/** What a read does when an entry's bytes do not match the CRC-32 the archive stores. */
public enum class CrcPolicy {
    /** Throw an [io.github.yuroyami.archivekodec.ArchiveFormatException]. The default. */
    STRICT,

    /**
     * Return the bytes and tell [ArchiveOpenOptions.crcMismatchListener], for readers that would
     * rather show part of a damaged document than nothing. ZipCrypto entries stay strict, because
     * there a CRC-32 mismatch is how a wrong password shows.
     */
    LENIENT,
}

/** Told about each entry whose CRC-32 does not match under [CrcPolicy.LENIENT]. */
public fun interface CrcMismatchListener {
    /** [entry] decoded to bytes whose CRC-32 is [actual], where the archive stores [expected]. */
    public fun onMismatch(entry: ArchiveEntry, expected: Long, actual: Long)
}
