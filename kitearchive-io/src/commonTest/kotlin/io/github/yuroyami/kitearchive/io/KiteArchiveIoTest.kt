package io.github.yuroyami.kitearchive.io

import io.github.yuroyami.kitearchive.codec.Codecs
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertContentEquals

class KiteArchiveIoTest {

    @Test
    fun drainsRawSource() {
        val data = "stream me through kotlinx-io".encodeToByteArray()
        val src: RawSource = Buffer().apply { write(data) }
        assertContentEquals(data, src.readAllBytes())
    }

    @Test
    fun codecDecompressOverSource() {
        val data = ("repeat me ").repeat(50).encodeToByteArray()
        val gz = Codecs.GZIP.compress(data)
        val src: RawSource = Buffer().apply { write(gz) }
        assertContentEquals(data, Codecs.GZIP.decompress(src))
    }
}
