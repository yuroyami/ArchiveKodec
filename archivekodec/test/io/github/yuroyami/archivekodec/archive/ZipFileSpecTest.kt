package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ZipFileSpecTest {
    @Test
    fun constructorsAndFactoriesUseSupportedMethodsAndDeflatedDefaults() {
        val bytes = byteArrayOf(1, 2, 3)
        val direct = ZipWriter.FileSpec("a", bytes)
        assertEquals(ZipMethod.DEFLATED, direct.method)
        assertEquals(direct, ZipWriter.FileSpec.Builder("a", bytes).build())
        assertEquals(direct, ZipWriter.FileSpec("a", bytes, ZipMethod.DEFLATED))
        assertEquals(ZipWriter.FileSpec("a", bytes, ZipMethod.STORED), ZipWriter.FileSpec("a", bytes) {
            method = ZipMethod.STORED
        })
        assertEquals(0, ZipMethod.STORED.code)
        assertEquals(8, ZipMethod.DEFLATED.code)
    }

    @Test
    fun equalContentAgreesInEqualityHashingAndCollections() {
        val first = ZipWriter.FileSpec("a", byteArrayOf(1, 2, 3), ZipMethod.STORED)
        val equal = ZipWriter.FileSpec("a", byteArrayOf(1, 2, 3), ZipMethod.STORED)
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertEquals(setOf(first), setOf(equal))
        assertNotEquals(first, ZipWriter.FileSpec("b", equal.data, equal.method))
        assertNotEquals(first, ZipWriter.FileSpec("a", byteArrayOf(1, 2, 4), equal.method))
        assertNotEquals(first, ZipWriter.FileSpec("a", byteArrayOf(3, 2, 1), equal.method))
        assertNotEquals(first, ZipWriter.FileSpec("a", equal.data, ZipMethod.DEFLATED))
        assertTrue(!first.equals(null) && !first.equals("FileSpec"))
        assertEquals(ZipWriter.FileSpec("", byteArrayOf()), ZipWriter.FileSpec("", byteArrayOf()))
    }

    @Test
    fun inputAndGetterMutationsCannotChangeAStoredValueOrHashKey() {
        val input = byteArrayOf(1, 2, 3)
        val file = ZipWriter.FileSpec("a", input, ZipMethod.STORED)
        val expected = ZipWriter.FileSpec("a", input.copyOf(), ZipMethod.STORED)
        val keys = hashSetOf(file)
        input.fill(9)
        val publicBytes = file.data
        publicBytes.fill(8)
        assertNotSame(publicBytes, file.data)
        assertEquals(expected, file)
        assertEquals(expected.hashCode(), file.hashCode())
        assertTrue(expected in keys)
        assertContentEquals(byteArrayOf(1, 2, 3), file.data)
    }

    @Test
    fun builderReuseAndRetainedFactoryConfigurationCannotChangeSnapshots() {
        val input = byteArrayOf(1, 2, 3)
        val builder = ZipWriter.FileSpec.Builder("a", input)
        val first = builder.build()
        input[0] = 4
        builder.name = "b"
        builder.method = ZipMethod.STORED
        val second = builder.build()
        builder.data.fill(9)
        assertEquals("a", first.name)
        assertEquals(ZipMethod.DEFLATED, first.method)
        assertContentEquals(byteArrayOf(1, 2, 3), first.data)
        assertEquals("b", second.name)
        assertEquals(ZipMethod.STORED, second.method)
        assertContentEquals(byteArrayOf(4, 2, 3), second.data)
        lateinit var retained: ZipWriter.FileSpec.Builder
        val configured = ZipWriter.FileSpec("c", byteArrayOf(5), ZipMethod.STORED) { retained = this }
        retained.name = "changed"
        retained.method = ZipMethod.DEFLATED
        retained.data[0] = 7
        assertEquals("c", configured.name)
        assertEquals(ZipMethod.STORED, configured.method)
        assertContentEquals(byteArrayOf(5), configured.data)
    }

    @Test
    fun copyOwnsItsConfigurationBytesAndPreservesTheOriginal() {
        val original = ZipWriter.FileSpec("a", byteArrayOf(1, 2, 3), ZipMethod.STORED)
        val equal = original.copy()
        assertNotSame(original, equal)
        assertEquals(original, equal)
        lateinit var retained: ZipWriter.FileSpec.Builder
        val changed = original.copy {
            retained = this
            name = "b"
            method = ZipMethod.DEFLATED
            data[0] = 4
        }
        retained.data.fill(9)
        changed.data.fill(8)
        assertEquals("a", original.name)
        assertEquals(ZipMethod.STORED, original.method)
        assertContentEquals(byteArrayOf(1, 2, 3), original.data)
        assertEquals("b", changed.name)
        assertEquals(ZipMethod.DEFLATED, changed.method)
        assertContentEquals(byteArrayOf(4, 2, 3), changed.data)
    }

    @Test
    fun failedConfigurationCannotMutateTheSource() {
        val source = ZipWriter.FileSpec("a", byteArrayOf(1), ZipMethod.STORED)
        val error = IllegalArgumentException("invalid configuration")
        assertEquals(error, assertFailsWith<IllegalArgumentException> {
            source.copy { data[0] = 2; throw error }
        })
        assertEquals(error, assertFailsWith<IllegalArgumentException> {
            ZipWriter.FileSpec("b", byteArrayOf(3)) { throw error }
        })
        assertContentEquals(byteArrayOf(1), source.data)
        assertEquals(source, source.copy())
    }

    @Test
    fun descriptionsStayBoundedAndWellFormedForHugeOrMalformedNamesAndPayloads() {
        val payload = ByteArray(1_000_000) { ((it * 73) xor (it ushr 7)).toByte() }
        val surrogate = 0xD800.toChar().toString()
        for (name in listOf("a", "x".repeat(1_000_000), "\n\t\r\\\"\u0000".repeat(1000),
            "x".repeat(95) + "😀tail", surrogate + "tail")) {
            val file = ZipWriter.FileSpec(name, payload, ZipMethod.STORED)
            val description = file.toString()
            assertTrue(description.length < 200, "Description has ${description.length} characters")
            assertTrue(description.contains("bytes=1000000") && description.contains("method=STORED"))
            assertTrue(!description.contains('\n') && !description.contains('\r') && !description.contains('\u0000'))
            description.encodeToByteArray(throwOnInvalidSequence = true)
            assertEquals(name, file.name)
        }
        assertTrue(ZipWriter.FileSpec(surrogate, byteArrayOf()).toString().contains("\\ud800"))
        assertTrue(ZipWriter.FileSpec("x".repeat(1000), byteArrayOf()).toString().contains('…'))
    }

    @Test
    fun writerUsesTheOwnedPayloadForBothMethodsAndTheirChecksums() {
        for (method in ZipMethod.entries) {
            val expected = ByteArray(257) { it.toByte() }
            val input = expected.copyOf()
            val file = ZipWriter.FileSpec("a", input, method)
            input.fill(9)
            file.data.fill(8)
            ZipReader(ByteArrayRandomAccessSource(ZipWriter.write(listOf(file)))).use { reader ->
                val entry = reader.entries().single()
                assertEquals(if (method == ZipMethod.STORED) CodecId.STORE else CodecId.DEFLATE, entry.codec)
                assertEquals(expected.size.toLong(), entry.size)
                assertContentEquals(expected, reader.read(entry))
            }
        }
    }
}
