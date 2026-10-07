package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId

internal class ZipExtentPiece(val local: ByteArray, val directory: ByteArray)

internal fun zipExtentPiece(
    name: String,
    data: ByteArray,
    codec: CodecId = CodecId.STORE,
    compressed: ByteArray? = null,
    localExtra: ByteArray = byteArrayOf(),
): ZipExtentPiece {
    val original = ZipWriter.write(listOf(ZipWriter.FileSpec(name, data, codec)))
    val central = ZipFormat.u32(original, original.size - 6).toInt()
    val headerLength = 30 + name.encodeToByteArray().size
    val header = original.copyOf(headerLength)
    val payload = compressed ?: original.copyOfRange(headerLength, central)
    val directory = original.copyOfRange(central, original.size - 22)
    putLe(header, 18, payload.size.toLong(), 4)
    putLe(header, 28, localExtra.size.toLong(), 2)
    putLe(directory, 20, payload.size.toLong(), 4)
    return ZipExtentPiece(header + localExtra + payload, directory)
}

internal fun zipExtentArchive(prefix: ByteArray, records: List<ByteArray>): ByteArray {
    val directory = records.fold(byteArrayOf()) { bytes, record -> bytes + record }
    val end = ByteArray(22)
    putLe(end, 0, ZipFormat.EOCD, 4)
    putLe(end, 8, records.size.toLong(), 2); putLe(end, 10, records.size.toLong(), 2)
    putLe(end, 12, directory.size.toLong(), 4); putLe(end, 16, prefix.size.toLong(), 4)
    return prefix + directory + end
}

/** Small, CRC-valid members sharing an inner header and payload. */
internal fun zipOverlappingPair(codec: CodecId, inExtra: Boolean = false, reversed: Boolean = false): ByteArray {
    val payload = "SHARED-PAYLOAD".encodeToByteArray()
    val inner = zipExtentPiece("b", payload, codec)
    val outer: ZipExtentPiece
    val innerOffset: Int
    when {
        inExtra -> {
            val extra = ByteArray(4) + inner.local
            putLe(extra, 0, 0xCAFE, 2); putLe(extra, 2, inner.local.size.toLong(), 2)
            outer = zipExtentPiece("a", byteArrayOf(), localExtra = extra)
            innerOffset = 35
        }
        codec == CodecId.STORE -> {
            outer = zipExtentPiece("a", inner.local)
            innerOffset = 31
        }
        else -> {
            // A non-final stored DEFLATE block quotes the inner local header;
            // the inner's final compressed stream supplies the shared payload.
            val quote = ByteArray(5)
            putLe(quote, 1, 31, 2); putLe(quote, 3, 31L xor 0xFFFF, 2)
            outer = zipExtentPiece("a", inner.local.copyOf(31) + payload, CodecId.DEFLATE, quote + inner.local)
            innerOffset = 36
        }
    }
    putLe(inner.directory, 42, innerOffset.toLong(), 4)
    val records = if (reversed) listOf(inner.directory, outer.directory) else listOf(outer.directory, inner.directory)
    return zipExtentArchive(outer.local, records)
}

/** Original 192-byte STORE reproducer from #65. */
internal fun zipOverlapIssueFixture(): ByteArray {
    val hex = "504b03041400000000000000210021f8f5ad2d0000002d0000000100000061504b03041400000000000000210024e8d09f0e0000000e00000001000000625348415245442d5041594c4f4144504b010214001400000000000000210021f8f5ad2d0000002d00000001000000000000000000000000000000000061504b010214001400000000000000210024e8d09f0e0000000e00000001000000000000000000000000001f00000062504b050600000000020002005e0000004c0000000000"
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
