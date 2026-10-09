package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ArchiveLimitsTest {
    @Test
    fun defaultZeroAndMaximumPoliciesPreserveTheirBoundaries() {
        val defaults = ArchiveLimits()
        assertEquals(ArchiveLimits.DEFAULT, defaults)
        assertEquals(2L * 1024 * 1024 * 1024, defaults.maxUncompressedBytes)
        assertEquals(10_000, defaults.maxCompressionRatio)
        assertEquals(100_000, defaults.maxEntries)
        assertEquals(100_000, defaults.maxSegments)
        assertEquals(8, defaults.maxNestingDepth)
        assertEquals(256L * 1024 * 1024, defaults.maxDecoderMemory)
        val zero = ArchiveLimits {
            maxUncompressedBytes = 0
            maxCompressionRatio = 0
            maxEntries = 0
            maxSegments = 0
            maxNestingDepth = 0
        }
        zero.checkOutputSize(0)
        zero.checkRatio(1, 0)
        zero.checkEntryCount(0)
        assertFailsWith<DecompressionBombException> { zero.checkOutputSize(1) }
        assertFailsWith<DecompressionBombException> { zero.checkRatio(1, 1) }
        assertFailsWith<DecompressionBombException> { zero.checkEntryCount(1) }
        assertEquals(Long.MAX_VALUE, ArchiveLimits.UNBOUNDED.maxUncompressedBytes)
        assertEquals(Int.MAX_VALUE, ArchiveLimits.UNBOUNDED.maxCompressionRatio)
        assertEquals(Int.MAX_VALUE, ArchiveLimits.UNBOUNDED.maxEntries)
        assertEquals(Int.MAX_VALUE, ArchiveLimits.UNBOUNDED.maxSegments)
        assertEquals(Int.MAX_VALUE, ArchiveLimits.UNBOUNDED.maxNestingDepth)
        assertEquals(Long.MAX_VALUE, ArchiveLimits.UNBOUNDED.maxDecoderMemory)
        ArchiveLimits.UNBOUNDED.checkOutputSize(Long.MAX_VALUE)
        ArchiveLimits.UNBOUNDED.checkEntryCount(Int.MAX_VALUE)
    }

    @Test
    fun builderReuseCannotChangeAnExistingSnapshot() {
        val builder = ArchiveLimits.Builder().apply {
            maxUncompressedBytes = 7
            maxCompressionRatio = 3
            maxEntries = 5
            maxSegments = 6
            maxNestingDepth = 2
        }
        val first = builder.build()
        val hash = first.hashCode()
        builder.maxUncompressedBytes = 8
        builder.maxCompressionRatio = 4
        builder.maxEntries = 6
        builder.maxSegments = 7
        builder.maxNestingDepth = 1
        val second = builder.build()
        assertEquals(listOf(7L, 3L, 5L, 6L, 2L), values(first))
        assertEquals(listOf(8L, 4L, 6L, 7L, 1L), values(second))
        assertEquals(hash, first.hashCode())
        assertNotSame(first, second)
    }

    @Test
    fun copyPreservesOtherSettingsAndDoesNotRetainItsBuilder() {
        val original = ArchiveLimits {
            maxUncompressedBytes = 7
            maxCompressionRatio = 3
            maxEntries = 5
            maxSegments = 6
            maxNestingDepth = 2
        }
        lateinit var configuration: ArchiveLimits.Builder
        val changed = original.copy {
            configuration = this
            maxEntries = 1
        }
        configuration.maxUncompressedBytes = 0
        configuration.maxEntries = 9
        assertEquals(listOf(7L, 3L, 5L, 6L, 2L), values(original))
        assertEquals(listOf(7L, 3L, 1L, 6L, 2L), values(changed))
        val equal = original.copy()
        assertEquals(original, equal)
        assertNotSame(original, equal)
    }

    @Test
    fun valueMethodsAgreeAndDescriptionsStayBounded() {
        val policy = ArchiveLimits.DEFAULT
        val equal = ArchiveLimits.Builder().build()
        assertEquals(policy, equal)
        assertEquals(policy.hashCode(), equal.hashCode())
        for ((_, configure) in invalidSettings(0)) assertNotEquals(policy, policy.copy(configure))
        assertTrue(!policy.equals(null))
        assertTrue(!policy.equals("ArchiveLimits"))
        val description = ArchiveLimits.UNBOUNDED.toString()
        assertTrue(description.length < 260)
        for (field in listOf("maxUncompressedBytes", "maxCompressionRatio", "maxEntries", "maxSegments", "maxNestingDepth", "maxDecoderMemory")) {
            assertTrue(description.contains("$field="))
        }
        assertTrue(description.contains(Long.MAX_VALUE.toString()))
        assertTrue(description.contains(Int.MAX_VALUE.toString()))
    }

    @Test
    fun everyConstructionPathRejectsEachNegativeSetting() {
        for (negative in listOf(-1L, Long.MIN_VALUE)) {
            for ((field, configure) in invalidSettings(negative)) {
                for (construct in listOf<() -> ArchiveLimits>(
                    { ArchiveLimits.Builder().apply(configure).build() },
                    { ArchiveLimits(configure) },
                    { ArchiveLimits.DEFAULT.copy(configure) },
                )) {
                    val failure = assertFailsWith<IllegalArgumentException>(field, construct)
                    assertTrue(failure.message!!.contains(field))
                }
            }
        }
        assertEquals(ArchiveLimits(), ArchiveLimits.DEFAULT)
    }

    @Test
    fun validationIsDeferredAndFailedBuildersCanBeCorrected() {
        val builder = ArchiveLimits.Builder()
        builder.maxEntries = -1
        assertFailsWith<IllegalArgumentException> { builder.build() }
        builder.maxEntries = 5
        assertEquals(5, builder.build().maxEntries)
        val original = ArchiveLimits { maxEntries = 7 }
        assertFailsWith<IllegalArgumentException> {
            original.copy {
                maxEntries = 0
                throw IllegalArgumentException("configuration callback failed")
            }
        }
        assertEquals(7, original.maxEntries)
    }

    private fun values(limits: ArchiveLimits): List<Long> = listOf(
        limits.maxUncompressedBytes, limits.maxCompressionRatio.toLong(),
        limits.maxEntries.toLong(), limits.maxSegments.toLong(), limits.maxNestingDepth.toLong(),
    )

    private fun invalidSettings(value: Long): List<Pair<String, ArchiveLimits.Builder.() -> Unit>> = listOf(
        "maxUncompressedBytes" to { maxUncompressedBytes = value },
        "maxCompressionRatio" to { maxCompressionRatio = if (value == Long.MIN_VALUE) Int.MIN_VALUE else value.toInt() },
        "maxEntries" to { maxEntries = if (value == Long.MIN_VALUE) Int.MIN_VALUE else value.toInt() },
        "maxSegments" to { maxSegments = if (value == Long.MIN_VALUE) Int.MIN_VALUE else value.toInt() },
        "maxNestingDepth" to { maxNestingDepth = if (value == Long.MIN_VALUE) Int.MIN_VALUE else value.toInt() },
        "maxDecoderMemory" to { maxDecoderMemory = value },
    )

    @Test
    fun ratioAcceptsEqualityAndRejectsTheNextByte() {
        for (ratio in listOf(1, 2, 10_000)) {
            val limits = ArchiveLimits { maxCompressionRatio = ratio }
            limits.checkRatio(2, 2L * ratio)
            assertFailsWith<DecompressionBombException> { limits.checkRatio(2, 2L * ratio + 1) }
        }
        ArchiveLimits { maxCompressionRatio = 0 }.checkRatio(1, 0)
        assertFailsWith<DecompressionBombException> { ArchiveLimits { maxCompressionRatio = 0 }.checkRatio(1, 1) }
    }

    @Test
    fun largeRatiosDoNotOverflowOrLoseTheRemainder() {
        ArchiveLimits { maxCompressionRatio = 1 }.checkRatio(Long.MAX_VALUE, Long.MAX_VALUE)
        val half = Long.MAX_VALUE / 2
        val limits = ArchiveLimits { maxCompressionRatio = 2 }
        limits.checkRatio(half, Long.MAX_VALUE - 1)
        assertFailsWith<DecompressionBombException> { limits.checkRatio(half, Long.MAX_VALUE) }
        assertFailsWith<DecompressionBombException> {
            ArchiveLimits.UNBOUNDED.checkRatio(4_294_967_296L, Long.MAX_VALUE)
        }
    }

    @Test
    fun truthfulZipMetadataEnforcesFractionalExcess() {
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", ByteArray(5) { 65 }, ZipMethod.DEFLATED)))
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        val entry = reader.entries().single()
        assertEquals(5L, entry.size)
        assertEquals(4L, entry.compressedSize)
        assertFailsWith<DecompressionBombException> { reader.read(entry, ArchiveLimits { maxCompressionRatio = 1 }) }
        assertEquals(5, reader.read(entry, ArchiveLimits { maxCompressionRatio = 2 }).size)
    }
}
