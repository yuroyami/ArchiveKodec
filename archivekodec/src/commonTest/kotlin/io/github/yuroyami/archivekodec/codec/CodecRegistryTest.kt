package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.FormatRegistry
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

internal val knownCodecIds = listOf(CodecId.STORE, CodecId.DEFLATE, CodecId.ZLIB, CodecId.GZIP,
    CodecId.LZ4, CodecId.SNAPPY, CodecId.SNAPPY_FRAMED, CodecId.BZIP2, CodecId.LZMA,
    CodecId.LZMA2, CodecId.XZ, CodecId.ZSTD, CodecId.BROTLI, CodecId.PPMD)

class CodecRegistryTest {
    private val defaults = CodecRegistry.DEFAULT
    private fun codec(name: String): Codec = object : Codec {
        override val id = CodecId(name)
        override fun compress(data: ByteArray) = data.copyOf()
        override fun decompress(data: ByteArray, limits: ArchiveLimits) = Codecs.STORE.decompress(data, limits)
    }

    @Test fun identifiersAreOpenCanonicalValues() {
        val custom = CodecId("My.Codec_v2+fast-1")
        val same = CodecId("my.codec_v2+fast-1")
        assertEquals(same, custom)
        assertEquals(same.hashCode(), custom.hashCode())
        assertEquals("my.codec_v2+fast-1", custom.name)
        assertEquals(custom.name, custom.displayName)
        assertEquals(custom.name, custom.toString())
        assertNotEquals(custom, CodecId("another"))
        assertEquals(CodecId.ZSTD, CodecId("ZSTD"))
        assertEquals(14, knownCodecIds.toSet().size)
    }

    @Test fun invalidIdentifiersAreRejectedAndInvalidLookupReturnsNull() {
        for (name in listOf("", " ", " gzip", "gzip ", "x/y", "x:y", "a,b", "a\n", "é", "K", "-bad")) {
            assertFailsWith<IllegalArgumentException>(name) { CodecId(name) }
            assertNull(defaults.byName(name), name)
        }
        assertNull(defaults.byName("missing"))
        assertNull(defaults.byName("x-gzip"))
    }

    @Test fun supportComesFromTheRegistryAndFacadeUsesDefault() {
        val expected = listOf(Codecs.STORE, Codecs.DEFLATE, Codecs.DEFLATE64, Codecs.BZIP2, Codecs.GZIP, Codecs.ZLIB,
            Codecs.LZ4, Codecs.SNAPPY, Codecs.SNAPPY_FRAMED)
        assertEquals(expected.map { it.id }, defaults.implemented)
        assertEquals(defaults.implemented, Codecs.implemented)
        assertEquals(defaults.implemented, ArchiveKodec.codecs)
        for (id in knownCodecIds + CodecId("external")) {
            val value = expected.find { it.id == id }
            assertSame(value, defaults.byId(CodecId(id.name)))
            assertSame(value, defaults.byName(id.name.uppercase()))
            assertSame(value, Codecs.byId(id))
            assertSame(value, ArchiveKodec.codec(id))
            assertSame(value, ArchiveKodec.codec(id.name))
        }
        for (format in FormatRegistry.DEFAULT.formats.map { it.id } + ArchiveFormat.UNKNOWN) assertSame(defaults.byFormat(format), ArchiveKodec.codec(format))
        assertSame(Codecs.STORE, defaults.byZipMethod(0))
        assertSame(Codecs.DEFLATE, defaults.byZipMethod(8))
        assertSame(Codecs.DEFLATE64, defaults.byZipMethod(9))
        assertSame(Codecs.BZIP2, defaults.byZipMethod(12))
        for (method in listOf(-1, 11, 14, 93, 98, 65535, 65536)) assertNull(defaults.byZipMethod(method))
    }

    @Test fun derivedRegistriesAndReturnedIdListsCannotChangeEarlierBindings() {
        val first = codec("private")
        val second = codec("PRIVATE")
        val a = defaults.with(first, 65535)
        val b = a.with(second)
        val sibling = defaults.with(codec("sibling"), 65535)
        assertNotSame(defaults, a)
        assertNull(defaults.byId(first.id))
        assertNull(defaults.byZipMethod(65535))
        assertSame(first, a.byZipMethod(65535))
        assertSame(second, b.byZipMethod(65535))
        assertSame(second, b.byName("PRIVATE"))
        assertEquals(CodecId("sibling"), sibling.byZipMethod(65535)!!.id)
        assertEquals(a.implemented, b.implemented)
        val ids = a.implemented
        (ids as MutableList<CodecId>).clear()
        assertEquals(defaults.implemented + first.id, a.implemented)
        assertSame(first, a.byId(first.id))
    }

    @Test fun replacementUpdatesEveryExistingMethodBindingForTheId() {
        val original = codec("deflate")
        val registered = defaults.with(original, 100)
        val replacement = codec("DEFLATE")
        val replaced = registered.with(replacement)
        for (method in listOf(8, 100)) assertSame(replacement, replaced.byZipMethod(method))
        assertSame(original, registered.byZipMethod(8))
        assertSame(Codecs.DEFLATE, defaults.byZipMethod(8))
        assertSame(replacement, replaced.byId(CodecId.DEFLATE))
        assertEquals(defaults.implemented, replaced.implemented)
    }

    @Test fun explicitMethodBindingCanOverrideAKnownMethodWithoutChangingItsOldId() {
        val engine = codec("fast-deflate")
        val registered = defaults.with(engine, 8)
        assertSame(engine, registered.byZipMethod(8))
        assertSame(engine, registered.byName("FAST-DEFLATE"))
        assertSame(Codecs.DEFLATE, registered.byId(CodecId.DEFLATE))
        assertSame(Codecs.STORE, registered.byZipMethod(0))
    }

    @Test fun knownUnavailableMethodsBecomeAvailableWhenTheirIdIsRegistered() {
        for ((method, id) in listOf(14 to CodecId.LZMA, 93 to CodecId.ZSTD, 98 to CodecId.PPMD)) {
            val engine = codec(id.name)
            assertSame(engine, defaults.with(engine).byZipMethod(method))
            assertNull(defaults.byZipMethod(method))
        }
        val zstd = codec("zstd")
        assertSame(zstd, defaults.with(zstd).byFormat(ArchiveFormat.ZSTD))
        assertNull(defaults.byFormat(ArchiveFormat.ZSTD))
    }

    @Test fun onlyUnsignedSixteenBitMethodsCanBeRegistered() {
        val engine = codec("external")
        for (method in listOf(Int.MIN_VALUE, -1, 65536, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { defaults.with(engine, method) }
        }
        for (method in listOf(0, 65535)) assertSame(engine, defaults.with(engine, method).byZipMethod(method))
    }
}
