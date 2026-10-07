package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry

/**
 * Zip-Slip / path-traversal defence (Snyk 2018; CVE-2020-8913; CVE-2023-35947;
 * Android "Zip Path Traversal"). Every entry name read out of an archive MUST pass
 * through [sanitize] before it is joined to an output directory.
 *
 * The guard rejects, by default and impossible to forget:
 *  - empty names and names containing NUL or control characters,
 *  - absolute paths (`/foo`) and Windows drive-letter paths (`C:\foo`),
 *  - any `..` traversal segment (rejected outright, not just resolved),
 *  - backslash separators (normalised, then re-checked).
 *
 * It returns a clean, forward-slash relative path safe to resolve under a root.
 * (Spaces in names are allowed. Only control characters and NUL are rejected.)
 */
object PathGuard {

    /**
     * Validate and normalise [entryName] to a safe relative path.
     * Names resolving to the root (for example `.`) are rejected as file destinations.
     * Use [isRootDirectory] to skip directory entries naming the root before this call.
     *
     * @throws ArchivePathException if the name is unsafe.
     */
    @Throws(ArchiveException::class)
    fun sanitize(entryName: String): String = normalize(entryName, allowRoot = false)

    /**
     * Whether [entry] is a directory naming the extraction root, such as `.` or `./`.
     * Callers can skip this entry before [sanitize], without creating a file or changing
     * the root directory. Empty or unsafe names and non-directory entries return false.
     * A false result does not imply safety: validate the remaining name with [sanitize].
     */
    fun isRootDirectory(entry: ArchiveEntry): Boolean = entry.isDirectory &&
        try { normalize(entry.name, allowRoot = true).isEmpty() } catch (_: ArchivePathException) { false }

    private fun normalize(entryName: String, allowRoot: Boolean): String {
        if (entryName.isEmpty()) throw ArchivePathException("empty entry name", entryName)
        for (ch in entryName) {
            if (ch.code < 0x20 || ch.code == 0x7f) {
                throw ArchivePathException("control/NUL character in entry name: '$entryName'", entryName)
            }
        }

        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/")) throw ArchivePathException("absolute path: '$entryName'", entryName)

        val stack = ArrayList<String>()
        for (part in normalized.split('/')) {
            when (part) {
                "", "." -> {} // collapse empty + current-dir segments
                ".." -> throw ArchivePathException("path traversal ('..') in: '$entryName'", entryName)
                else -> stack.add(part)
            }
        }
        if (stack.isEmpty()) {
            if (allowRoot) return ""
            throw ArchivePathException("entry resolves to nothing: '$entryName'", entryName)
        }
        val result = stack.joinToString("/")
        // Collapsing a leading ./ can expose a Windows drive prefix.
        if (result.length >= 2 && result[1] == ':') {
            throw ArchivePathException("drive-letter path: '$entryName'", entryName)
        }
        return result
    }

    /** Non-throwing variant: true if [entryName] is a safe relative path. */
    fun isSafe(entryName: String): Boolean =
        try { sanitize(entryName); true } catch (_: ArchivePathException) { false }
}

/** Thrown when an archive entry name fails the [PathGuard] checks. */
class ArchivePathException : ArchiveException {
    constructor(message: String) : super(message)
    constructor(message: String, entryName: String) : super(message, entryName = entryName)
}
