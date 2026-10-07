package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId

internal fun zipMetadataBase(): ByteArray = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", "ABC".encodeToByteArray(), CodecId.STORE)))

internal fun zipWithComment(bytes: ByteArray, comment: ByteArray): ByteArray {
    val result = bytes + comment
    putLe(result, bytes.size - 2, comment.size.toLong(), 2)
    return result
}

// Mask bits select the conditional fields: output size, compressed size, offset, disk.
internal fun zip64Extra(mask: Int, output: Long = 3, compressed: Long = 3, offset: Long = 0, disk: Long = 0): ByteArray {
    val length = (if (mask and 1 != 0) 8 else 0) + (if (mask and 2 != 0) 8 else 0) +
        (if (mask and 4 != 0) 8 else 0) + (if (mask and 8 != 0) 4 else 0)
    val result = ByteArray(4 + length)
    putLe(result, 0, 1, 2); putLe(result, 2, length.toLong(), 2)
    var at = 4
    for ((bit, value) in listOf(1 to output, 2 to compressed, 4 to offset, 8 to disk)) {
        if (mask and bit != 0) {
            val width = if (bit == 8) 4 else 8
            putLe(result, at, value, width)
            at += width
        }
    }
    return result
}

internal fun zipEntryMetadata(mask: Int, extra: ByteArray = zip64Extra(mask)): ByteArray {
    val original = zipMetadataBase()
    val central = ZipFormat.u32(original, original.size - 6).toInt()
    val records = original.copyOfRange(central, original.size - 22) + extra
    putLe(records, 6, 45, 2)
    if (mask and 1 != 0) putLe(records, 24, 0xFFFFFFFF, 4)
    if (mask and 2 != 0) putLe(records, 20, 0xFFFFFFFF, 4)
    if (mask and 4 != 0) putLe(records, 42, 0xFFFFFFFF, 4)
    if (mask and 8 != 0) putLe(records, 34, 0xFFFF, 2)
    putLe(records, 30, extra.size.toLong(), 2)
    val end = original.copyOfRange(original.size - 22, original.size)
    putLe(end, 12, records.size.toLong(), 4)
    return original.copyOf(central) + records + end
}
