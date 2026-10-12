package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveClosedException

/** Shared source ownership and clearable, typed metadata cache for built-in readers. */
internal class ReaderSource<T : Any>(source: RandomAccessSource) {
    private var owned: RandomAccessSource? = source
    private var cached: T? = null

    val source: RandomAccessSource
        get() = owned ?: throw ArchiveClosedException("archive reader is closed")

    fun metadata(parse: () -> T): T {
        source // Check before returning even previously cached metadata.
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
