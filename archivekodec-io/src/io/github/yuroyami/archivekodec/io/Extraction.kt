@file:JvmName("ArchiveExtraction")

package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.EntryType
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.crypto.ArchivePasswordProvider
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlin.jvm.JvmName

/** Receives extraction progress: bytes written so far against the total expected. */
public fun interface ProgressSink {
    /**
     * Called after each chunk is written. [bytesDone] only rises; [bytesTotal] is the sum of
     * the requested entries' listed sizes, or -1 when any of them is unknown (a ZIP entry
     * sized only by its data descriptor lists a size of -1).
     */
    public fun progress(bytesDone: Long, bytesTotal: Long)
}

/**
 * Passwords asked for while extracting, by a suspending [prompt] such as a dialog. Pass the
 * same object to the reader, as [io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions.password],
 * and to [extract]: before each encrypted entry, [extract] suspends on [prompt] with the
 * entry's name, and the reader then takes that answer. A null answer leaves the entry
 * without a password, so its read fails with
 * [io.github.yuroyami.archivekodec.ArchivePasswordException]. The prompt is asked once per
 * encrypted entry; remember an answer inside it to reuse one password for a whole archive.
 * The encrypted header of a 7z archive is asked for while listing, before [extract] runs, so
 * it is not prompted for: supply that password with [ArchivePasswordProvider.of].
 */
public class PromptedPasswords(private val prompt: suspend (entryName: String) -> CharArray?) : ArchivePasswordProvider {
    private var name: String? = null
    private var answer: CharArray? = null

    internal suspend fun ask(entryName: String) {
        forget()
        val password = prompt(entryName) ?: return
        name = entryName
        answer = password.copyOf()
        password.fill('\u0000')
    }

    internal fun forget() {
        answer?.fill('\u0000')
        answer = null
        name = null
    }

    /** The answer [extract] collected for [entryName], or null. Readers call this. */
    override fun password(entryName: String?): CharArray? = if (entryName != null && entryName == name) answer?.copyOf() else null
}

/**
 * Extract [entries] to the sinks [into] returns, one per entry, and close each sink after
 * its entry. Entries must come from this reader's [ArchiveReader.entries], and each one
 * selects its own listing slot: of two members with identical metadata, passing one extracts
 * only that one. An unchanged copy of a listed entry selects the first slot with equal
 * metadata that no other given entry has taken. Entries are extracted in listing order,
 * whatever order they are given in, so a solid block (a 7z folder, a cabinet folder, a solid
 * RAR5 stream) is decoded once for all the entries it holds. Directories are skipped: [into] is called for files and other members with data.
 * [limits] apply to each entry, and [maxTotalBytes] to all of them together: the extraction
 * fails with a [DecompressionBombException] before a write would pass it, or before it starts
 * when the listed sizes already do. A file that is safe alone can still fill a disk in
 * company, so set a total budget for untrusted archives.
 *
 * When an entry fails, for a decoding, integrity, limit or sink error or a cancellation,
 * its sink keeps what was written before the failure and is closed. [onFailure] is then
 * called with the entry and the error, before the error propagates, so the caller can delete
 * the partial file; the extraction stops there. Writing to a temporary name and renaming it
 * after [extract] returns is the other safe pattern.
 *
 * Symbolic links are never written through [into]. By default they are skipped, the safe
 * choice for untrusted archives: a link to `../..` followed by a file "inside" it is the
 * classic way out of an extraction folder. With [onLink], each symbolic link is passed to it
 * with its target (read from the member's data where the format keeps it there), after the
 * files before it; check the target with
 * [io.github.yuroyami.archivekodec.security.PathGuard.resolveLink] before creating anything.
 * Hard links are extracted as ordinary files holding the data of the member they name, so
 * they cannot reach outside the archive either.
 *
 * Cancelling the calling coroutine stops the extraction between entries and, for entries
 * that stream (see [ArchiveReader.read] with a sink), before each write of about 1 MiB, so a
 * large entry stays cancellable. [progress] hears about every write. With [passwords],
 * each encrypted entry first suspends on its prompt; open the reader with the same object.
 *
 * @throws IllegalArgumentException if an entry was not listed by this reader, or [maxTotalBytes] is negative.
 * @throws DecompressionBombException if the entries together pass [maxTotalBytes].
 * @throws ArchiveIOException if a sink reports an IO failure.
 * @throws ArchiveException for the reading, decoding, integrity and limit failures of [ArchiveReader.read].
 * @throws kotlinx.coroutines.CancellationException when the calling coroutine is cancelled.
 */
@Throws(ArchiveException::class, CancellationException::class)
public suspend fun ArchiveReader.extract(
    entries: Collection<ArchiveEntry>,
    limits: ArchiveLimits = this.limits,
    progress: ProgressSink? = null,
    passwords: PromptedPasswords? = null,
    onLink: ((entry: ArchiveEntry, target: String) -> Unit)? = null,
    maxTotalBytes: Long = Long.MAX_VALUE,
    onFailure: ((entry: ArchiveEntry, error: Throwable) -> Unit)? = null,
    into: (ArchiveEntry) -> RawSink,
) {
    require(maxTotalBytes >= 0) { "maxTotalBytes must not be negative, not $maxTotalBytes" }
    val listing = entries()
    val chosen = BooleanArray(listing.size)
    var missing = 0
    for (entry in entries) {
        // The listed object itself selects its slot; equality alone cannot tell apart two
        // members with identical metadata, such as two TAR members of one name and size.
        var slot = listing.indexOfFirst { it === entry }
        if (slot < 0) slot = listing.indices.firstOrNull { !chosen[it] && listing[it] == entry } ?: listing.indexOf(entry)
        if (slot < 0) missing++ else chosen[slot] = true
    }
    require(missing == 0) { "$missing of the entries were not listed by this reader" }
    // Devices, FIFOs and sockets are never created: writing one as a file would be wrong, and
    // creating a device node from an archive is a privilege the caller should not hand out.
    val selected = listing.filterIndexed { index, entry -> chosen[index] && !entry.isDirectory && entry.type.isExtractable() }
    val ordered = selected.filter { !it.isSymbolicLink }
    val total = if (ordered.any { it.size < 0 }) -1L else ordered.sumOf { it.size }
    if (total > maxTotalBytes) throw DecompressionBombException("extract: the entries list $total bytes, more than the total budget of $maxTotalBytes")
    var done = 0L
    val context = currentCoroutineContext()
    for (entry in selected) {
        if (entry.isSymbolicLink) {
            if (onLink != null) {
                context.ensureActive()
                val target = entry.linkTarget ?: read(entry, limits).decodeToString()
                onLink(entry, target)
            }
            continue
        }
        context.ensureActive()
        if (entry.isEncrypted && passwords != null) passwords.ask(entry.name)
        val sink = into(entry)
        val buffer = Buffer()
        var completed = false
        var failure: Throwable? = null
        try {
            read(entry, ByteSink { bytes, offset, length ->
                context.ensureActive()
                if (length > maxTotalBytes - done) {
                    throw DecompressionBombException("extract: '${entry.name}' takes the output past the total budget of $maxTotalBytes bytes", null, entry.name, null)
                }
                buffer.write(bytes, offset, offset + length)
                try {
                    sink.write(buffer, buffer.size)
                } catch (error: IOException) {
                    throw ArchiveIOException("writing '${entry.name}' failed: ${error.message}", error)
                }
                done += length
                progress?.progress(done, total)
            }, limits)
            try {
                sink.flush()
            } catch (error: IOException) {
                throw ArchiveIOException("writing '${entry.name}' failed: ${error.message}", error)
            }
            completed = true
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            passwords?.forget()
            try {
                sink.close()
            } catch (error: IOException) {
                // After a failure, that failure propagates; after a success, the close failure does.
                if (completed) {
                    val closing = ArchiveIOException("closing the sink for '${entry.name}' failed: ${error.message}", error)
                    onFailure?.invoke(entry, closing)
                    throw closing
                }
            }
            failure?.let { onFailure?.invoke(entry, it) }
        }
    }
}

private fun EntryType.isExtractable(): Boolean =
    this == EntryType.FILE || this == EntryType.HARD_LINK || this == EntryType.SYMBOLIC_LINK
