package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.ByteSink

/**
 * Makes a forward-only stream reader or writer fail-stop: a kept failure is rethrown by every
 * later [guarded] or [guardedWrite] call without running its block.
 *
 * A reader that cannot rewind does not know where its input stands after a failure part-way
 * through a header or a skip, so carrying on could read a member's data as a header: [guarded]
 * keeps every failure. A writer that failed after part of an entry reached its sink would, if it
 * carried on, write an archive that looks complete but is misaligned: [guardedWrite] keeps a
 * failure once the call has handed any byte to the sink, and leaves the writer usable after one
 * that came before, such as a refused argument or name.
 */
internal class FailStop {
    private var failure: Throwable? = null

    /** For a writer: true once the current [guardedWrite] call handed bytes to the sink. */
    var wrote: Boolean = false

    inline fun <T> guarded(block: () -> T): T {
        check()
        try {
            return block()
        } catch (e: Throwable) {
            fail(e)
            throw e
        }
    }

    /** For a writer: run [block], keeping its failure only if it came after bytes went to [sink]. */
    inline fun <T> guardedWrite(block: () -> T): T {
        check()
        wrote = false
        try {
            return block()
        } catch (e: Throwable) {
            if (wrote) fail(e)
            throw e
        }
    }

    /** [sink], marking [wrote] before every write it passes on. */
    fun tracking(sink: ByteSink): ByteSink = ByteSink { bytes, offset, length ->
        wrote = true
        sink.write(bytes, offset, length)
    }

    /** Rethrow the kept failure, if there is one. */
    fun check() {
        failure?.let { throw it }
    }

    fun fail(e: Throwable) {
        if (failure == null) failure = e
    }

    companion object {
        /**
         * Check one read of a writer's source: a read must return -1 or between 1 and the
         * [asked] count, or a writer's copy loop would never end, or write more than it read.
         */
        fun checkRead(n: Int, asked: Int, format: String, name: String) {
            if (n == 0 || n > asked) throw ArchiveFormatException("$format: the source of '$name' returned $n bytes for $asked asked", entryName = name)
        }
    }
}
