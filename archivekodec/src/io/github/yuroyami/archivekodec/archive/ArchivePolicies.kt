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

/** What a reader does when two entries share one name. */
public enum class DuplicateNamePolicy {
    /** List both, and let a lookup by name return the last, as Info-ZIP's `unzip` extracts it. The default. */
    LAST_WINS,

    /** Refuse the archive with an [io.github.yuroyami.archivekodec.ArchiveFormatException]. */
    REJECT,
}
