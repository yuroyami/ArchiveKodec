package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * ZIP writer for STORE and DEFLATE entries, with ZIP64 where it is needed. The whole
 * archive is built in memory and returned as a `ByteArray`; there is no streaming
 * form. ZIP64 records are emitted only when the entry count passes 65535 or a size or
 * offset overflows 32 bits, with per-entry ZIP64 extra fields for oversized entries,
 * so ordinary archives stay classic and small.
 *
 * This is the only writer in the library, and it is not reachable from the
 * `ArchiveKodec` object.
 */
object ZipWriter {

    /** One file to add: its archive [name], raw [data], and compression [codec]. */
    data class FileSpec(
        val name: String,
        val data: ByteArray,
        val codec: CodecId = CodecId.DEFLATE,
    )

    private class Rec(
        val nameBytes: ByteArray,
        val method: Int,
        val crc: Long,
        val comp: Long,
        val uncomp: Long,
        val offset: Long,
        val zip64: Boolean,
    )

    private const val MASK32 = 0xFFFFFFFFL

    fun write(files: List<FileSpec>): ByteArray {
        val out = ByteArrayBuilder(1024)
        val recs = ArrayList<Rec>(files.size)

        for (f in files) {
            val offset = out.size.toLong()
            val nameBytes = f.name.encodeToByteArray()
            val crc = Crc32.of(f.data)
            val method = ZipFormat.methodCode(f.codec)
            val comp = when (f.codec) {
                CodecId.STORE -> f.data
                CodecId.DEFLATE -> Deflate.encode(f.data)
                else -> throw IllegalArgumentException("ZipWriter supports STORE/DEFLATE, not ${f.codec}")
            }
            val needs64 = offset > MASK32 || comp.size.toLong() > MASK32 || f.data.size.toLong() > MASK32

            out.le32(ZipFormat.LOC)
            out.le16(if (needs64) 45 else 20) // version needed
            out.le16(0)                        // flags
            out.le16(method)
            out.le16(0); out.le16(0)           // mod time, date
            out.le32(crc)
            if (needs64) { out.le32(MASK32); out.le32(MASK32) } else { out.le32(comp.size.toLong()); out.le32(f.data.size.toLong()) }
            out.le16(nameBytes.size)
            out.le16(if (needs64) 20 else 0)   // extra len (zip64 = 4 hdr + 16 data)
            out.append(nameBytes)
            if (needs64) {
                out.le16(0x0001); out.le16(16)
                out.le64(f.data.size.toLong()); out.le64(comp.size.toLong())
            }
            out.append(comp)

            recs.add(Rec(nameBytes, method, crc, comp.size.toLong(), f.data.size.toLong(), offset, needs64))
        }

        val cdStart = out.size.toLong()
        for (r in recs) {
            out.le32(ZipFormat.CEN)
            out.le16(45)                       // version made by
            out.le16(if (r.zip64) 45 else 20)  // version needed
            out.le16(0)                        // flags
            out.le16(r.method)
            out.le16(0); out.le16(0)           // time, date
            out.le32(r.crc)
            if (r.zip64) { out.le32(MASK32); out.le32(MASK32) } else { out.le32(r.comp); out.le32(r.uncomp) }
            out.le16(r.nameBytes.size)
            out.le16(if (r.zip64) 28 else 0)   // extra len (4 hdr + 24 data: uncomp, comp, offset)
            out.le16(0)                        // comment len
            out.le16(0)                        // disk start
            out.le16(0)                        // internal attrs
            out.le32(0)                        // external attrs
            if (r.zip64) out.le32(MASK32) else out.le32(r.offset)
            out.append(r.nameBytes)
            if (r.zip64) {
                out.le16(0x0001); out.le16(24)
                out.le64(r.uncomp); out.le64(r.comp); out.le64(r.offset)
            }
        }
        val cdSize = out.size.toLong() - cdStart
        val count = recs.size

        val needEocd64 = count > 0xFFFF || cdStart > MASK32 || cdSize > MASK32
        if (needEocd64) {
            val z64Pos = out.size.toLong()
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
        out.le32(minOf(cdSize, MASK32)); out.le32(minOf(cdStart, MASK32))
        out.le16(0)                            // comment len
        return out.toByteArray()
    }
}
