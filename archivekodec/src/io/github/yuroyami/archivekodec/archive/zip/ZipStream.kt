package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.FailStop
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.DeflateEncoderSession
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.le16
import io.github.yuroyami.archivekodec.util.le32
import io.github.yuroyami.archivekodec.util.le64
import kotlin.jvm.JvmOverloads

/**
 * Writes a ZIP archive to a [ByteSink] one entry at a time, so neither the archive nor an
 * entry ever sits in memory whole, and the archive may pass 4 GiB. Use it instead of
 * [ZipWriter] when the data is large or arrives as a stream; both write the same names,
 * times, modes, comments and attributes for the same input, under the same name rules.
 *
 * Sizes and the CRC-32 are known only once an entry's data has passed, so each nonempty file
 * has general purpose bit 3 set: its local header holds zeros, and a data descriptor with the
 * signature `PK\x07\x08` follows the data. The central directory holds the real values. An
 * empty file and a directory have no descriptor, as [ZipWriter] writes them.
 *
 * ZIP64 is written where it is needed, as `java.util.zip` and Go's `archive/zip` write it: a
 * data descriptor has 8-byte sizes when a size exceeds 0xFFFFFFFF; a size or local header
 * offset of 0xFFFFFFFF or more goes in a ZIP64 extra field in the central directory, never in
 * the local header, and the entry then needs version 4.5; the ZIP64 end records are written
 * when there are more than 65535 entries, or the central directory's size or offset reaches
 * 0xFFFFFFFF.
 *
 * The writer keeps one small record per entry (its name, comment, sizes and offset) until
 * [finish] writes the central directory; ZIP puts that directory at the end, so this memory
 * grows with the entry count, not with the data. Encryption is not offered; [ZipWriter]
 * writes encrypted archives in memory. A [ZipMethod.STORED] file with a descriptor reads
 * through the central directory, as [ZipReader], `unzip` and `java.util.zip.ZipFile` read,
 * but not with forward-only readers such as `java.util.zip.ZipInputStream`, which cannot find
 * where stored data ends; use [ZipMethod.DEFLATED] for those.
 *
 * Arguments are checked before anything is written, so a rejected put leaves the archive as
 * it was, and so does a source that fails on its first read. A failure after a call has
 * written anything to the sink, from the source, the sink or the encoder, leaves the archive
 * incomplete, so the writer stops: every later [putFile], [putDirectory] and [finish] call
 * throws that same failure again and writes nothing. Not thread-safe.
 */
public class ZipStreamWriter(private val sink: ByteSink) {
    private class Record(
        val name: String,
        val nameBytes: ByteArray,
        val flags: Int,
        val method: Int,
        val dosDateTime: Int,
        val crc: Long,
        val compressed: Long,
        val size: Long,
        val offset: Long,
        val extra: ByteArray,
        val comment: ByteArray?,
        val unixMode: Int?,
    )

    private val records = ArrayList<Record>()
    private val buffer = ByteArray(64 * 1024)
    private var position = 0L
    private var finished = false
    /** Keeps the failure that stopped the writer after output was written; later calls rethrow it. */
    private val failure = FailStop()

    private fun checkUsable() {
        failure.check()
        check(!finished) { "the ZIP stream is finished" }
    }

    /**
     * Write a file whose data is read from [data] until it ends. A [ZipMethod.DEFLATED] entry
     * is compressed as it is read, in memory bounded by the encoder's chunk of about 1 MiB.
     *
     * @param name exact archive spelling, under the rules [ZipWriter.write] states; a name that
     *   ends with `/` is a directory, which [putDirectory] writes.
     * @param method [ZipMethod.DEFLATED] (the default) or [ZipMethod.STORED].
     * @param lastModifiedEpochSeconds modification time in Unix epoch seconds, written as UTC,
     *   as [ZipWriter.FileSpec.lastModifiedEpochSeconds] says; null writes 1980-01-01.
     * @param unixMode Unix `st_mode` in `0..0xFFFF`, such as `0x81A4` (`0o100644`); null writes no Unix attributes.
     * @param comment entry comment, written as UTF-8 in the central directory; null writes none.
     * @param deflateOptions DEFLATE settings for a deflated entry; null uses [DeflateOptions.DEFAULT].
     * @throws ArchiveFormatException if [name] breaks the rules [ZipWriter.write] states.
     * @throws IllegalArgumentException for a name ending with `/`, a [unixMode] over 16 bits, a
     *   [comment] that is not valid Unicode or whose UTF-8 form exceeds 65535 bytes, or
     *   [deflateOptions] with a preset dictionary.
     * @throws IllegalStateException after [finish].
     * @throws ArchiveException for a failure of [data] or the sink, or the failure that stopped
     *   the writer; see the class description.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun putFile(
        name: String,
        data: ByteSource,
        method: ZipMethod = ZipMethod.DEFLATED,
        lastModifiedEpochSeconds: Long? = null,
        unixMode: Int? = null,
        comment: String? = null,
        deflateOptions: DeflateOptions? = null,
    ) {
        checkUsable()
        require(!name.endsWith('/')) { "ZIP: '$name' names a directory; use putDirectory" }
        require(deflateOptions?.dictionary == null) { "ZIP: DEFLATE entries cannot use a preset dictionary" }
        val options = deflateOptions ?: DeflateOptions.DEFAULT
        val prepared = prepare(name, lastModifiedEpochSeconds, unixMode, comment)
        val offset = position
        val first = readSome(data, name)
        failure.guarded { writeFile(prepared, data, first, method, options, offset) }
    }

    private fun writeFile(prepared: Prepared, data: ByteSource, first: Int, method: ZipMethod, options: DeflateOptions, offset: Long) {
        val name = prepared.name
        var n = first
        if (n < 0) {
            // Empty: the sizes are known, so the entry needs no descriptor, as ZipWriter writes it.
            val compressed = when (method) {
                ZipMethod.STORED -> ByteArray(0)
                ZipMethod.DEFLATED -> Deflate.encode(ByteArray(0), options)
            }
            localHeader(prepared, UTF8_FLAG, method.code, compressed.size.toLong())
            emit(compressed, 0, compressed.size)
            records += prepared.record(UTF8_FLAG, method.code, 0, compressed.size.toLong(), 0, offset)
            return
        }
        val flags = UTF8_FLAG or DESCRIPTOR_FLAG
        localHeader(prepared, flags, method.code, 0)
        val dataStart = position
        val crc = Crc32()
        var size = 0L
        val encoder = when (method) {
            ZipMethod.STORED -> null
            ZipMethod.DEFLATED -> DeflateEncoderSession(options, ByteSink { b, o, l -> emit(b, o, l) })
        }
        while (n >= 0) {
            crc.update(buffer, 0, n)
            size += n
            if (encoder != null) encoder.write(buffer, 0, n) else emit(buffer, 0, n)
            n = readSome(data, name)
        }
        encoder?.finish()
        val compressed = position - dataStart
        val value = crc.value()
        val descriptor = ByteArrayBuilder(24)
        descriptor.le32(ZipFormat.DATA_DESCRIPTOR)
        descriptor.le32(value)
        if (compressed > MAX_32 || size > MAX_32) {
            descriptor.le64(compressed); descriptor.le64(size)
        } else {
            descriptor.le32(compressed); descriptor.le32(size)
        }
        val bytes = descriptor.toByteArray()
        emit(bytes, 0, bytes.size)
        records += prepared.record(flags, method.code, value, compressed, size, offset)
    }

    /**
     * Write a directory entry; [name] gains a trailing slash if it has none. It is stored with
     * no data and no descriptor.
     *
     * @param lastModifiedEpochSeconds modification time in Unix epoch seconds; null writes 1980-01-01.
     * @param unixMode Unix `st_mode` in `0..0xFFFF`, such as `0x41ED` (`0o40755`); null writes no Unix attributes.
     * @param comment entry comment, written as UTF-8 in the central directory; null writes none.
     * @throws ArchiveFormatException if the name breaks the rules [ZipWriter.write] states.
     * @throws IllegalArgumentException for a [unixMode] over 16 bits, or a [comment] that is not
     *   valid Unicode or whose UTF-8 form exceeds 65535 bytes.
     * @throws IllegalStateException after [finish].
     * @throws ArchiveException for a failure of the sink, or the failure that stopped the writer.
     */
    @Throws(ArchiveException::class)
    @JvmOverloads
    public fun putDirectory(name: String, lastModifiedEpochSeconds: Long? = null, unixMode: Int? = null, comment: String? = null) {
        checkUsable()
        val prepared = prepare(if (name.endsWith('/')) name else "$name/", lastModifiedEpochSeconds, unixMode, comment)
        val offset = position
        failure.guarded { localHeader(prepared, UTF8_FLAG, ZipMethod.STORED.code, 0) }
        records += prepared.record(UTF8_FLAG, ZipMethod.STORED.code, 0, 0, 0, offset)
    }

    /**
     * Write the central directory and the end records, which end the archive. Later calls do
     * nothing, unless this one failed: then they throw its failure again.
     * @throws ArchiveException for a failure of the sink, or the failure that stopped the writer.
     */
    @Throws(ArchiveException::class)
    public fun finish() {
        failure.check()
        if (finished) return
        failure.guarded { end(ByteArray(0)) }
    }

    /**
     * Write the central directory and the end records with an archive [comment], as UTF-8.
     * Later calls do nothing, unless this one failed after writing: then they throw its failure again.
     * @throws IllegalArgumentException if [comment] is not valid Unicode or its UTF-8 form exceeds
     *   65535 bytes; nothing is written, and the writer stays usable.
     * @throws ArchiveException for a failure of the sink, or the failure that stopped the writer.
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    public fun finish(comment: String) {
        failure.check()
        if (finished) return
        val bytes = ZipWriter.encodeComment(comment, "archive comment")
        failure.guarded { end(bytes) }
    }

    private fun end(archiveComment: ByteArray) {
        val cdStart = position
        val out = ByteArrayBuilder(64 * 1024)
        for (r in records) {
            val zip64 = ByteArrayBuilder(28)
            if (r.size >= MAX_32) zip64.le64(r.size)
            if (r.compressed >= MAX_32) zip64.le64(r.compressed)
            if (r.offset >= MAX_32) zip64.le64(r.offset)
            val extra = if (zip64.size == 0) r.extra else ByteArrayBuilder(r.extra.size + 4 + zip64.size).apply {
                append(r.extra)
                le16(0x0001); le16(zip64.size)
                append(zip64.toByteArray())
            }.toByteArray()
            val versionNeeded = if (zip64.size == 0) 20 else 45
            val comment = r.comment ?: ByteArray(0)
            out.le32(ZipFormat.CEN)
            out.le16(ZipWriter.versionMadeBy(versionNeeded, r.unixMode))
            out.le16(versionNeeded)
            out.le16(r.flags)
            out.le16(r.method)
            out.le16(r.dosDateTime and 0xFFFF); out.le16(r.dosDateTime ushr 16)
            out.le32(r.crc)
            out.le32(minOf(r.compressed, MAX_32)); out.le32(minOf(r.size, MAX_32))
            out.le16(r.nameBytes.size)
            out.le16(extra.size)
            out.le16(comment.size)
            out.le16(0)                        // disk start
            out.le16(0)                        // internal attrs
            out.le32(ZipWriter.externalAttributes(r.name, r.unixMode))
            out.le32(minOf(r.offset, MAX_32))
            out.append(r.nameBytes)
            out.append(extra)
            out.append(comment)
            if (out.size >= 60 * 1024) flush(out)
        }
        val cdSize = position + out.size - cdStart
        val count = records.size
        if (count > 0xFFFF || cdSize >= MAX_32 || cdStart >= MAX_32) {
            val z64Pos = position + out.size
            out.le32(ZipFormat.Z64_EOCD)
            out.le64(44L)                      // size of remaining record
            out.le16(45); out.le16(45)
            out.le32(0); out.le32(0)           // this disk, cd start disk
            out.le64(count.toLong()); out.le64(count.toLong())
            out.le64(cdSize); out.le64(cdStart)
            out.le32(ZipFormat.Z64_LOC)
            out.le32(0)                        // disk of zip64 eocd
            out.le64(z64Pos)
            out.le32(1)                        // total disks
        }
        out.le32(ZipFormat.EOCD)
        out.le16(0); out.le16(0)
        out.le16(minOf(count, 0xFFFF)); out.le16(minOf(count, 0xFFFF))
        out.le32(minOf(cdSize, MAX_32)); out.le32(minOf(cdStart, MAX_32))
        out.le16(archiveComment.size)
        out.append(archiveComment)
        flush(out)
        finished = true
        records.clear()
    }

    /** An entry's checked name, time, extra field and comment, before anything is written. */
    private class Prepared(
        val name: String,
        val nameBytes: ByteArray,
        val dosDateTime: Int,
        val extra: ByteArray,
        val comment: ByteArray?,
        val unixMode: Int?,
    ) {
        fun record(flags: Int, method: Int, crc: Long, compressed: Long, size: Long, offset: Long) =
            Record(name, nameBytes, flags, method, dosDateTime, crc, compressed, size, offset, extra, comment, unixMode)
    }

    private fun prepare(name: String, lastModifiedEpochSeconds: Long?, unixMode: Int?, comment: String?): Prepared {
        require(unixMode == null || unixMode in 0..0xFFFF) { "ZIP: unixMode $unixMode does not fit 16 bits" }
        val commentBytes = comment?.let { ZipWriter.encodeComment(it, "entry comment") }
        val nameBytes = ZipWriter.encodeName(name)
        return Prepared(
            name, nameBytes, ZipWriter.dosDateTime(lastModifiedEpochSeconds),
            ZipWriter.extendedTimestamp(lastModifiedEpochSeconds), commentBytes, unixMode,
        )
    }

    private fun localHeader(p: Prepared, flags: Int, method: Int, compressed: Long) {
        val out = ByteArrayBuilder(30 + p.nameBytes.size + p.extra.size)
        out.le32(ZipFormat.LOC)
        out.le16(20)                           // version needed
        out.le16(flags)
        out.le16(method)
        out.le16(p.dosDateTime and 0xFFFF); out.le16(p.dosDateTime ushr 16)
        out.le32(0)                            // CRC-32: zero for an empty entry, else in the descriptor
        out.le32(compressed); out.le32(0)
        out.le16(p.nameBytes.size)
        out.le16(p.extra.size)
        out.append(p.nameBytes)
        out.append(p.extra)
        flush(out)
    }

    /** Read the next piece of [data] into [buffer]: its length, or -1 at the end. */
    private fun readSome(data: ByteSource, name: String): Int {
        val n = data.read(buffer, 0, buffer.size)
        if (n < 0) return -1
        FailStop.checkRead(n, buffer.size, "ZIP", name)
        return n
    }

    private fun flush(out: ByteArrayBuilder) {
        val bytes = out.takeByteArray()
        emit(bytes, 0, bytes.size)
    }

    private fun emit(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        sink.write(bytes, offset, length)
        position += length
    }

    private companion object {
        const val UTF8_FLAG = 0x0800
        const val DESCRIPTOR_FLAG = 0x0008
        const val MAX_32 = 0xFFFFFFFFL
    }
}
