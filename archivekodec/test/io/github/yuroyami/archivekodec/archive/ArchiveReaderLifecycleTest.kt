package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveClosedException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArchiveReaderLifecycleTest {
    private val readers: List<(RandomAccessSource) -> ArchiveReader> = listOf(::ZipReader, ::TarReader, ::ArReader, ::CpioReader)
    private val payload = "ABC".encodeToByteArray()
    private val failure = ArchiveIOException("test source failure", IllegalStateException("test cause"), 7, "source")

    private class TrackingSource(bytes: ByteArray) : RandomAccessSource {
        private val delegate = ByteArrayRandomAccessSource(bytes)
        var accesses = 0
        var closes = 0
        var readFailure: ArchiveIOException? = null
        var onClose: () -> Unit = {}
        override val size: Long get() { accesses++; return delegate.size }
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            accesses++
            readFailure?.let { throw it }
            return delegate.read(offset, into, intoOffset, length)
        }
        override fun close() { closes++; onClose() }
    }

    private fun fixture(index: Int): ByteArray = when (index) {
        0 -> zipMetadataBase()
        1 -> ByteArray(512).also {
            "a.txt".encodeToByteArray().copyInto(it)
            "00000000003".encodeToByteArray().copyInto(it, 124)
            it[156] = '0'.code.toByte()
            "ustar\u000000".encodeToByteArray().copyInto(it, 257)
            it.fill(32, 148, 156)
            it.sumOf { b -> b.toInt() and 255 }.toString(8).padStart(6, '0').encodeToByteArray().copyInto(it, 148)
            it[154] = 0
        } + payload + ByteArray(509 + 1024)
        2 -> "!<arch>\n".encodeToByteArray() + ByteArray(60) { 32 }.also {
            "a.txt/".encodeToByteArray().copyInto(it)
            it[48] = '3'.code.toByte(); it[58] = 0x60; it[59] = 10
        } + payload + byteArrayOf(10)
        else -> newc("a.txt", payload) + newc("TRAILER!!!", byteArrayOf())
    }

    private fun newc(name: String, data: ByteArray): ByteArray {
        val named = "$name\u0000".encodeToByteArray()
        val fields = longArrayOf(1, 0x81A4, 0, 0, 1, 0, data.size.toLong(), 0, 0, 0, 0, named.size.toLong(), 0)
        val header = ("070701" + fields.joinToString("") { it.toString(16).padStart(8, '0') }).encodeToByteArray()
        return header + named + ByteArray((4 - (110 + named.size) % 4) % 4) + data + ByteArray((4 - data.size % 4) % 4)
    }

    @Test
    fun closesUnparsedSourceExactlyOnce() {
        for (create in readers) {
            var closes = 0
            val source = object : RandomAccessSource {
                override val size: Long get() = error("close must not inspect source")
                override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int = error("close must not read")
                override fun close() { closes++ }
            }
            val reader = create(source)
            reader.close()
            reader.close()
            assertEquals(1, closes, reader.format.name)
        }
    }

    @Test
    fun cachedEntriesAndReadsAreUnavailableAfterClose() {
        for ((i, create) in readers.withIndex()) {
            val source = TrackingSource(fixture(i))
            val reader = create(source)
            val entries = reader.entries()
            val entry = entries.single()
            assertContentEquals(payload, reader.read(entry))
            assertSame(entries, reader.entries())
            reader.close()
            val accesses = source.accesses
            assertFailsWith<ArchiveClosedException> { reader.entries() }
            for (member in listOf(entry, entry.copy { size = 0 }, entry.copy { isDirectory = true })) {
                val error = assertFailsWith<ArchiveClosedException> { reader.read(member, ArchiveLimits { maxUncompressedBytes = 0 }) }
                assertEquals(member.name, error.entryName)
                assertEquals(member.dataOffset, error.offset)
            }
            assertEquals(accesses, source.accesses)
            assertEquals(1, source.closes)
            assertTrue(reader.toString().contains("closed"))
            assertEquals("a.txt", entries.single().name) // Returned metadata remains caller-owned.
        }
    }

    @Test
    fun listingFailureKeepsSourceUntilClose() {
        for ((i, create) in readers.withIndex()) {
            val source = TrackingSource(fixture(i)).also { it.readFailure = failure }
            val reader = create(source)
            assertSame(failure, assertFailsWith<ArchiveIOException> { reader.entries() })
            assertEquals(0, source.closes)
            source.readFailure = null
            assertContentEquals(payload, reader.read(reader.entries().single()))
            reader.close()
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun readFailureKeepsSourceUntilClose() {
        for ((i, create) in readers.withIndex()) {
            val source = TrackingSource(fixture(i))
            val reader = create(source)
            val entry = reader.entries().single()
            source.readFailure = failure
            assertSame(failure, assertFailsWith<ArchiveIOException> { reader.read(entry) })
            assertEquals(0, source.closes)
            source.readFailure = null
            assertContentEquals(payload, reader.read(entry))
            reader.close()
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun malformedListingCanStillBeClosed() {
        val broken = listOf(byteArrayOf(), fixture(1).copyOf(512), "invalid!".encodeToByteArray(), "070701".encodeToByteArray())
        for ((i, create) in readers.withIndex()) {
            val source = TrackingSource(broken[i])
            val reader = create(source)
            assertFailsWith<ArchiveFormatException> { reader.entries() }
            reader.close()
            reader.close()
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun closeFailureLeavesReaderClosedBeforeCleanup() {
        for ((i, create) in readers.withIndex()) {
            val source = TrackingSource(fixture(i))
            val reader = create(source)
            val entry = reader.entries().single()
            source.onClose = {
                assertFailsWith<ArchiveClosedException> { reader.entries() }
                assertFailsWith<ArchiveClosedException> { reader.read(entry) }
                assertTrue(reader.toString().contains("closed"))
                reader.close() // Reentrant close must not attempt cleanup twice.
                throw failure
            }
            assertSame(failure, assertFailsWith<ArchiveIOException> { reader.close() })
            reader.close()
            assertFailsWith<ArchiveClosedException> { reader.entries() }
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun useClosesOnNormalReturn() {
        for ((i, create) in readers.withIndex()) {
            val source = TrackingSource(fixture(i))
            val reader = create(source)
            assertContentEquals(payload, reader.use { it.read(it.entries().single()) })
            assertEquals(1, source.closes)
            assertFailsWith<ArchiveClosedException> { reader.entries() }
        }
    }

    @Test
    fun useClosesOnNonLocalReturn() {
        val source = TrackingSource(fixture(0))
        fun result(): Int = ZipReader(source).use { return 42 }
        assertEquals(42, result())
        assertEquals(1, source.closes)
    }

    @Test
    fun useClosesAfterBlockFailure() {
        for (create in readers) {
            val source = TrackingSource(byteArrayOf())
            val primary = IllegalStateException("block failed")
            assertSame(primary, assertFailsWith<IllegalStateException> { create(source).use { throw primary } })
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun useKeepsPrimaryFailureAndSuppressesCloseFailure() {
        for (create in readers) {
            val source = TrackingSource(byteArrayOf()).also { it.onClose = { throw failure } }
            val primary = IllegalStateException("block failed")
            val reader = create(source)
            assertSame(primary, assertFailsWith<IllegalStateException> { reader.use { throw primary } })
            assertSame(failure, primary.suppressedExceptions.single())
            reader.close()
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun useReportsCloseFailureAfterSuccessfulBlock() {
        for (create in readers) {
            val source = TrackingSource(byteArrayOf()).also { it.onClose = { throw failure } }
            val reader = create(source)
            assertSame(failure, assertFailsWith<ArchiveIOException> { reader.use { 42 } })
            reader.close()
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun autoCloseableViewSharesTheReaderLifecycle() {
        for ((i, create) in readers.withIndex()) {
            val source = TrackingSource(fixture(i))
            val reader = create(source)
            val view = reader.asAutoCloseable()
            view.use { assertContentEquals(payload, reader.read(reader.entries().single())) }
            view.close()
            reader.close()
            assertEquals(1, source.closes)
            assertFailsWith<ArchiveClosedException> { reader.entries() }
        }
    }

    @Test
    fun nullableUsePassesNullToBlock() {
        val reader: ArchiveReader? = null
        assertEquals(42, reader.use { assertNull(it); 42 })
    }

    @Test
    fun successfulOpenTransfersSourceOwnership() {
        for (i in readers.indices) {
            val source = TrackingSource(fixture(i))
            ArchiveKodec.open(source).use { assertContentEquals(payload, it.read(it.entries().single())) }
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun failedOpenLeavesSourceOwnershipWithCaller() {
        for (readFailure in listOf(null, failure)) {
            val source = TrackingSource("unknown format".encodeToByteArray()).also { it.readFailure = readFailure }
            if (readFailure == null) assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(source) }
            else assertSame(failure, assertFailsWith<ArchiveIOException> { ArchiveKodec.open(source) })
            assertEquals(0, source.closes)
            source.close()
            assertEquals(1, source.closes)
        }
    }
}
