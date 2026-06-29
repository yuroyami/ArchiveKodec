package io.github.yuroyami.kitearchive.archive

import io.github.yuroyami.kitearchive.archive.tar.TarReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TarReaderTest {

    @Test
    fun readsSingleUstarFile() {
        val content = "Hello, KiteArchive!".encodeToByteArray()
        val tar = buildSingleFileUstar("hello.txt", content)

        val reader = TarReader(ByteArrayRandomAccessSource(tar))
        val entries = reader.entries()

        assertEquals(1, entries.size)
        assertEquals("hello.txt", entries[0].name)
        assertEquals(content.size.toLong(), entries[0].size)
        assertFalse(entries[0].isDirectory)
        assertContentEquals(content, reader.read(entries[0]))
    }

    /** Hand-build a minimal single-file ustar archive (one header + data + two zero blocks). */
    private fun buildSingleFileUstar(name: String, content: ByteArray): ByteArray {
        val h = ByteArray(512)

        fun put(s: String, off: Int) {
            val b = s.encodeToByteArray()
            b.copyInto(h, off, 0, b.size)
        }
        fun putOctal(v: Long, off: Int, len: Int) {
            val s = v.toString(8).padStart(len - 1, '0')
            s.encodeToByteArray().copyInto(h, off)
            h[off + len - 1] = 0
        }

        put(name, 0)
        put("0000644", 100); h[107] = 0          // mode
        putOctal(0, 108, 8)                       // uid
        putOctal(0, 116, 8)                       // gid
        putOctal(content.size.toLong(), 124, 12)  // size
        putOctal(0, 136, 12)                      // mtime
        h[156] = '0'.code.toByte()                // typeflag = normal file
        put("ustar", 257); h[262] = 0             // magic + NUL
        put("00", 263)                            // version

        // checksum: field treated as spaces, sum all bytes, write "%06o\0 ".
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0
        for (b in h) sum += b.toInt() and 0xFF
        val chk = sum.toString(8).padStart(6, '0')
        chk.encodeToByteArray().copyInto(h, 148)
        h[154] = 0
        h[155] = ' '.code.toByte()

        val padded = ((content.size + 511) / 512) * 512
        val body = ByteArray(padded)
        content.copyInto(body, 0)
        val trailer = ByteArray(1024) // two zero blocks = EOF
        return h + body + trailer
    }
}
