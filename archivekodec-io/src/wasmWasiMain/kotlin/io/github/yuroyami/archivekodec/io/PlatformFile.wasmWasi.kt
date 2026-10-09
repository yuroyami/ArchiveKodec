package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveUnsupportedException

internal actual fun openPlatformFile(path: String): PlatformFile =
    throw ArchiveUnsupportedException("FileRandomAccessSource is not available on WASI; read the file into a ByteArray instead")
