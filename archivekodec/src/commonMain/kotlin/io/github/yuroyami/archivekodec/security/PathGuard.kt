package io.github.yuroyami.archivekodec.security

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
     *
     * @throws ArchivePathException if the name is unsafe.
     */
    fun sanitize(entryName: String): String {
        if (entryName.isEmpty()) throw ArchivePathException("empty entry name")
        for (ch in entryName) {
            if (ch.code < 0x20 || ch.code == 0x7f) {
                throw ArchivePathException("control/NUL character in entry name: '$entryName'")
            }
        }

        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/")) throw ArchivePathException("absolute path: '$entryName'")
        if (normalized.length >= 2 && normalized[1] == ':') {
            throw ArchivePathException("drive-letter path: '$entryName'")
        }

        val stack = ArrayList<String>()
        for (part in normalized.split('/')) {
            when (part) {
                "", "." -> {} // collapse empty + current-dir segments
                ".." -> throw ArchivePathException("path traversal ('..') in: '$entryName'")
                else -> stack.add(part)
            }
        }
        if (stack.isEmpty()) throw ArchivePathException("entry resolves to nothing: '$entryName'")
        return stack.joinToString("/")
    }

    /** Non-throwing variant: true if [entryName] is a safe relative path. */
    fun isSafe(entryName: String): Boolean =
        try { sanitize(entryName); true } catch (_: ArchivePathException) { false }
}

/** Thrown when an archive entry name fails the [PathGuard] checks. */
class ArchivePathException(message: String) : Exception(message)
