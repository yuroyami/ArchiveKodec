package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.le16
import io.github.yuroyami.archivekodec.archive.zip.le32
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

// Foreign archives may contain names that our writer refuses to produce.
// Build their framing independently so reader tests retain those raw names.
internal fun zipForeignStoredName(name: String, data: ByteArray = byteArrayOf()): ByteArray =
    zipForeignStoredNames(listOf(name), data)

internal fun zipForeignStoredNames(names: List<String>, data: ByteArray = byteArrayOf()): ByteArray {
    val crc = Crc32.of(data)
    val out = ByteArrayBuilder(128)
    val records = names.map { name ->
        val encoded = name.encodeToByteArray()
        val offset = out.size
        out.le32(ZipFormat.LOC)
        out.le16(20); out.le16(0x0800); out.le16(0)
        out.le16(0); out.le16(0x0021)
        out.le32(crc); out.le32(data.size.toLong()); out.le32(data.size.toLong())
        out.le16(encoded.size); out.le16(0)
        out.append(encoded); out.append(data)
        encoded to offset
    }
    val central = out.size
    for ((encoded, offset) in records) {
        out.le32(ZipFormat.CEN)
        out.le16(45); out.le16(20); out.le16(0x0800); out.le16(0)
        out.le16(0); out.le16(0x0021)
        out.le32(crc); out.le32(data.size.toLong()); out.le32(data.size.toLong())
        out.le16(encoded.size); out.le16(0); out.le16(0)
        out.le16(0); out.le16(0); out.le32(0); out.le32(offset.toLong())
        out.append(encoded)
    }
    val centralSize = out.size - central
    out.le32(ZipFormat.EOCD)
    out.le16(0); out.le16(0); out.le16(records.size); out.le16(records.size)
    out.le32(centralSize.toLong()); out.le32(central.toLong()); out.le16(0)
    return out.toByteArray()
}
