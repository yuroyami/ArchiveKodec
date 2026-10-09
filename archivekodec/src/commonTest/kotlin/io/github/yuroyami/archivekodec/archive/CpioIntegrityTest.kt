package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.*

class CpioIntegrityTest {
    private val payload = "DATA-123".encodeToByteArray()
    private data class Spec(
        val name: String, val data: ByteArray = byteArrayOf(), val magic: String = "070701",
        val inode: Long = 1, val links: Long = 2, val major: Long = 1, val minor: Long = 13,
        val mode: Long = 0x81a4, val check: Long? = null,
    )
    private data class Fixture(val bytes: ByteArray, val headers: List<Int>, val bodies: List<Pair<Long, Long>>)

    private fun prefix(s: Spec, size: Long = s.data.size.toLong()): ByteArray {
        val name = s.name.encodeToByteArray() + byteArrayOf(0)
        fun number(v: Long, radix: Int, width: Int) = v.toString(radix).padStart(width, '0')
        if (s.magic == "070707") {
            return (s.magic + number(s.major, 8, 6) + number(s.inode, 8, 6) + number(s.mode, 8, 6) +
                "000000000000" + number(s.links, 8, 6) + "00000000000000000" +
                number(name.size.toLong(), 8, 6) + number(size, 8, 11)).encodeToByteArray() + name
        }
        val sum = s.check ?: if (s.magic == "070702") s.data.sumOf { it.toLong() and 255 } and 0xffffffffL else 0
        val fields = longArrayOf(s.inode, s.mode, 0, 0, s.links, 0, size, s.major, s.minor, 0, 0, name.size.toLong(), sum)
        val named = (s.magic + fields.joinToString("") { number(it, 16, 8) }).encodeToByteArray() + name
        return named + ByteArray((4 - named.size % 4) % 4)
    }

    private fun fixture(vararg specs: Spec, trailerMagic: String = specs.lastOrNull()?.magic ?: "070701", trailerCheck: Long = 0): Fixture {
        var bytes = byteArrayOf()
        val headers = mutableListOf<Int>()
        val bodies = mutableListOf<Pair<Long, Long>>()
        for (s in specs.toList() + Spec("TRAILER!!!", magic = trailerMagic, inode = 0, links = 1, check = trailerCheck)) {
            headers.add(bytes.size)
            val p = prefix(s)
            bodies.add((bytes.size + p.size).toLong() to s.data.size.toLong())
            bytes += p + s.data + if (s.magic == "070707") byteArrayOf() else ByteArray((4 - s.data.size % 4) % 4)
        }
        return Fixture(bytes, headers, bodies)
    }

    private fun reader(f: Fixture) = CpioReader(ByteArrayRandomAccessSource(f.bytes))
    private fun assertPayloads(r: CpioReader, expected: ByteArray, count: Int) {
        val entries = r.entries()
        assertEquals(count, entries.size)
        for (e in entries) {
            assertEquals(expected.size.toLong(), e.size, e.name)
            assertEquals(e.size, e.compressedSize)
            assertNull(e.crc32)
            assertContentEquals(expected, r.read(e), e.name)
            assertContentEquals(expected, r.read(e.copy()), e.name)
        }
    }

    @Test fun binaryCpioIsDetectedAndExplicitlyRefusedInBothByteOrders() {
        for (magic in listOf(byteArrayOf(0xc7.toByte(), 0x71), byteArrayOf(0x71, 0xc7.toByte()))) {
            for (bytes in listOf(magic, magic + ByteArray(24))) {
                assertEquals(ArchiveFormat.CPIO, Magic.sniff(bytes))
                ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { r ->
                    repeat(2) {
                        val error = assertFailsWith<ArchiveUnsupportedException> { r.entries() }
                        assertEquals(0L, error.offset)
                        assertTrue(error.message.orEmpty().contains("binary cpio"))
                    }
                }
            }
        }
    }

    @Test fun rejectsUnknownOrIncompleteFirstHeadersInsteadOfReturningAnEmptyListing() {
        val inputs = listOf(byteArrayOf(), byteArrayOf(0), "07070".encodeToByteArray(),
            "notcpio".encodeToByteArray(), ByteArray(26))
        for (bytes in inputs) {
            CpioReader(ByteArrayRandomAccessSource(bytes)).use { r ->
                repeat(2) {
                    val error = assertFailsWith<ArchiveFormatException> { r.entries() }
                    assertEquals(0L, error.offset)
                }
            }
        }
    }

    @Test fun rejectsUnknownOrIncompleteHeadersWithoutCachingAPartialListing() {
        for (magic in listOf("070701", "070702", "070707")) {
            val f = fixture(Spec("a", payload, magic))
            val offset = f.headers[1]
            val badHeaders = (1..5).map { ByteArray(it) } + listOf("broken".encodeToByteArray())
            for (badHeader in badHeaders) {
                val bytes = f.bytes.copyOf(offset) + badHeader
                CpioReader(ByteArrayRandomAccessSource(bytes)).use { r ->
                    repeat(2) {
                        val error = assertFailsWith<ArchiveFormatException> { r.entries() }
                        assertEquals(offset.toLong(), error.offset)
                    }
                }
            }
        }
    }

    @Test fun refusesBinaryHeadersAfterSupportedRecords() {
        for (magic in listOf("070701", "070702", "070707")) {
            val f = fixture(Spec("a", payload, magic))
            val offset = f.headers[1]
            for (binary in listOf(byteArrayOf(0xc7.toByte(), 0x71), byteArrayOf(0x71, 0xc7.toByte()))) {
                val bytes = f.bytes.copyOf(offset) + binary + ByteArray(24)
                CpioReader(ByteArrayRandomAccessSource(bytes)).use { r ->
                    repeat(2) {
                        val error = assertFailsWith<ArchiveUnsupportedException> { r.entries() }
                        assertEquals(offset.toLong(), error.offset)
                    }
                }
            }
        }
    }

    @Test fun trailerTerminatedEmptyArchivesAndBlockPaddingRemainValid() {
        for (magic in listOf("070701", "070702", "070707")) {
            val empty = fixture(trailerMagic = magic)
            CpioReader(ByteArrayRandomAccessSource(empty.bytes + ByteArray(512))).use { r ->
                assertTrue(r.entries().isEmpty())
            }
            val f = fixture(Spec("a", payload, magic))
            CpioReader(ByteArrayRandomAccessSource(f.bytes + ByteArray(512))).use { r ->
                assertPayloads(r, payload, 1)
            }
        }
    }

    @Test fun resolvesPayloadAtEveryPositionInAllAsciiDialects() {
        for (magic in listOf("070701", "070702", "070707")) for (position in 0..2) {
            val specs = (0..2).map { Spec("link$it", if (it == position) payload else byteArrayOf(), magic, links = 3) }
            reader(fixture(*specs.toTypedArray(), trailerMagic = magic)).use { assertPayloads(it, payload, 3) }
        }
    }

    @Test fun lastNonemptyPayloadWinsAndEmptyAliasesDoNotEraseIt() {
        for (magic in listOf("070701", "070702", "070707")) {
            reader(fixture(Spec("old", payload, magic), Spec("new", byteArrayOf(7, 8), magic), Spec("alias", magic = magic))).use {
                assertPayloads(it, byteArrayOf(7, 8), 3)
            }
        }
    }

    @Test fun allEmptyGroupsAndIncompleteLinkSetsAreValid() {
        reader(fixture(Spec("empty"), Spec("also-empty"))).use { assertPayloads(it, byteArrayOf(), 2) }
        reader(fixture(Spec("only-included-link", payload, links = 200))).use { assertPayloads(it, payload, 1) }
    }

    @Test fun separatesInodesAndBothDeviceComponents() {
        val f = fixture(Spec("a", payload), Spec("a-alias"), Spec("inode", byteArrayOf(1), inode = 2),
            Spec("major", byteArrayOf(2), major = 2), Spec("minor", byteArrayOf(3), minor = 14))
        reader(f).use { r ->
            val entries = r.entries()
            val expected = listOf(payload, payload, byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
            for ((e, data) in entries.zip(expected)) assertContentEquals(data, r.read(e))
        }
    }

    @Test fun separatesOdcDevicesAndInodes() {
        reader(fixture(Spec("a", payload, "070707"), Spec("alias", magic = "070707"),
            Spec("device", byteArrayOf(2), "070707", major = 2), Spec("inode", byteArrayOf(3), "070707", inode = 2))).use { r ->
            for ((e, data) in r.entries().zip(listOf(payload, payload, byteArrayOf(2), byteArrayOf(3)))) assertContentEquals(data, r.read(e))
        }
    }

    @Test fun singleLinksAndDirectoriesNeverJoinGroups() {
        reader(fixture(Spec("linked", payload), Spec("single", links = 1), Spec("dir", mode = 0x41ed), Spec("other-dir", mode = 0x41ed))).use { r ->
            val e = r.entries()
            assertContentEquals(payload, r.read(e[0]))
            for (i in 1..3) assertContentEquals(byteArrayOf(), r.read(e[i]))
            assertTrue(e[2].isDirectory); assertTrue(e[3].isDirectory)
        }
    }

    @Test fun symlinkPayloadsResolveWithoutCombiningDifferentFileTypes() {
        reader(fixture(Spec("symlink", payload, mode = 0xa1ff), Spec("symlink-alias", mode = 0xa1ff))).use { assertPayloads(it, payload, 2) }
        val f = fixture(Spec("file", payload), Spec("symlink", payload, mode = 0xa1ff))
        val error = assertFailsWith<ArchiveFormatException> { reader(f).entries() }
        assertEquals("symlink", error.entryName)
        assertEquals(f.headers[1].toLong() + 14, error.offset)
    }

    @Test fun permissionsDoNotSplitAGroup() {
        reader(fixture(Spec("private", payload, mode = 0x8180), Spec("public", mode = 0x81ff))).use { assertPayloads(it, payload, 2) }
    }

    @Test fun newcAndCrcShareGroupsButOdcHasItsOwnDeviceNamespace() {
        reader(fixture(Spec("newc"), Spec("crc", payload, "070702"), Spec("odc", byteArrayOf(7), "070707"))).use { r ->
            val e = r.entries()
            assertContentEquals(payload, r.read(e[0])); assertContentEquals(payload, r.read(e[1]))
            assertContentEquals(byteArrayOf(7), r.read(e[2]))
        }
    }

    @Test fun crcUsesUnsignedByteSumAndDoesNotExposeItAsCrc32() {
        reader(fixture(Spec("unsigned", byteArrayOf(-1, -128, 0, 127), "070702"))).use {
            assertPayloads(it, byteArrayOf(-1, -128, 0, 127), 1)
        }
    }

    @Test fun rejectsBadCarrierChecksumForEveryAliasWithPhysicalContext() {
        val f = fixture(Spec("alias", magic = "070702"), Spec("carrier", payload, "070702", check = 478))
        reader(f).use { r -> for (e in r.entries()) {
            val error = assertFailsWith<ArchiveFormatException> { r.read(e) }
            assertEquals("carrier", error.entryName)
            assertEquals(f.headers[1].toLong() + 102, error.offset)
        } }
    }

    @Test fun alsoChecksOverwrittenPhysicalPayloads() {
        val f = fixture(Spec("corrupt-old", payload, "070702", check = 1), Spec("final", byteArrayOf(3), "070702"))
        reader(f).use { r -> for (e in r.entries()) {
            val error = assertFailsWith<ArchiveFormatException> { r.read(e) }
            assertEquals("corrupt-old", error.entryName); assertEquals(102L, error.offset)
        } }
    }

    @Test fun rejectsNonzeroChecksumsForEmptyFilesAndAliasesWhileListing() {
        for (links in listOf(1L, 2L)) {
            val error = assertFailsWith<ArchiveFormatException> { reader(fixture(Spec("empty", magic = "070702", links = links, check = 1))).entries() }
            assertEquals("empty", error.entryName); assertEquals(102L, error.offset)
        }
    }

    @Test fun validatesEmptyDirectoryAndTrailerChecksums() {
        assertEquals("dir", assertFailsWith<ArchiveFormatException> { reader(fixture(Spec("dir", magic = "070702", mode = 0x41ed, check = 1))).entries() }.entryName)
        val f = fixture(Spec("a"), trailerMagic = "070702", trailerCheck = 1)
        val error = assertFailsWith<ArchiveFormatException> { reader(f).entries() }
        assertEquals("TRAILER!!!", error.entryName); assertEquals(f.headers.last().toLong() + 102, error.offset)
    }

    @Test fun newcCheckFieldIsNotMistakenForACrcChecksum() {
        reader(fixture(Spec("newc", payload, check = 0xffffffffL))).use { assertPayloads(it, payload, 1) }
    }

    private class ObservedSource(val fixture: Fixture, val shortReads: Boolean = false) : RandomAccessSource {
        private val delegate = ByteArrayRandomAccessSource(fixture.bytes)
        var forbidPayload = false
        var payloadReads = 0
        var failure: ArchiveIOException? = null
        override val size: Long get() = delegate.size
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            if (fixture.bodies.any { (start, count) -> count > 0 && offset < start + count && offset + length > start }) {
                check(!forbidPayload) { "metadata listing touched a payload" }
                payloadReads++
                failure?.let { throw it }
            }
            return delegate.read(offset, into, intoOffset, if (shortReads) minOf(length, 3) else length)
        }
    }

    @Test fun listingResolvesSizesWithoutTouchingAnyPayload() {
        val s = ObservedSource(fixture(Spec("alias", magic = "070702"), Spec("carrier", payload, "070702")))
        s.forbidPayload = true
        CpioReader(s).use { r -> assertEquals(listOf(8L, 8L), r.entries().map { it.size }); assertEquals(0, s.payloadReads) }
    }

    @Test fun logicalAliasLimitsRunBeforePayloadReads() {
        val s = ObservedSource(fixture(Spec("alias", magic = "070702"), Spec("carrier", payload, "070702")))
        CpioReader(s).use { r ->
            val e = r.entries().first(); s.forbidPayload = true
            assertFailsWith<DecompressionBombException> { r.read(e, ArchiveLimits { maxUncompressedBytes = 7 }) }
            assertFailsWith<DecompressionBombException> { r.read(e, ArchiveLimits { maxCompressionRatio = 0 }) }
            assertEquals(0, s.payloadReads)
            s.forbidPayload = false
            assertContentEquals(payload, r.read(e, ArchiveLimits { maxUncompressedBytes = 8 }))
        }
    }

    @Test fun changedEntryMetadataCannotBypassCanonicalChecks() {
        reader(fixture(Spec("alias"), Spec("carrier", payload, "070702"))).use { r ->
            val e = r.entries().first()
            for (fake in listOf(e.copy { size = 0 }, e.copy { name = "other" }, e.copy { isDirectory = true }, e.copy { compressedSize = 0 })) {
                assertFailsWith<ArchiveFormatException> { r.read(fake) }
            }
            assertContentEquals(payload, r.read(e.copy()))
        }
    }

    @Test fun returnedListCannotChangePrivateDirectory() {
        reader(fixture(Spec("alias"), Spec("carrier", payload))).use { r ->
            val e = r.entries().first()
            assertFails { (r.entries() as MutableList<ArchiveEntry>).clear() }
            assertEquals(2, r.entries().size); assertContentEquals(payload, r.read(e))
        }
    }

    @Test fun renamedAliasesAndRawNameCopiesDoNotChangeGrouping() {
        var calls = 0
        val f = fixture(Spec("alias"), Spec("carrier", payload))
        CpioReader(ByteArrayRandomAccessSource(f.bytes), ArchiveNameDecoder { calls++; it.fill(0); "same" }).use { r ->
            val e = r.entries()
            assertEquals(listOf("same", "same"), e.map { it.name })
            assertContentEquals("alias".encodeToByteArray(), e[0].nameBytes)
            e[0].nameBytes!!.fill(0)
            assertPayloads(r, payload, 2); assertEquals(2, calls)
        }
    }

    @Test fun checksumsAreRecomputedAfterSuccessfulReads() {
        val f = fixture(Spec("alias"), Spec("carrier", payload, "070702"))
        reader(f).use { r ->
            val e = r.entries().first(); assertContentEquals(payload, r.read(e))
            f.bytes[f.bodies[1].first.toInt()] = 0
            assertFailsWith<ArchiveFormatException> { r.read(e) }
        }
    }

    @Test fun changedOverwrittenBodiesAreAlsoDetected() {
        val f = fixture(Spec("old", payload, "070702"), Spec("final", byteArrayOf(9), "070702"))
        reader(f).use { r ->
            val e = r.entries().last(); assertContentEquals(byteArrayOf(9), r.read(e))
            f.bytes[f.bodies[0].first.toInt()] = 0
            assertFailsWith<ArchiveFormatException> { r.read(e) }
        }
    }

    @Test fun checksumReadsHandleShortReadsAndPreserveIoFailure() {
        val s = ObservedSource(fixture(Spec("old", ByteArray(20000) { -1 }, "070702"), Spec("final", payload, "070702")), true)
        CpioReader(s).use { r ->
            val e = r.entries().last(); assertContentEquals(payload, r.read(e))
            val failure = ArchiveIOException("source failed", IllegalStateException("cause"), 42, "source")
            s.failure = failure
            assertSame(failure, assertFailsWith<ArchiveIOException> { r.read(e) })
        }
    }

    @Test fun overwrittenChecksumScanRejectsInvalidReadCounts() {
        val f = fixture(Spec("old", payload, "070702"), Spec("final", byteArrayOf(9), "070702"))
        for (count in listOf(-1, 0, 9)) {
            var armed = false
            val source = object : RandomAccessSource {
                val delegate = ByteArrayRandomAccessSource(f.bytes)
                override val size: Long get() = delegate.size
                override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int =
                    if (armed && offset == f.bodies[0].first) count else delegate.read(offset, into, intoOffset, length)
            }
            CpioReader(source).use { r ->
                val e = r.entries().last(); armed = true
                val error = assertFailsWith<ArchiveFormatException> { r.read(e) }
                assertEquals("old", error.entryName); assertEquals(f.bodies[0].first, error.offset)
            }
        }
    }

    @Test fun directoryReadsCannotBypassAStoredBodyChecksum() {
        val f = fixture(Spec("dir", payload, "070702", mode = 0x41ed))
        reader(f).use { r ->
            val e = r.entries().single(); assertEquals(0L, e.size)
            assertContentEquals(byteArrayOf(), r.read(e))
            f.bytes[f.bodies[0].first.toInt()] = 0
            assertEquals("dir", assertFailsWith<ArchiveFormatException> { r.read(e) }.entryName)
        }
    }

    @Test fun hardLinkGroupsCannotCrossTheFirstTrailerBoundary() {
        val first = fixture(Spec("empty-alias"))
        val second = fixture(Spec("other-segment", payload))
        CpioReader(ByteArrayRandomAccessSource(first.bytes + second.bytes)).use { assertPayloads(it, byteArrayOf(), 1) }
    }

    @Test fun rejectsMalformedLinkAndChecksumNumbersAtTheirExactByte() {
        for (field in listOf(6, 14, 38, 54, 62, 70, 94, 102)) {
            val f = fixture(Spec("a", payload, "070702")); f.bytes[field + 7] = 'g'.code.toByte()
            assertEquals((field + 7).toLong(), assertFailsWith<ArchiveFormatException> { reader(f).entries() }.offset)
        }
        for (field in listOf(6, 12, 18, 36, 59, 65)) {
            val f = fixture(Spec("a", payload, "070707")); f.bytes[field] = '8'.code.toByte()
            assertEquals(field.toLong(), assertFailsWith<ArchiveFormatException> { reader(f).entries() }.offset)
        }
    }

    @Test fun trailerCannotHideAStoredBody() {
        val f = fixture(Spec("TRAILER!!!", payload))
        assertEquals("TRAILER!!!", assertFailsWith<ArchiveFormatException> { reader(f).entries() }.entryName)
    }

    private class SparseSource(
        override val size: Long, val regions: List<Pair<Long, ByteArray>>, val fillStart: Long, val fillSize: Long,
    ) : RandomAccessSource {
        var forbidPayload = true
        var largestPayloadRead = 0
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            val count = minOf(length.toLong(), size - offset).toInt()
            if (offset < fillStart + fillSize && offset + count > fillStart) {
                check(!forbidPayload); largestPayloadRead = maxOf(largestPayloadRead, count)
            }
            into.fill(0, intoOffset, intoOffset + count)
            val start = maxOf(offset, fillStart); val end = minOf(offset + count, fillStart + fillSize)
            if (start < end) into.fill(-1, intoOffset + (start - offset).toInt(), intoOffset + (end - offset).toInt())
            for ((position, bytes) in regions) {
                val from = maxOf(position, offset); val to = minOf(position + bytes.size, offset + count)
                if (from < to) bytes.copyInto(into, intoOffset + (from - offset).toInt(), (from - position).toInt(), (to - position).toInt())
            }
            return count
        }
    }

    @Test fun overwrittenChecksumWrapsAt32BitsAndUsesBoundedChunks() {
        val size = 16_843_010L // 255 * size = 2^32 + 254.
        val first = prefix(Spec("old", magic = "070702", check = 254), size)
        val next = first.size + size + ((4 - size % 4) % 4)
        val last = prefix(Spec("final", byteArrayOf(9), "070702")) + byteArrayOf(9, 0, 0, 0)
        val trailer = prefix(Spec("TRAILER!!!", links = 1))
        val s = SparseSource(next + last.size + trailer.size, listOf(0L to first, next to last, next + last.size to trailer), first.size.toLong(), size)
        CpioReader(s).use { r ->
            val e = r.entries().first(); assertEquals(1L, e.size); assertEquals(0, s.largestPayloadRead)
            s.forbidPayload = false
            assertContentEquals(byteArrayOf(9), r.read(e, ArchiveLimits { maxUncompressedBytes = 1 }))
            assertTrue(s.largestPayloadRead in 1..8192)
        }
    }

    @Test fun largeLogicalAliasCanBeListedButNeverAllocatedAsWholeArray() {
        val size = Int.MAX_VALUE.toLong() + 1
        val alias = prefix(Spec("alias")); val carrier = prefix(Spec("carrier"), size)
        val body = (alias.size + carrier.size).toLong()
        val trailer = prefix(Spec("TRAILER!!!", links = 1))
        val s = SparseSource(body + size + trailer.size, listOf(0L to alias, alias.size.toLong() to carrier, body + size to trailer), body, size)
        CpioReader(s).use { r ->
            val e = r.entries().first(); assertEquals(size, e.size)
            assertFailsWith<DecompressionBombException> { r.read(e, ArchiveLimits { maxUncompressedBytes = 8 }) }
            assertTrue(assertFailsWith<ArchiveFormatException> { r.read(e, ArchiveLimits.UNBOUNDED) }.message!!.contains("ByteArray"))
            assertEquals(0, s.largestPayloadRead)
        }
    }

    // bsdcpio 3.5.3, two actual filesystem hard links to DATA-123.
    @Test fun readsBsdcpioNewcHardLinks() {
        val hex = "30373037303130393132306634323030303038316134303030303031663530303030303030303030303030303032366163356637363230303030303030303030303030303031303030303030306430303030303030303030303030303030303030303030303830303030303030306f6e652e747874000000303730373031303931323066343230303030383161343030303030316635303030303030303030303030303030323661633566373632303030303030303830303030303030313030303030303064303030303030303030303030303030303030303030303038303030303030303074776f2e747874000000444154412d3132333037303730313030303030303030303030303030303030303030303030303030303030303030303030303030303130303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030623030303030303030545241494c4552212121000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        CpioReader(ByteArrayRandomAccessSource(data)).use { r ->
            assertEquals(listOf("one.txt", "two.txt"), r.entries().map { it.name })
            assertPayloads(r, payload, 2)
        }
    }

    // bsdcpio 3.5.3, two actual filesystem hard links to DATA-123.
    @Test fun readsBsdcpioOdcHardLinks() {
        val hex = "303730373037373737373737303030303031313030363434303030373635303030303030303030303032303030303030313532363133373335343230303030313030303030303030303031306f6e652e74787400444154412d3132333037303730373737373737373030303030313130303634343030303736353030303030303030303030323030303030303135323631333733353432303030303130303030303030303030313074776f2e74787400444154412d31323330373037303730303030303030303030303030303030303030303030303030303030303030303030303130303030303030303030303030303030303030303031333030303030303030303030545241494c45522121210000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        CpioReader(ByteArrayRandomAccessSource(data)).use { r ->
            assertEquals(listOf("one.txt", "two.txt"), r.entries().map { it.name })
            assertPayloads(r, payload, 2)
        }
    }
}
