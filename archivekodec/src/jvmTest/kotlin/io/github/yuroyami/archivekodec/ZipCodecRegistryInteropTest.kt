package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.CodecRegistry
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipCodecRegistryInteropTest {
    @Test fun jdkInflaterReplacementReadsForeignZipIncludingDataDescriptors() {
        val payloads = listOf(byteArrayOf(), "foreign zip data".repeat(1000).encodeToByteArray(),
            ByteArray(65536) { ((it * 31) xor (it ushr 5)).toByte() })
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { writer ->
            for ((index, payload) in payloads.withIndex()) {
                writer.putNextEntry(ZipEntry("$index.bin"))
                writer.write(payload)
                writer.closeEntry()
            }
        }
        var calls = 0
        val jdk = object : Codec {
            override val id = CodecId("deflate")
            override fun compress(data: ByteArray): ByteArray = error("decoder-only test")
            override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray {
                calls++
                val inflater = Inflater(true)
                try {
                    inflater.setInput(data)
                    val out = ByteArrayOutputStream()
                    val scratch = ByteArray(1024)
                    while (!inflater.finished()) {
                        val count = inflater.inflate(scratch)
                        assertTrue(count > 0 || inflater.finished(), "JDK decoder made no progress")
                        limits.checkOutputSize(out.size().toLong() + count)
                        limits.checkRatio(data.size.toLong(), out.size().toLong() + count)
                        out.write(scratch, 0, count)
                    }
                    assertEquals(0, inflater.remaining)
                    return out.toByteArray()
                } finally {
                    inflater.end()
                }
            }
        }
        val registry = CodecRegistry.DEFAULT.with(jdk)
        for (codecs in listOf(registry, CodecRegistry.DEFAULT)) {
            ZipReader(ByteArrayRandomAccessSource(sink.toByteArray()), codecs).use { reader ->
                val entries = reader.entries()
                assertEquals(payloads.size, entries.size)
                for ((index, entry) in entries.withIndex()) assertContentEquals(payloads[index], reader.read(entry))
            }
            assertEquals(payloads.size, calls)
        }
    }
}
