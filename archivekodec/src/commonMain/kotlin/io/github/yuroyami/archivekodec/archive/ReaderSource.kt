package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveClosedException

/** Shared source ownership and clearable metadata cache for built-in readers. */
internal class ReaderSource(source: RandomAccessSource) {
    private var owned: RandomAccessSource? = source
    private var cached: List<ArchiveEntry>? = null

    val source: RandomAccessSource
        get() = owned ?: throw ArchiveClosedException("archive reader is closed")

    fun entries(parse: () -> List<ArchiveEntry>): List<ArchiveEntry> {
        source // Check before returning even previously cached entries.
        return cached ?: parse().also {
            source // A source callback may have closed the reader during parsing.
            cached = it
        }
    }

    fun close() {
        val releasing = owned ?: return
        owned = null
        cached = null
        releasing.close()
    }

    override fun toString(): String = owned?.toString() ?: "closed"
}
