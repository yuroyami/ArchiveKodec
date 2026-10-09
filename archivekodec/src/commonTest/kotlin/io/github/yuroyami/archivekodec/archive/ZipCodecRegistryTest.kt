package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.CodecRegistry
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ZipCodecRegistryTest {
    private val data = "custom decoder payload".encodeToByteArray()
    private fun zip(method: ZipMethod = ZipMethod.STORED) = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", data, method)))
    private fun central(bytes: ByteArray) = readU32Le(bytes, bytes.size - 6).toInt()
    private fun patchMethod(bytes: ByteArray, method: Int): ByteArray = bytes.also {
        putLe(it, 8, method.toLong(), 2)
        putLe(it, central(it) + 10, method.toLong(), 2)
    }
    private class Decoder(override val id: CodecId, val decode: (ByteArray, ArchiveLimits) -> ByteArray) : Codec {
        var calls = 0
        var lastLimits: ArchiveLimits? = null
        override fun compress(data: ByteArray): ByteArray = error("read must not encode")
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray {
            calls++; lastLimits = limits
            return decode(data, limits)
        }
    }

    @Test fun registeredMethod93AndPrivateMethodDecodeWithoutChangingDefaults() {
        for ((method, id) in listOf(93 to CodecId("zstd"), 65535 to CodecId("private-wire"))) {
            val bytes = patchMethod(zip(), method)
            val engine = Decoder(id) { input, limits -> Codecs.STORE.decompress(input, limits) }
            val registry = CodecRegistry.DEFAULT.with(engine, method)
            ZipReader(ByteArrayRandomAccessSource(bytes), registry).use { reader ->
                assertSame(registry, reader.codecs)
                val entry = reader.entries().single()
                assertEquals(id, entry.codec)
                assertContentEquals(data, reader.read(entry))
            }
            assertEquals(1, engine.calls)
            ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(reader.entries().single()) }
                assertTrue(error.message.orEmpty().contains(method.toString()))
                assertEquals(8L, error.offset)
                assertEquals("a.txt", error.entryName)
            }
            assertEquals(1, engine.calls)
        }
    }

    @Test fun replacingDeflateAndStoreUsesTheRegisteredInstances() {
        for ((method, builtin) in listOf(ZipMethod.DEFLATED to Codecs.DEFLATE, ZipMethod.STORED to Codecs.STORE)) {
            val engine = Decoder(CodecId(builtin.id.name.uppercase())) { input, limits -> builtin.decompress(input, limits) }
            val registry = CodecRegistry.DEFAULT.with(engine)
            val bytes = zip(method)
            ZipReader(ByteArrayRandomAccessSource(bytes), registry).use { reader ->
                assertContentEquals(data, reader.read(reader.entries().single()))
            }
            assertEquals(1, engine.calls)
            ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                assertContentEquals(data, reader.read(reader.entries().single()))
            }
            assertEquals(1, engine.calls)
        }
    }

    @Test fun customRegistryConstructorsKeepNamesAndReadPolicies() {
        val engine = Decoder(CodecId("zstd")) { input, limits -> Codecs.STORE.decompress(input, limits) }
        val registry = CodecRegistry.DEFAULT.with(engine)
        val bytes = patchMethod(zip(), 93)
        val policy = ArchiveLimits { maxUncompressedBytes = data.size.toLong() }
        // Disable UTF-8 flag to exercise the explicitly supplied name decoder.
        putLe(bytes, 6, 0, 2); putLe(bytes, central(bytes) + 8, 0, 2)
        val names = ArchiveNameDecoder { "custom-name" }
        val readers = listOf(
            ZipReader(ByteArrayRandomAccessSource(bytes), registry),
            ZipReader(ByteArrayRandomAccessSource(bytes), policy, registry),
            ZipReader(ByteArrayRandomAccessSource(bytes), names, registry),
            ZipReader(ByteArrayRandomAccessSource(bytes), names, policy, registry),
        )
        for ((index, reader) in readers.withIndex()) reader.use {
            val entry = reader.entries().single()
            assertEquals(if (index >= 2) "custom-name" else "a.txt", entry.name)
            assertSame(if (index % 2 == 1) policy else ArchiveLimits.DEFAULT, reader.limits)
            assertContentEquals(data, reader.read(entry))
            assertSame(reader.limits, engine.lastLimits)
            assertContentEquals(data, reader.read(entry, ArchiveLimits.UNBOUNDED))
            assertSame(ArchiveLimits.UNBOUNDED, engine.lastLimits)
        }
    }

    @Test fun headerSizeAndRatioLimitsRunBeforeCustomDecoding() {
        val engine = Decoder(CodecId.ZSTD) { input, _ -> input }
        val registry = CodecRegistry.DEFAULT.with(engine)
        val bytes = patchMethod(zip(), 93)
        for (policy in listOf(ArchiveLimits { maxUncompressedBytes = data.size - 1L },
            ArchiveLimits { maxCompressionRatio = 0 })) {
            ZipReader(ByteArrayRandomAccessSource(bytes), policy, registry).use { reader ->
                assertFailsWith<DecompressionBombException> { reader.read(reader.entries().single()) }
            }
        }
        assertEquals(0, engine.calls)
    }

    @Test fun customOutputStillMustMatchSizeCrcAndLimits() {
        val bytes = patchMethod(zip(), 93)
        for (output in listOf(data.copyOf(data.size - 1), data.copyOf().also { it[0] = 0 })) {
            val engine = Decoder(CodecId.ZSTD) { _, _ -> output }
            ZipReader(ByteArrayRandomAccessSource(bytes), CodecRegistry.DEFAULT.with(engine)).use { reader ->
                val error = assertFailsWith<ArchiveFormatException> { reader.read(reader.entries().single()) }
                assertEquals("a.txt", error.entryName)
                assertEquals(35L, error.offset)
                assertTrue(error.message.orEmpty().contains(if (output.size != data.size) "size" else "CRC-32"))
            }
        }
        val engine = Decoder(CodecId.ZSTD) { _, _ -> data + 0 }
        for (policy in listOf(ArchiveLimits { maxUncompressedBytes = data.size.toLong() },
            ArchiveLimits { maxCompressionRatio = 1 })) {
            ZipReader(ByteArrayRandomAccessSource(bytes), policy, CodecRegistry.DEFAULT.with(engine)).use { reader ->
                assertFailsWith<DecompressionBombException> { reader.read(reader.entries().single()) }
            }
        }
    }

    @Test fun encryptedEntriesNeverReachACustomDecoderIncludingMethod99() {
        for ((method, flags) in listOf(93 to 1, 93 to 64, 99 to 0)) {
            val bytes = patchMethod(zip(), method)
            putLe(bytes, 6, (flags or 0x800).toLong(), 2)
            putLe(bytes, central(bytes) + 8, (flags or 0x800).toLong(), 2)
            val engine = Decoder(CodecId("custom")) { input, _ -> input }
            ZipReader(ByteArrayRandomAccessSource(bytes), CodecRegistry.DEFAULT.with(engine, method)).use { reader ->
                val entry = reader.entries().single()
                assertTrue(entry.isEncrypted)
                val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(entry) }
                assertTrue(error.message.orEmpty().contains("encryption"))
            }
            assertEquals(0, engine.calls)
        }
    }

    @Test fun customFailuresKeepEntryAndAbsolutePayloadOffset() {
        val engine = Decoder(CodecId.ZSTD) { _, _ -> throw ArchiveFormatException("custom failure", 2) }
        ZipReader(ByteArrayRandomAccessSource(patchMethod(zip(), 93)), CodecRegistry.DEFAULT.with(engine)).use { reader ->
            val error = assertFailsWith<ArchiveFormatException> { reader.read(reader.entries().single()) }
            assertEquals("a.txt", error.entryName)
            assertEquals(37L, error.offset)
            assertTrue(error.message.orEmpty().startsWith("custom failure"))
        }
    }

    @Test fun remappingStoredMethodCannotBypassItsWireSizeRule() {
        val bytes = zip()
        putLe(bytes, 22, data.size - 1L, 4)
        putLe(bytes, central(bytes) + 24, data.size - 1L, 4)
        val engine = Decoder(CodecId("other")) { _, _ -> data.copyOf(data.size - 1) }
        ZipReader(ByteArrayRandomAccessSource(bytes), CodecRegistry.DEFAULT.with(engine, 0)).use { reader ->
            val error = assertFailsWith<ArchiveFormatException> { reader.read(reader.entries().single()) }
            assertTrue(error.message.orEmpty().contains("stored compressed size"))
        }
        assertEquals(0, engine.calls)
    }
}
