package io.github.yuroyami.kitearchive

import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.kitearchive.archive.zip.ZipReader
import io.github.yuroyami.kitearchive.archive.zip.ZipWriter
import io.github.yuroyami.kitearchive.codec.CodecId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Interop both directions against the JDK's ZIP, plus the ZIP64 (entry-count) path. */
class ZipInteropTest {

    private val dataA = ByteArray(40) { 'A'.code.toByte() }
    private val dataB = "the quick brown fox jumps over the lazy dog ".repeat(20).encodeToByteArray()

    @Test
    fun ourZipReadByJdk() {
        val bytes = ZipWriter.write(
            listOf(
                ZipWriter.FileSpec("a.txt", dataA, CodecId.STORE),
                ZipWriter.FileSpec("b.txt", dataB, CodecId.DEFLATE),
            ),
        )
        val got = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                got[e.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        assertContentEquals(dataA, got["a.txt"])
        assertContentEquals(dataB, got["b.txt"])
    }

    @Test
    fun jdkZipReadByUs() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("x.txt")); zos.write(dataB); zos.closeEntry()
            zos.putNextEntry(ZipEntry("y.txt")); zos.write(dataA); zos.closeEntry()
        }
        val reader = ZipReader(ByteArrayRandomAccessSource(bos.toByteArray()))
        val byName = reader.entries().associateBy { it.name }
        assertContentEquals(dataB, reader.read(byName.getValue("x.txt")))
        assertContentEquals(dataA, reader.read(byName.getValue("y.txt")))
    }

    @Test
    fun zip64EntryCountRoundTrip() {
        // > 65535 entries forces the ZIP64 end-of-central-directory on write + read.
        val files = (0 until 70_000).map {
            ZipWriter.FileSpec("f$it", byteArrayOf((it and 0xFF).toByte()), CodecId.STORE)
        }
        val bytes = ZipWriter.write(files)
        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        val entries = reader.entries()
        assertEquals(70_000, entries.size)
        assertContentEquals(byteArrayOf(0), reader.read(entries.first { it.name == "f0" }))
        assertContentEquals(byteArrayOf((69_999 and 0xFF).toByte()), reader.read(entries.first { it.name == "f69999" }))
    }
}
