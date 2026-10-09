package io.github.yuroyami.archivekodec.js

import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsExportsTest {
    private val hello = "hello".encodeToByteArray()

    @Test
    fun codecsRoundTrip() {
        assertContentEquals(hello, gunzip(gzip(hello)))
        assertContentEquals(hello, inflate(deflate(hello, 0)))
        assertContentEquals(hello, unzlib(zlib(hello, 9)))
        assertContentEquals(hello, unlz4(lz4(hello)))
        assertContentEquals(hello, decompressAny(compress("zlib", hello)))
        assertEquals("gzip", sniff(gzip(hello)))
        assertTrue("deflate" in codecs())
    }

    @Test
    fun maxBytesCapsOutput() {
        val packed = gzip(ByteArray(5000))
        assertFailsWith<DecompressionBombException> { gunzip(packed, 4999.0) }
        assertEquals(5000, gunzip(packed, 5000.0).size)
        assertFailsWith<IllegalArgumentException> { gunzip(packed, -1.0) }
        assertFailsWith<IllegalArgumentException> { gunzip(packed, Double.NaN) }
    }

    @Test
    fun zipThenOpen() {
        val archive = open(zip(arrayOf(ZipMember("a.txt", hello), ZipMember("a.txt", ByteArray(3)))))
        assertEquals("zip", archive.format)
        val entries = archive.entries
        assertEquals(listOf("a.txt", "a.txt"), entries.map { it.name })
        assertEquals(5.0, entries[0].size)
        assertEquals(1, entries[1].index)
        assertContentEquals(hello, archive.read("a.txt"))
        assertContentEquals(ByteArray(3), archive.readAt(1))
        assertFailsWith<IllegalArgumentException> { archive.read("b.txt") }
        assertFailsWith<IllegalArgumentException> { archive.readAt(2) }
        archive.close()
    }
}
