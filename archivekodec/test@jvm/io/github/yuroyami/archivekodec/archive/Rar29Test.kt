package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.archive.rar.RarReader
import io.github.yuroyami.archivekodec.codec.rar.RarFilter
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RAR 2.9 decompression, which RAR 3.x and 4.x write, on libarchive's test archives. The reader
 * checks every member's CRC-32, so a read that returns is byte-exact.
 */
class Rar29Test {
    private val dir = javaClass.classLoader.getResource("rar4/README.md")!!.let { File(it.toURI()).parentFile }
    private val limits = ArchiveLimits { rejectUnsafeNames = false }

    /** The size of every file member, each read in full and checked against its CRC-32. */
    private fun readAll(bytes: ByteArray, budget: ArchiveLimits = limits): List<Int> {
        val reader = RarReader(ByteArrayRandomAccessSource(bytes), budget)
        try {
            return reader.entries().filter { !it.isDirectory }.map { reader.read(it, budget).size }
        } finally {
            reader.close()
        }
    }

    private fun fixture(name: String) = File(dir, "$name.rar").readBytes()

    @Test
    fun lzssPpmdFiltersAndUnicodeNamesDecodeWithTheirCrc() {
        // An HTML page, a link to it, a test file and a nested one, at RAR's normal and best methods.
        assertEquals(listOf(20111, 20111, 25, 20), readAll(fixture("compress_normal")).sortedDescending())
        assertEquals(listOf(20111, 20111, 25, 20), readAll(fixture("compress_best")).sortedDescending())
        assertEquals(listOf(204288), readAll(fixture("filter")))
        for (name in listOf("multi_lzss_blocks", "filter_incomplete_block", "lowdist_reset", "noeof", "unicode", "windows")) {
            assertTrue(readAll(fixture(name)).isNotEmpty(), name)
        }
        // A PPMd block that turns into LZ77 blocks and back, expanding 176 KB to 241,647,978 bytes.
        val large = ArchiveLimits { rejectUnsafeNames = false; maxUncompressedBytes = 300_000_000 }
        assertEquals(listOf(241_647_978), readAll(fixture("ppmd_lzss_conversion"), large))
    }

    @Test
    fun theVirtualMachineRunsRarsOwnFilterBytecode() {
        // The x86 filter normally runs natively once its bytecode is recognized; run that bytecode
        // in the machine instead, and the CRC-32 still has to match.
        RarFilter.interpretStandardFilters = true
        try {
            assertEquals(listOf(204288), readAll(fixture("filter")))
        } finally {
            RarFilter.interpretStandardFilters = false
        }
    }

    @Test
    fun malformedArchivesFailWithArchiveExceptions() {
        for (name in listOf("invalid1", "overflow", "unbound_staticdata")) {
            assertFailsWith<ArchiveException>(name) { readAll(fixture(name)) }
        }
    }

    @Test
    fun corruptedArchivesFailOnlyWithArchiveExceptions() {
        val random = Random(29)
        val bounded = ArchiveLimits { rejectUnsafeNames = false; maxUncompressedBytes = 64L shl 20 }
        for (name in listOf("compress_normal", "filter", "multi_lzss_blocks")) {
            val original = fixture(name)
            repeat(300) {
                val bytes = original.copyOf()
                repeat(1 + random.nextInt(4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
                // Any other exception, a hang or running out of memory fails the test.
                try {
                    val reader = RarReader(ByteArrayRandomAccessSource(bytes), bounded)
                    try {
                        for (e in reader.entries()) if (!e.isDirectory) try { reader.read(e) } catch (_: ArchiveException) {}
                    } finally {
                        reader.close()
                    }
                } catch (_: ArchiveException) {
                }
            }
        }
    }
}
