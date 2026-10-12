package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtractionBudgetTest {
    private val mib = 1024 * 1024

    private fun threeEntries(method: ZipMethod): ByteArray = ZipWriter.write(
        (0 until 3).map { i -> ZipWriter.FileSpec("e$i.bin", ByteArray(mib) { (it * 31 + i).toByte() }, method) },
    )

    @Test
    fun thirdMebibyteEntryPassesATwoMebibyteBudget() {
        for (method in listOf(ZipMethod.STORED, ZipMethod.DEFLATED)) {
            ArchiveKodec.open(ByteArrayRandomAccessSource(threeEntries(method))).use { reader ->
                val budget = ExtractionBudget(2L * mib)
                val entries = reader.entries()
                assertEquals(mib, reader.read(entries[0], budget = budget).size)
                assertEquals(mib, reader.read(entries[1], budget = budget).size)
                assertEquals(2L * mib, budget.usedBytes)
                val error = assertFailsWith<DecompressionBombException>(method.name) { reader.read(entries[2], budget = budget) }
                assertTrue(error.message.orEmpty().contains("extraction budget"), error.message)
                assertEquals(2L * mib, budget.usedBytes, "a failed read charges nothing")
                // The reader itself keeps no total: reads without the budget still work.
                assertEquals(mib, reader.read(entries[2]).size)
            }
        }
    }

    @Test
    fun budgetAndPerCallLimitsBothApply() {
        ArchiveKodec.open(ByteArrayRandomAccessSource(threeEntries(ZipMethod.DEFLATED))).use { reader ->
            val entry = reader.entries()[0]
            val budget = ExtractionBudget(10L * mib)
            assertFailsWith<DecompressionBombException> {
                reader.read(entry, ArchiveLimits { maxUncompressedBytes = mib - 1L }, budget)
            }
            assertEquals(0L, budget.usedBytes)
            val bytes = reader.read(entry, ArchiveLimits.DEFAULT, budget)
            assertContentEquals(reader.read(entry), bytes)
            assertEquals(mib.toLong(), budget.usedBytes)
            assertEquals(9L * mib, budget.remainingBytes)
        }
    }

    @Test
    fun readerLimitsAreTheDefaultUnderABudget() {
        val limits = ArchiveLimits { maxUncompressedBytes = 100 }
        ArchiveKodec.open(ByteArrayRandomAccessSource(threeEntries(ZipMethod.STORED)), limits).use { reader ->
            val error = assertFailsWith<DecompressionBombException> {
                reader.read(reader.entries()[0], budget = ExtractionBudget(Long.MAX_VALUE))
            }
            assertTrue(error.message.orEmpty().contains("limit 100"), error.message)
        }
    }

    @Test
    fun chargeIsAllOrNothing() {
        val budget = ExtractionBudget(10)
        budget.charge(4)
        budget.charge(6)
        assertEquals(0L, budget.remainingBytes)
        budget.charge(0)
        assertFailsWith<DecompressionBombException> { budget.charge(1) }
        assertEquals(10L, budget.usedBytes)
        assertFailsWith<IllegalArgumentException> { budget.charge(-1) }
        assertFailsWith<IllegalArgumentException> { ExtractionBudget(-1) }
        assertFailsWith<DecompressionBombException> { ExtractionBudget(0).charge(Long.MAX_VALUE) }
    }
}
