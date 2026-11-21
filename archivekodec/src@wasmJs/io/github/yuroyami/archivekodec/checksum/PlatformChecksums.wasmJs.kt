package io.github.yuroyami.archivekodec.checksum

// No platform checksum is used here; the pure code runs.

internal actual fun platformCrc32(): Checksum? = null

internal actual fun platformAdler32(): Checksum? = null
