package io.github.yuroyami.archivekodec.crypto

import java.security.SecureRandom

private val generator = SecureRandom()

internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { generator.nextBytes(it) }
