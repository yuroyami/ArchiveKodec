package io.github.yuroyami.archivekodec.archive.cpio

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.DuplicateNames
import io.github.yuroyami.archivekodec.archive.decodeStoredName
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.SourceBuffer
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.PathGuard
import kotlin.jvm.JvmOverloads

/**
 * Writes a cpio archive to a [ByteSink] one member at a time, so the archive and its members
 * never sit in memory whole: [CpioWriter.Dialect.NEWC] or [CpioWriter.Dialect.ODC], with the
 * same records as [CpioWriter] writes. [CpioWriter.Dialect.CRC] is not offered, because its
 * header holds a sum of the body, which a stream has not read yet. Not thread-safe.
 */
public class CpioStreamWriter @JvmOverloads public constructor(
    private val sink: ByteSink,
    private val dialect: CpioWriter.Dialect = CpioWriter.Dialect.NEWC,
) {
    private var finished = false

    init {
        require(dialect != CpioWriter.Dialect.CRC) { "cpio: the crc dialect needs each body's sum before the body; use CpioWriter" }
    }

    /**
     * Write a regular file of [size] bytes read from [data], which must hold exactly that many.
     *
     * @throws ArchiveFormatException for an invalid name, a value an odc field cannot hold, or
     *   when [data] holds a different number of bytes than [size].
     * @throws ArchiveException for a failure of [data] or the sink.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun putFile(name: String, size: Long, data: ByteSource, mode: Int = 0x81A4, modificationTime: Long = 0, uid: Long = 0, gid: Long = 0) {
        require(mode in 0..0xFFFF && (mode and 0xF000 == 0 || mode and 0xF000 == 0x8000)) { "cpio: mode must be permission bits or a regular file mode" }
        require(size in 0..0xFFFFFFFFL) { "cpio: size must fit an unsigned 32-bit field" }
        val buffer = ByteArray(minOf(size, 64L * 1024).toInt().coerceAtLeast(1))
        record(name, mode or 0x8000, size, modificationTime, uid, gid)
        var copied = 0L
        while (true) {
            val n = data.read(buffer, 0, buffer.size)
            if (n < 0) break
            if (copied + n > size) throw ArchiveFormatException("cpio: '$name' holds more than its declared $size bytes", entryName = name)
            sink.write(buffer, 0, n)
            copied += n
        }
        if (copied != size) throw ArchiveFormatException("cpio: '$name' holds $copied bytes, not its declared $size", entryName = name)
        pad(size)
    }

    /** Write a directory. @throws ArchiveFormatException for an invalid name. */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun putDirectory(name: String, mode: Int = 0x1ED, modificationTime: Long = 0, uid: Long = 0, gid: Long = 0) {
        require(mode in 0..0xFFF) { "cpio: mode must contain only permission bits" }
        record(name, mode or 0x4000, 0, modificationTime, uid, gid)
    }

    /** Write a symbolic link to [target]. @throws ArchiveFormatException for an invalid name. */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun putSymbolicLink(name: String, target: String, modificationTime: Long = 0, uid: Long = 0, gid: Long = 0) {
        val bytes = target.encodeToByteArray()
        require(bytes.isNotEmpty() && 0.toByte() !in bytes) { "cpio: a link target must be nonempty without NUL" }
        record(name, 0xA1FF, bytes.size.toLong(), modificationTime, uid, gid)
        sink.write(bytes, 0, bytes.size)
        pad(bytes.size.toLong())
    }

    /** Write the trailer that ends the archive. Later calls do nothing. */
    @Throws(ArchiveException::class)
    public fun finish() {
        if (finished) return
        finished = true
        val trailer = CpioWriter.trailer(dialect)
        sink.write(trailer, 0, trailer.size)
    }

    private fun record(name: String, mode: Int, size: Long, modificationTime: Long, uid: Long, gid: Long) {
        check(!finished) { "the cpio stream is finished" }
        require(modificationTime in 0..0xFFFFFFFFL && uid in 0..0xFFFFFFFFL && gid in 0..0xFFFFFFFFL) {
            "cpio: metadata must fit an unsigned 32-bit field"
        }
        val encoded = CpioWriter.encodeName(name)
        val head = CpioWriter.recordHead(dialect, encoded,
            longArrayOf(0, mode.toLong(), uid, gid, 1, modificationTime, size, 0, 0, 0, 0, encoded.size + 1L, 0))
        sink.write(head, 0, head.size)
    }

    private fun pad(size: Long) {
        val padding = CpioWriter.dataPadding(dialect, size)
        if (padding > 0) sink.write(ByteArray(padding), 0, padding)
    }
}

/**
 * Reads a cpio archive forward from a [ByteSource], one record at a time, without seeking:
 * newc, crc, odc and old binary cpio, as [CpioReader] reads them. [next] moves to the next
 * record, skipping whatever of the current one was not read, and [read] streams its data.
 * A crc record's sum is checked once its body has passed, read or skipped.
 *
 * Each record is reported with the body it stores. [CpioReader] gives every link of a hard-link
 * group the group's data, but newc stores it with the last link, which a forward reader has
 * not reached at the earlier ones, so those read as empty here. Names are checked as
 * [ArchiveLimits.rejectUnsafeNames] says. Not thread-safe.
 *
 * Decoders write to a [ByteSink] rather than offer a source, so a compressed archive must be
 * decoded first, to a file for example.
 */
public class CpioStreamReader @JvmOverloads public constructor(
    source: ByteSource,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
    private val nameDecoder: ArchiveNameDecoder = ArchiveNameDecoder.UTF8,
) {
    private val input = SourceBuffer(source)
    private var remaining = 0L
    private var padding = 0L
    private var started = false
    private var ended = false
    private var count = 0
    private var expectedSum: Long? = null
    private var sum = 0L
    private var sumOffset = 0L
    private val duplicates = if (limits.rejectDuplicateNames) DuplicateNames() else null

    /** The entry [next] returned last, or null before the first and after the end. */
    public var current: ArchiveEntry? = null
        private set

    /**
     * Move to the next record and return it, or null at the trailer or the end of the input.
     *
     * @throws ArchiveFormatException for a damaged or truncated header, or a crc sum mismatch.
     * @throws io.github.yuroyami.archivekodec.security.ArchivePathException for an unsafe name, when the limits reject them.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException past [ArchiveLimits.maxEntries].
     */
    @Throws(ArchiveException::class)
    public fun next(): ArchiveEntry? {
        consume(remaining, null)
        skip(padding)
        padding = 0
        current = null
        if (ended) return null
        val pos = input.streamPosition
        if (!input.ensure(1)) {
            if (!started) throw ArchiveFormatException("cpio: missing header", 0)
            ended = true
            return null
        }
        started = true
        input.ensure(6)
        val prefix = input.buffer.copyOfRange(input.position, input.position + minOf(6, input.available))
        val record = when {
            isBinaryCpioMagic(prefix) -> binary(pos, prefix[0] == 0xc7.toByte())
            prefix.size < 6 -> throw ArchiveFormatException("cpio: truncated header magic", pos)
            else -> when (prefix.decodeToString()) {
                "070701", "070702" -> newc(pos, prefix[5] == '2'.code.toByte())
                "070707" -> odc(pos)
                else -> throw ArchiveFormatException("cpio: unrecognized header magic", pos)
            }
        }
        if (record == null) { ended = true; return null }
        current = record
        return record
    }

    /** newc and crc: 110-byte header, name and body each padded to four bytes. */
    private fun newc(pos: Long, crc: Boolean): ArchiveEntry? {
        val h = header(110)
        fun field(i: Int): Long = number(h, 6 + i * 8, 8, 16, pos)
        val mode = field(1)
        val mtime = field(5)
        val size = field(6)
        val nameSize = field(11)
        val checksum = if (crc) field(12) else null
        val name = name(pos + 110, nameSize)
        skip(align4(pos + 110 + nameSize) - (pos + 110 + nameSize))
        return start(name, pos + 110, size, mode, mtime, checksum, pos + 102, align4(size) - size)
    }

    /** odc: 76-byte octal header, no padding. */
    private fun odc(pos: Long): ArchiveEntry? {
        val h = header(76)
        fun field(offset: Int, width: Int = 6) = number(h, offset, width, 8, pos)
        val mode = field(18)
        val mtime = field(48, 11)
        val nameSize = field(59)
        val size = field(65, 11)
        val name = name(pos + 76, nameSize)
        return start(name, pos + 76, size, mode, mtime, null, pos, 0)
    }

    /** Old binary cpio: thirteen 16-bit words, name and body each padded to two bytes. */
    private fun binary(pos: Long, littleEndian: Boolean): ArchiveEntry? {
        val h = header(26)
        fun word(i: Int): Long {
            val a = h[2 * i].toInt() and 0xFF
            val b = h[2 * i + 1].toInt() and 0xFF
            return (if (littleEndian) (b shl 8) or a else (a shl 8) or b).toLong()
        }
        val mode = word(3)
        val mtime = (word(8) shl 16) or word(9)
        val nameSize = word(10)
        val size = (word(11) shl 16) or word(12)
        val name = name(pos + 26, nameSize)
        skip(nameSize and 1)
        return start(name, pos + 26, size, mode, mtime, null, pos, size and 1)
    }

    private fun start(nameBytes: ByteArray, nameStart: Long, size: Long, mode: Long, mtime: Long, checksum: Long?, checksumOffset: Long, pad: Long): ArchiveEntry? {
        if (nameBytes.contentEquals(TRAILER)) {
            if (size != 0L) throw ArchiveFormatException("cpio: trailer has a data body", nameStart, "TRAILER!!!")
            if (checksum != null && checksum != 0L) throw ArchiveFormatException("cpio: byte-sum checksum mismatch (expected $checksum, got 0)", checksumOffset, "TRAILER!!!")
            return null
        }
        limits.checkEntryCount(++count)
        val name = nameDecoder.decodeStoredName(nameBytes, nameStart)
        val isDirectory = (mode and 0xF000L) == 0x4000L
        val entry = ArchiveEntry(name, if (isDirectory) 0L else size, isDirectory) {
            lastModifiedEpochSeconds = mtime
            unixMode = mode.toInt()
        }.withNameBytes(nameBytes)
        if (limits.rejectUnsafeNames && !PathGuard.isRootDirectory(entry)) PathGuard.sanitize(entry.name)
        duplicates?.add(entry)
        // A directory's stored body is skipped and reports no data, as CpioReader reports it.
        remaining = size
        padding = pad
        expectedSum = checksum
        sum = 0
        sumOffset = checksumOffset
        if (isDirectory || (checksum != null && size == 0L)) consume(size, null)
        return entry
    }

    /**
     * Stream the current record's data, or the part not read yet, to [sink]; return the bytes
     * written. [limits] bound the record's size.
     * @throws ArchiveException for a truncated archive, a crc sum mismatch or a sink failure.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun read(sink: ByteSink, limits: ArchiveLimits = this.limits): Long {
        val entry = current ?: throw IllegalStateException("no current entry; call next() first")
        if (entry.isDirectory) return 0
        limits.checkOutputSize(remaining)
        return consume(remaining, sink)
    }

    /** The current record's data as a source, for a decoder or another writer. */
    public fun data(): ByteSource = ByteSource { into, offset, length ->
        val entry = current ?: throw IllegalStateException("no current entry; call next() first")
        if (remaining == 0L || entry.isDirectory) -1 else {
            val n = minOf(remaining, length.toLong()).toInt()
            var copied = 0
            consume(n.toLong()) { b, o, count -> b.copyInto(into, offset + copied, o, o + count); copied += count }
            n
        }
    }

    /** Pass [count] body bytes to [sink], or drop them, summing them for a crc record. */
    private fun consume(count: Long, sink: ByteSink?): Long {
        var left = count
        while (left > 0) {
            if (!input.ensure(1)) throw ArchiveFormatException("cpio: '${current?.name}' is truncated", input.streamPosition, current?.name)
            val n = minOf(left, input.available.toLong()).toInt()
            if (expectedSum != null) for (i in input.position until input.position + n) sum += input.buffer[i].toLong() and 0xFF
            sink?.write(input.buffer, input.position, n)
            input.position += n
            left -= n
            remaining -= n
        }
        val expected = expectedSum
        if (remaining == 0L && expected != null) {
            expectedSum = null
            val actual = sum and 0xFFFFFFFFL
            if (actual != expected) throw ArchiveFormatException("cpio: byte-sum checksum mismatch (expected $expected, got $actual)", sumOffset, current?.name)
        }
        return count
    }

    private fun header(size: Int): ByteArray {
        val h = ByteArray(size)
        input.readFully(h, 0, size, "cpio header")
        return h
    }

    private fun name(offset: Long, length: Long): ByteArray {
        if (length < 1) throw ArchiveFormatException("cpio: empty name field", offset)
        if (length > 64 * 1024) throw ArchiveFormatException("cpio: name field of $length bytes is too long", offset)
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes, 0, bytes.size, "cpio name")
        if (bytes.last().toInt() != 0) throw ArchiveFormatException("cpio: missing name terminator", offset)
        return bytes.copyOf(bytes.indexOf(0))
    }

    private fun skip(count: Long) {
        var left = count
        while (left > 0) {
            if (!input.ensure(1)) throw ArchiveFormatException("cpio: archive is truncated", input.streamPosition)
            val n = minOf(left, input.available.toLong()).toInt()
            input.position += n
            left -= n
        }
    }

    private fun align4(v: Long): Long = (v + 3) and -4L

    private fun number(b: ByteArray, off: Int, len: Int, radix: Int, headerOffset: Long): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt() and 255
            val d = when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> -1
            }
            if (d < 0 || d >= radix) throw ArchiveFormatException("cpio: invalid base-$radix digit", headerOffset + i)
            v = v * radix + d
        }
        return v
    }

    private companion object {
        val TRAILER = "TRAILER!!!".encodeToByteArray()
    }
}
