package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * Supplies the bytes of members that an archive references instead of storing, such as the
 * object files a GNU thin `ar` archive lists. The library never opens files itself: map the
 * stored [path] (usually relative to the archive's directory) to bytes however suits the
 * caller, and validate it as untrusted input. Return null when the member is unavailable.
 */
public fun interface ExternalMemberResolver {
    /**
     * Return the referenced member's bytes, or null if it cannot be found.
     * @throws ArchiveException to report a failure with archive context.
     */
    @Throws(ArchiveException::class)
    public fun resolve(path: String): ByteArray?
}
