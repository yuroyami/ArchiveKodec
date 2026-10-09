package io.github.yuroyami.archivekodec.archive.zip

/** ZIP signatures. Codec method bindings belong to CodecRegistry. */
internal object ZipFormat {
    const val LOC = 0x04034b50L          // local file header
    const val CEN = 0x02014b50L          // central directory header
    const val CEN_SIGNATURE = 0x05054b50L // optional central directory digital signature
    const val EOCD = 0x06054b50L         // end of central directory
    const val Z64_EOCD = 0x06064b50L     // zip64 end of central directory
    const val Z64_LOC = 0x07064b50L      // zip64 EOCD locator

}
