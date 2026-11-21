package io.github.yuroyami.archivekodec.checksum

/**
 * The platform's own CRC-32, where it has one faster than the table code, or null. On the JVM
 * and Android this is `java.util.zip.CRC32`, which HotSpot and ART run as an intrinsic.
 */
internal expect fun platformCrc32(): Checksum?

/** The platform's own Adler-32, where it has one faster than the pure code, or null. */
internal expect fun platformAdler32(): Checksum?
