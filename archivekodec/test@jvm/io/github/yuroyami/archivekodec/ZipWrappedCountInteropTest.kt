package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.util.readU32Le
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipWrappedCountInteropTest {
    @Test
    fun exactClassicMaximumAndWrappedCountsAgreeWithJdk() {
        for (count in listOf(65535, 65536, 70000)) {
            val generated = ZipWriter.write((0 until count).map { ZipWriter.FileSpec("f$it", byteArrayOf((it and 255).toByte()), ZipMethod.STORED) })
            val end = generated.copyOfRange(generated.size - 22, generated.size)
            val cdEnd = (readU32Le(end, 12) + readU32Le(end, 16)).toInt()
            for (offset in listOf(8, 10)) {
                end[offset] = count.toByte(); end[offset + 1] = (count ushr 8).toByte()
            }
            val bytes = generated.copyOf(cdEnd) + end
            if (count > 65535) {
                val corruptZip64 = generated.copyOf()
                for (field in listOf(24, 32)) for (byte in 0 until 8) {
                    corruptZip64[cdEnd + field + byte] = ((count.toLong() and 65535) ushr (byte * 8)).toByte()
                }
                assertFailsWith<ArchiveFormatException> {
                    ZipReader(ByteArrayRandomAccessSource(corruptZip64)).entries()
                }
            }
            val path = Files.createTempFile("archivekodec-wrapped-", ".zip")
            try {
                Files.write(path, bytes)
                ZipFile(path.toFile()).use { jdk ->
                    val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
                    val entries = reader.entries()
                    assertEquals(count, jdk.size())
                    assertEquals(jdk.size(), entries.size)
                    assertEquals("f${count - 1}", entries.last().name)
                    jdk.getInputStream(jdk.getEntry(entries.last().name)).use {
                        assertContentEquals(it.readAllBytes(), reader.read(entries.last()))
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }
}
