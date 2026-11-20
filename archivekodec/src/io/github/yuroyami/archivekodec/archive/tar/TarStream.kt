package io.github.yuroyami.archivekodec.archive.tar

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.FailStop
import io.github.yuroyami.archivekodec.archive.decodeStoredName
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.SourceBuffer
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.jvm.JvmOverloads

/**
 * Writes a TAR entry by entry to a [ByteSink], reading each file's data from a [ByteSource],
 * so neither the archive nor any member has to fit in memory. Names follow [TarWriter]: a
 * ustar prefix split where possible, otherwise a GNU long-name record. A file larger than
 * the 8 GiB ustar field gets a pax size record. Call [finish] to write the end records.
 * Not thread-safe.
 *
 * The writer is fail-stop: once a call has handed any byte to the sink and then fails, for a
 * failure of the data source, the sink, or data that ends early, the archive in the sink is
 * incomplete, and every later put or [finish] rethrows that same exception without writing. A
 * failure before anything is written, such as a refused argument or name, leaves the writer
 * usable. A source read that returns 0, or more than was asked, is refused with
 * [ArchiveFormatException].
 */
public class TarStreamWriter(private val sink: ByteSink) {
    private var finished = false
    private val failure = FailStop()
    private val out = failure.tracking(sink)
    private val buffer = ByteArray(64 * 1024)

    /**
     * Write a regular file of exactly [size] bytes read from [data].
     *
     * @throws ArchiveFormatException for an invalid name, or when [data] ends before [size] bytes.
     * @throws ArchiveException when the source or the sink fails, or the failure that stopped the writer.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun putFile(name: String, size: Long, data: ByteSource, mode: Int = 0x1A4, modificationTime: Long = 0): Unit = failure.guardedWrite {
        check(!finished) { "the TAR stream is finished" }
        require(size >= 0) { "size must not be negative" }
        require(mode in 0..0xFFF) { "TAR: mode must contain only Unix permission bits" }
        val header = TarWriter.headerRecords(name, isDirectory = false, size, mode, 0, 0, modificationTime)
        out.write(header, 0, header.size)
        var remaining = size
        while (remaining > 0) {
            val asked = minOf(buffer.size.toLong(), remaining).toInt()
            val n = data.read(buffer, 0, asked)
            if (n < 0) throw ArchiveFormatException("TAR: '$name' ended after ${size - remaining} of $size bytes", entryName = name)
            FailStop.checkRead(n, asked, "TAR", name)
            out.write(buffer, 0, n)
            remaining -= n
        }
        val padding = ((512 - (size and 511)) and 511).toInt()
        if (padding > 0) out.write(ByteArray(padding), 0, padding)
    }

    /**
     * Write a directory entry; [name] gains a trailing slash if it has none.
     * @throws ArchiveFormatException for an invalid name.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun putDirectory(name: String, mode: Int = 0x1ED, modificationTime: Long = 0): Unit = failure.guardedWrite {
        check(!finished) { "the TAR stream is finished" }
        require(mode in 0..0xFFF) { "TAR: mode must contain only Unix permission bits" }
        val header = TarWriter.headerRecords(if (name.endsWith('/')) name else "$name/", isDirectory = true, 0, mode, 0, 0, modificationTime)
        out.write(header, 0, header.size)
    }

    /**
     * Write the two zero records that end the archive. Later calls do nothing once they were
     * written, and rethrow the failure if writing them, or an earlier call, failed.
     */
    @Throws(ArchiveException::class)
    public fun finish(): Unit = failure.guardedWrite {
        if (finished) return@guardedWrite
        finished = true
        out.write(ByteArray(1024), 0, 1024)
    }
}

/**
 * Reads a TAR forward from a [ByteSource], one entry at a time, without seeking: [next] moves
 * to the next entry, skipping whatever of the current one's data was not read, and [read]
 * streams the current entry's data. It understands ustar, GNU long names and links, and pax
 * paths, link paths, sizes and times. Sparse members are refused with
 * [ArchiveUnsupportedException]; [TarReader] reads them from a seekable source. Names are
 * checked as [ArchiveLimits.rejectUnsafeNames] says. Not thread-safe.
 *
 * The reader is fail-stop: once [next], [read] or a [data] source throws, the reader is
 * failed and every later call to them rethrows that same exception. It reads forward and
 * cannot rewind, so after a refused or damaged entry it cannot tell where the next header
 * starts. A refused name or sparse member is such a failure too; to list past unsafe names,
 * turn [ArchiveLimits.rejectUnsafeNames] off and check each [ArchiveEntry.name] yourself,
 * with [io.github.yuroyami.archivekodec.security.PathGuard] for example.
 * A size refusal is the one exception: when [read] or a [data] source refuses an entry because
 * what is left of it exceeds [ArchiveLimits.maxUncompressedBytes], nothing was consumed, so the
 * reader stays usable and [next] skips it.
 *
 * Decoders write to a [ByteSink] rather than offer a source, so for a `.tar.gz` and the like,
 * decode into a [TarStreamSink] instead.
 */
public class TarStreamReader @JvmOverloads public constructor(
    source: ByteSource,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    private val nameDecoder: ArchiveNameDecoder = ArchiveNameDecoder.UTF8,
) {
    private val input = SourceBuffer(source)
    private var remaining = 0L
    private var padding = 0
    private var ended = false

    /** The entry [next] returned last, or null before the first and after the end. */
    public var current: ArchiveEntry? = null
        private set

    private val headers = TarHeaderState(limits, nameDecoder)
    private val failure = FailStop()

    /**
     * Move to the next entry and return it, or null at the end of the archive. After any
     * exception the reader is failed and later calls rethrow it.
     *
     * @throws ArchiveFormatException for a damaged or truncated header.
     * @throws ArchiveUnsupportedException for a sparse member.
     * @throws io.github.yuroyami.archivekodec.security.ArchivePathException for an unsafe name, when the limits reject them.
     * @throws DecompressionBombException past [ArchiveLimits.maxEntries].
     */
    @Throws(ArchiveException::class)
    public fun next(): ArchiveEntry? = failure.guarded { nextEntry() }

    private fun nextEntry(): ArchiveEntry? {
        skip(remaining + padding)
        remaining = 0
        padding = 0
        current = null
        if (ended) return null
        val header = ByteArray(BLOCK)
        while (true) {
            val at = input.streamPosition
            if (input.atEnd()) { ended = true; return null } // a TAR may lack its end records
            input.readFully(header, 0, BLOCK, "TAR header")
            when (val step = headers.onHeader(header, at)) {
                TarHeaderState.Step.End -> { ended = true; return null }
                is TarHeaderState.Step.Metadata -> {
                    val data = ByteArray(step.size)
                    input.readFully(data, 0, data.size, "TAR metadata record")
                    skip(step.padding.toLong())
                    headers.onMetadata(step.type, data)
                }
                is TarHeaderState.Step.Skip -> skip(step.bytes)
                is TarHeaderState.Step.Entry -> {
                    remaining = step.dataSize
                    padding = step.padding
                    if (step.skipAfter > 0) skip(step.skipAfter)
                    current = step.entry
                    return step.entry
                }
            }
        }
    }

    /**
     * Stream the current entry's data, or the part not read yet, to [sink]; return the bytes
     * written. [limits] bound the entry's size: an entry larger than they allow is refused
     * before anything is consumed, and [next] can skip it. After any other exception the reader
     * is failed and later calls rethrow it.
     * @throws ArchiveException for a truncated archive or a sink failure.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun read(sink: ByteSink, limits: ArchiveLimits = this.limits): Long {
        failure.check()
        val entry = current ?: throw IllegalStateException("no current entry; call next() first")
        limits.checkOutputSize(remaining)
        return failure.guarded { copyTo(sink, entry) }
    }

    private fun copyTo(sink: ByteSink, entry: ArchiveEntry): Long {
        var written = 0L
        while (remaining > 0) {
            if (!input.ensure(1)) throw ArchiveFormatException("TAR: '${entry.name}' is truncated", input.streamPosition, entry.name)
            val n = minOf(remaining, input.available.toLong()).toInt()
            sink.write(input.buffer, input.position, n)
            input.position += n
            remaining -= n
            written += n
        }
        return written
    }

    /**
     * The current entry's data as a source, for a decoder or another writer. A failure of the
     * source fails the reader, as one of [read] does. Like [read], it refuses an entry larger
     * than the reader's [ArchiveLimits.maxUncompressedBytes], leaving the reader usable.
     */
    public fun data(): ByteSource = ByteSource { into, offset, length ->
        failure.check()
        if (current == null) throw IllegalStateException("no current entry; call next() first")
        // The same size rule as read, and like it, a refusal consumes nothing.
        limits.checkOutputSize(remaining)
        failure.guarded { dataRead(into, offset, length) }
    }

    private fun dataRead(into: ByteArray, offset: Int, length: Int): Int =
        when {
            current == null -> throw IllegalStateException("no current entry; call next() first")
            remaining == 0L -> -1
            !input.ensure(1) -> throw ArchiveFormatException("TAR: '${current!!.name}' is truncated", input.streamPosition)
            else -> {
                val n = minOf(remaining, input.available.toLong(), length.toLong()).toInt()
                input.buffer.copyInto(into, offset, input.position, input.position + n)
                input.position += n
                remaining -= n
                n
            }
        }

    private fun skip(count: Long) {
        var left = count
        while (left > 0) {
            if (!input.ensure(1)) throw ArchiveFormatException("TAR: archive is truncated", input.streamPosition)
            val n = minOf(left, input.available.toLong()).toInt()
            input.position += n
            left -= n
        }
    }

    private companion object {
        const val BLOCK = 512
    }
}

/**
 * What one TAR header means, with the long names, long links and pax records before it: the
 * part of reading a TAR that does not depend on whether the bytes are pulled or pushed.
 */
internal class TarHeaderState(private val limits: ArchiveLimits, private val nameDecoder: ArchiveNameDecoder) {
    private val duplicates = if (limits.rejectDuplicateNames) io.github.yuroyami.archivekodec.archive.DuplicateNames() else null

    sealed class Step {
        /** Two zero records, or one: the archive ends. */
        object End : Step()
        /** A metadata record of [size] bytes follows, then [padding]; pass it to [onMetadata]. */
        class Metadata(val type: Char, val size: Int, val padding: Int) : Step()
        /** Nothing to report: skip [bytes]. */
        class Skip(val bytes: Long) : Step()
        /** An entry: [dataSize] bytes of its data follow, then [padding]; or [skipAfter] bytes to skip. */
        class Entry(val entry: ArchiveEntry, val dataSize: Long, val padding: Int, val skipAfter: Long) : Step()
    }

    private var longName: ByteArray? = null
    private var longLink: ByteArray? = null
    private var local: PaxRecords? = null
    private var global = PaxRecords.EMPTY
    private var count = 0

    fun onMetadata(type: Char, data: ByteArray) {
        when (type) {
            'L' -> longName = trimNul(data)
            'K' -> longLink = trimNul(data)
            'x' -> local = PaxRecords.parse(ByteArrayRandomAccessSource(data), 0, data.size.toLong())
            else -> global = PaxRecords.parse(ByteArrayRandomAccessSource(data), 0, data.size.toLong()).under(global)
        }
    }

    fun onHeader(header: ByteArray, at: Long): Step {
        if (TarHeader.isZeroRecord(header)) return Step.End
        if (!TarHeader.hasValidChecksum(header)) throw ArchiveFormatException("TAR: bad header checksum", at)
        val type = header[156].toInt().toChar()
        val pax = if (type == 'x' || type == 'g' || type == 'L' || type == 'K') PaxRecords.EMPTY
            else (local ?: PaxRecords.EMPTY).under(global)
        val size = when (type) {
            '3', '4', '5', '6' -> 0L
            else -> pax.text("size", at)?.let { paxNumber(it, "size", at) } ?: numeric(header, 124, 12, at)
        }
        if (size < 0) throw ArchiveFormatException("TAR: negative size", at + 124)
        val pad = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        when (type) {
            'L', 'K', 'x', 'g' -> {
                if (size > MAX_METADATA) throw ArchiveFormatException("TAR: a $size-byte $type record is larger than $MAX_METADATA bytes", at)
                return Step.Metadata(type, size.toInt(), pad)
            }
            'S' -> throw ArchiveUnsupportedException("TAR: sparse members cannot be read forward; use TarReader", at)
        }
        if (pax.values.keys.any { it.startsWith("GNU.sparse.") }) {
            throw ArchiveUnsupportedException("TAR: sparse members cannot be read forward; use TarReader", at)
        }
        val paxName = pax.values["path"]
        val nameBytes = paxName ?: longName ?: run {
            val base = cString(header, 0, 100)
            val prefix = if (TarHeader.usesUstarPrefix(header)) cString(header, 345, 155) else byteArrayOf()
            if (prefix.isNotEmpty()) prefix + byteArrayOf(47) + base else base
        }
        val linkBytes = pax.values["linkpath"] ?: longLink ?: cString(header, 157, 100)
        longName = null
        longLink = null
        local = null
        if (nameBytes.isEmpty()) return Step.Skip(size + pad)
        val utf8 = pax.text("hdrcharset", at) != "BINARY"
        val name = (if (paxName != null && utf8) ArchiveNameDecoder.UTF8 else nameDecoder).decodeStoredName(nameBytes, at)
        val permissions = optionalNumeric(header, 100, 8, at)?.let { (it and 0xFFF).toInt() }
        val linkTarget = if (type == '1' || type == '2') {
            (if (pax.values["linkpath"] != null && utf8) ArchiveNameDecoder.UTF8 else nameDecoder).decodeStoredName(linkBytes, at + 157)
        } else null
        val isDirectory = type == '5' || name.endsWith("/")
        val hasData = (type == '0' || type == '\u0000' || type == '7') && !isDirectory
        val entry = when (type) {
            '2' -> ArchiveEntry(name, linkBytes.size.toLong(), false) {
                tarMetadata(header, pax, type, at)
                unixMode = (permissions ?: 0x1FF) or 0xA000
                isSymbolicLink = true
                this.linkTarget = linkTarget
            }
            '1' -> ArchiveEntry(name, 0, false) {
                tarMetadata(header, pax, type, at)
                unixMode = permissions?.let { it or 0x8000 }
                compressedSize = 0
                isHardLink = true
                this.linkTarget = linkTarget
            }
            else -> ArchiveEntry(name, if (hasData) size else 0, isDirectory) {
                tarMetadata(header, pax, type, at)
                // Devices and FIFOs keep their type even when the mode field is empty.
                val fileType = tarSpecialType(type) ?: if (isDirectory) 0x4000 else 0x8000
                unixMode = permissions?.let { it or fileType } ?: tarSpecialType(type)?.let { it or 0x1A4 }
                compressedSize = if (hasData) size else 0
            }
        }
        if (limits.rejectUnsafeNames && !io.github.yuroyami.archivekodec.security.PathGuard.isRootDirectory(entry)) {
            io.github.yuroyami.archivekodec.security.PathGuard.sanitize(name)
        }
        duplicates?.add(entry)
        limits.checkEntryCount(++count)
        // Devices, FIFOs, directories and links have no data to report; whatever they store is skipped.
        return if (hasData) Step.Entry(entry, size, pad, 0) else Step.Entry(entry, 0, 0, size + pad)
    }

    private fun trimNul(bytes: ByteArray): ByteArray {
        var end = bytes.size
        while (end > 0 && bytes[end - 1].toInt() == 0) end--
        return bytes.copyOf(end)
    }

    companion object {
        private const val BLOCK = 512

        /** The largest GNU long name or link, or pax record, read into memory: 1 MiB. TarReader caps L and K records with it too. */
        const val MAX_METADATA = 1L shl 20
    }
}

/** Hears about the entries a [TarStreamSink] finds, and their data, as the bytes arrive. */
public interface TarStreamVisitor {
    /** A new entry begins; its data, if any, follows through [onData]. */
    @Throws(ArchiveException::class)
    public fun onEntry(entry: ArchiveEntry)

    /** The next piece of the current entry's data. The array is reused after this returns. */
    @Throws(ArchiveException::class)
    public fun onData(entry: ArchiveEntry, bytes: ByteArray, offset: Int, length: Int)
}

/**
 * Reads a TAR from bytes pushed into it, the push-side partner of [TarStreamReader]: write the
 * archive to this sink in any pieces, for instance as the output of
 * `Codecs.GZIP.decompress(source, sink, limits)`, and the [visitor] hears about each entry
 * and its data as they arrive. Memory stays near one header and the metadata records, whatever
 * the archive's size. Call [finish] after the last byte, to check that the archive was complete.
 * It reads what [TarStreamReader] reads and refuses the same. Not thread-safe.
 *
 * Like [TarStreamReader], the sink is fail-stop: once [write] or [finish] throws, including
 * for an exception from the [visitor], the sink is failed and every later [write] or [finish]
 * rethrows that same exception, since it cannot tell where the next header starts. To list
 * past unsafe names, turn [ArchiveLimits.rejectUnsafeNames] off and check each
 * [ArchiveEntry.name] in [TarStreamVisitor.onEntry] yourself.
 */
public class TarStreamSink @JvmOverloads public constructor(
    private val visitor: TarStreamVisitor,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    nameDecoder: ArchiveNameDecoder = ArchiveNameDecoder.UTF8,
) : ByteSink {
    private val headers = TarHeaderState(limits, nameDecoder)
    private val header = ByteArray(512)
    private var headerFill = 0
    private var metadata: ByteArray? = null
    private var metadataType = ' '
    private var metadataFill = 0
    private var metadataPadding = 0
    private var entry: ArchiveEntry? = null
    private var data = 0L
    private var skip = 0L
    private var position = 0L
    private var ended = false
    private val failure = FailStop()

    override fun write(bytes: ByteArray, offset: Int, length: Int): Unit = failure.guarded { push(bytes, offset, length) }

    private fun push(bytes: ByteArray, offset: Int, length: Int) {
        var at = offset
        val end = offset + length
        while (at < end) {
            val n: Int
            when {
                ended -> return // after the end records, padding to the record size may follow
                data > 0 -> {
                    n = minOf(data, (end - at).toLong()).toInt()
                    visitor.onData(entry!!, bytes, at, n)
                    data -= n
                }
                skip > 0 -> n = minOf(skip, (end - at).toLong()).toInt().also { skip -= it }
                metadata != null -> {
                    val record = metadata!!
                    n = minOf(record.size - metadataFill, end - at)
                    bytes.copyInto(record, metadataFill, at, at + n)
                    metadataFill += n
                    if (metadataFill == record.size) {
                        headers.onMetadata(metadataType, record)
                        metadata = null
                        skip = metadataPadding.toLong()
                    }
                }
                else -> {
                    n = minOf(512 - headerFill, end - at)
                    bytes.copyInto(header, headerFill, at, at + n)
                    headerFill += n
                    if (headerFill == 512) {
                        headerFill = 0
                        onHeader(position + n - 512)
                    }
                }
            }
            at += n
            position += n
        }
    }

    private fun onHeader(at: Long) {
        when (val step = headers.onHeader(header, at)) {
            TarHeaderState.Step.End -> ended = true
            is TarHeaderState.Step.Metadata -> {
                metadata = ByteArray(step.size)
                metadataType = step.type
                metadataFill = 0
                metadataPadding = step.padding
                if (step.size == 0) { headers.onMetadata(step.type, ByteArray(0)); metadata = null; skip = step.padding.toLong() }
            }
            is TarHeaderState.Step.Skip -> skip = step.bytes
            is TarHeaderState.Step.Entry -> {
                // An entry's data must fit the limits, as for TarStreamReader.read.
                limits.checkOutputSize(step.dataSize)
                entry = step.entry
                visitor.onEntry(step.entry)
                data = step.dataSize
                skip = step.padding + step.skipAfter
            }
        }
    }

    /**
     * Check that the archive ended cleanly: no entry, record or header was cut short.
     * @throws ArchiveFormatException for a truncated archive, or the failure that stopped the sink.
     */
    @Throws(ArchiveException::class)
    public fun finish(): Unit = failure.guarded {
        if (data > 0) throw ArchiveFormatException("TAR: '${entry?.name}' is truncated", position, entry?.name)
        if (!ended && (headerFill != 0 || metadata != null || skip > 0)) throw ArchiveFormatException("TAR: archive is truncated", position)
    }
}
