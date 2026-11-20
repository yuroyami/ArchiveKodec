package io.github.yuroyami.archivekodec.archive

import kotlin.jvm.JvmStatic

/**
 * A point in time as an archive stores it: whole seconds since the Unix epoch, UTC, and a
 * nanosecond part in `0..999_999_999`. Times before 1970 have a negative [epochSeconds] and
 * a nonnegative [nanoseconds], so `-0.5 s` is `epochSeconds = -1, nanoseconds = 500_000_000`.
 * Formats store different precisions: DOS times two seconds, Unix times one second, NTFS
 * times 100 nanoseconds and pax times nanoseconds; a reader never invents digits.
 */
public class ArchiveTime(
    /** Whole seconds since 1970-01-01T00:00:00Z, rounded toward negative infinity. */
    public val epochSeconds: Long,
    /** The part below a second, in nanoseconds, `0..999_999_999`. */
    public val nanoseconds: Int = 0,
) : Comparable<ArchiveTime> {
    init {
        require(nanoseconds in 0..999_999_999) { "ArchiveTime: nanoseconds must be in 0..999999999" }
    }

    override fun compareTo(other: ArchiveTime): Int =
        if (epochSeconds != other.epochSeconds) epochSeconds.compareTo(other.epochSeconds) else nanoseconds.compareTo(other.nanoseconds)

    override fun equals(other: Any?): Boolean = other is ArchiveTime && epochSeconds == other.epochSeconds && nanoseconds == other.nanoseconds

    override fun hashCode(): Int = 31 * epochSeconds.hashCode() + nanoseconds

    /** Seconds with the fraction when there is one, for example `1700000000.25`. */
    override fun toString(): String = if (nanoseconds == 0) "$epochSeconds" else
        "$epochSeconds." + nanoseconds.toString().padStart(9, '0').trimEnd('0')

    /** Conversions from the time encodings archives use. */
    public companion object {
        /** 100-nanosecond intervals between 1601-01-01 and 1970-01-01, as Windows FILETIME counts them. */
        private const val FILETIME_UNIX_EPOCH = 116_444_736_000_000_000L

        /** A Windows FILETIME: 100-nanosecond intervals since 1601-01-01 UTC, read as unsigned. */
        @JvmStatic
        public fun fromFileTime(fileTime: Long): ArchiveTime {
            // Unsigned 64-bit: values past Long.MAX_VALUE lie beyond the year 30828 and are clamped.
            val ticks = if (fileTime < 0) Long.MAX_VALUE else fileTime
            val sinceUnix = ticks - FILETIME_UNIX_EPOCH
            val seconds = sinceUnix.floorDiv(10_000_000L)
            val rest = sinceUnix.mod(10_000_000L)
            return ArchiveTime(seconds, (rest * 100).toInt())
        }
    }
}

/** What kind of member an [ArchiveEntry] is, from its flags and Unix file type. */
public enum class EntryType {
    /** A regular file, and the type of any member whose format says nothing more. */
    FILE,
    /** A directory. */
    DIRECTORY,
    /** A symbolic link, or a Windows junction; see [ArchiveEntry.linkTarget]. */
    SYMBOLIC_LINK,
    /** A hard link to the earlier member named [ArchiveEntry.linkTarget]. */
    HARD_LINK,
    /** A character device; see [ArchiveEntry.deviceMajor]. */
    CHARACTER_DEVICE,
    /** A block device; see [ArchiveEntry.deviceMajor]. */
    BLOCK_DEVICE,
    /** A named pipe. */
    FIFO,
    /** A Unix domain socket. */
    SOCKET,
}
