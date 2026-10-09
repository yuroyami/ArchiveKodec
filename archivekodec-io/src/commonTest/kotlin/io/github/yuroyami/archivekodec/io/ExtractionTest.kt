package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchivePasswordException
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** #25: extracting a chosen set of entries to sinks, with progress, cancellation and passwords. */
@OptIn(ExperimentalEncodingApi::class)
class ExtractionTest {
    // From EncryptionTest: 7-Zip's ZipCrypto archive of in.txt (3000 bytes), password SECRET.
    private val zipCrypto = Base64.decode(
        "UEsDBBQAAQAIAHMmSV0HNwbmYAQAALgLAAAGAAAAaW4udHh07hVO0S3WOeULYX0B9akSYu4x+CPFVVY1PGeyDs8FiGMcHdrSFvSX" +
        "UwuBC415Yuve7p7667Ba5S89OcqbvJBa4dFUdhMmlBPf74y8IwiubL1RXMN+9ck56iIf6GWh6E+ijKKtorXXBBl0b88C4v1SREFQ" +
        "0/ulmOa7sLqg0IZSMlt6naUiOQ38Uag8TuBXvWjDZw/1vZFJSg0sLrjPeTfpTqUORScGqETdvK94mAsoqcPZNXOnAjrFaC2ZGDRt" +
        "su8u7ZgFSNL0ipeLRPoxWslC5A8IrV7xJZQRE0vz7wLYcSYDdCvR9fqNUXKo8qNRP204nfB9aprdHq6Z9IGsvalQZZDpi5kQ721b" +
        "TQlzj/95edKyP7MMcc1Ml66ySxExAfFLf4lbl+QOsU/ehAXzi87V4p+FgF5d7kyEAAoy63BiWa9nA1C1Dyq6yRnADeKPen7IrrYH" +
        "wrTu7zwFDRhgsEKkWInT0mEDqFCkhRRYaQJglp+uoIDBAb+EVTMnfCzjwPTJSXtGJuZe+J6jyMghASMYr2ybNjeuTOx9Qk3jBClg" +
        "iOnhyHudJhHKi0NWrjL98MucTS83bUq+U0sXEYNcbk9uNtJx5cpaTAHxrwFQaYYrfTicYauvC7QY3dZrh3juIL81DVGke39TAy3X" +
        "cRc9SmTlD7QvH03XIj3Ism5efNWhI4cg4lYHfodU2IH5hrKeeH/Raze1luDU3CchA2Ld316MHFj5hKA4mqUb338pRWi1ebEzC9Ub" +
        "KIggD6RRNCaQsM4iqSYPD5GuVXUTcFlJDDcAqjDNIDHmVxzVMMMAJ31V1isz1dpYvzC2GEd4LaHwc/0pSBQ2ghXtatmAXzdim99C" +
        "ICSfoFP46tnGgi66Yon8qx1dzmMReR2toWNadWRziv4wdQsG9CeJYaYIyN1bMn00L4b1ybfswzxA9XqX3Oc8p002TeuWQcRy5A8P" +
        "wuHl5MEYgkdsc6Ey56MN8/ywji2htgeHykxlQdvGUS6V5GEY4CdqmF6kCq7Sl4nmGzMm48rFPxD5JPCU0/+p0dw+vJZiMtZbUc8t" +
        "s+2lbTKUXGH//aCaaN46f2mAJARdyTCovcyCtLmPfn7Y8jmwQKm+0+wS6SmrIRrIMWG65ZonHULVLDgrxbXVPBJxkt+nUi7tNnw2" +
        "t214E8JfTs6EGr4XZw0jTn5FBhl3lrxkST9iaGhoeeFT58ZpcdZ4UQgKtIeTfBvMJ8CN5YuyQ39U+3TbCeklRaGrm6Ie2VfJ1ApS" +
        "K0GZ7bZr89XHVLmd3yTlgDQOn4jeJFr1d858TFYvEFgmJOCJ9dDigZuVfuynL+1FeRVfoKcybaCd0AtLmbdGBpm+nv4VVpBOGjYc" +
        "fbWzKBQezoQ3+ujc1QeDKwYLS8iSvBhrY/P4nZQJkSVP42Fw1y+087H5smLUbgSIZXLj77zAuuEBA2VhRVGwOds3vx78B5uI2R/2" +
        "1NnNQ7RyKzpVaqV18yzUaeEPxApOBP4837XoWKFH71BLAQI/AxQAAQAIAHMmSV0HNwbmYAQAALgLAAAGACQAAAAAAAAAIICkgQAA" +
        "AABpbi50eHQKACAAAAAAAAEAGABb+HLdqVfdAQAAAAAAAAAAAAAAAAAAAABQSwUGAAAAAAEAAQBYAAAAhAQAAAAA",
    )

    // From CabReaderTest: gcab's MSZIP cabinet, three files in one folder.
    private val mszipGz = Base64.decode(
        "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",
    )

    private class Sink : RawSink {
        val buffer = Buffer()
        var closed = false
        override fun write(source: Buffer, byteCount: Long) = buffer.write(source, byteCount)
        override fun flush() {}
        override fun close() { closed = true }
    }

    /** A source that counts the bytes read from it. */
    private class Counting(bytes: ByteArray) : RandomAccessSource {
        private val delegate = ByteArrayRandomAccessSource(bytes)
        var bytesRead = 0L
        override val size get() = delegate.size
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int =
            delegate.read(offset, into, intoOffset, length).also { if (it > 0) bytesRead += it }
    }

    private val big = Random(25).nextBytes(64) .let { seed -> ByteArray(6_000_000) { seed[(it / 3) % seed.size] } }
    private val zip = ZipWriter.write(listOf(
        ZipWriter.FileSpec("dir/", ByteArray(0)),
        ZipWriter.FileSpec("dir/a.txt", "alpha".encodeToByteArray()),
        ZipWriter.FileSpec("big.bin", big),
        ZipWriter.FileSpec("z.txt", "omega".encodeToByteArray()),
    ))

    @Test
    fun extractsAChosenSetInListingOrderWithProgress() = runTest {
        ArchiveKodec.open(zip).use { reader ->
            val byName = reader.entries().associateBy { it.name }
            val sinks = LinkedHashMap<String, Sink>()
            val reports = ArrayList<Pair<Long, Long>>()
            // Asked for out of order, and including a directory, which is skipped.
            val chosen = listOf("z.txt", "big.bin", "dir/", "dir/a.txt").map { byName.getValue(it) }
            reader.extract(chosen, progress = { done, total -> reports += done to total }) { entry ->
                Sink().also { sinks[entry.name] = it }
            }
            assertEquals(listOf("dir/a.txt", "big.bin", "z.txt"), sinks.keys.toList())
            assertContentEquals("alpha".encodeToByteArray(), sinks.getValue("dir/a.txt").buffer.readByteArray())
            assertContentEquals(big, sinks.getValue("big.bin").buffer.readByteArray())
            assertContentEquals("omega".encodeToByteArray(), sinks.getValue("z.txt").buffer.readByteArray())
            assertTrue(sinks.values.all { it.closed })
            val total = 5L + big.size + 5
            assertTrue(reports.all { it.second == total })
            assertEquals(reports.map { it.first }.sorted(), reports.map { it.first })
            assertEquals(total, reports.last().first)
            assertTrue(reports.size >= 6, "big.bin streams in several writes: $reports")
            assertFailsWith<IllegalArgumentException> {
                reader.extract(listOf(ArchiveEntryOf("stranger.txt"))) { Sink() }
            }
        }
    }

    @Test
    fun cancellingStopsInsideALargeEntry() = runTest {
        ArchiveKodec.open(zip).use { reader ->
            val bigEntry = reader.entries().single { it.name == "big.bin" }
            val sink = Sink()
            var job: kotlinx.coroutines.Job? = null
            job = launch {
                reader.extract(listOf(bigEntry), progress = { done, _ -> if (done >= 1_000_000) job?.cancel() }) { sink }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(sink.buffer.size < big.size, "stopped after ${sink.buffer.size} of ${big.size} bytes")
            assertTrue(sink.closed)
        }
    }

    @Test
    fun aSolidFolderIsDecodedOnce() = runTest {
        val cab = Gzip.decompress(mszipGz, ArchiveLimits.UNBOUNDED)
        val source = Counting(cab)
        ArchiveKodec.open(source).use { reader ->
            val entries = reader.entries()
            val listed = source.bytesRead
            val sinks = HashMap<String, Sink>()
            reader.extract(entries.reversed()) { entry -> Sink().also { sinks[entry.name] = it } }
            assertEquals(3, sinks.size)
            assertContentEquals("Hello, cabinet!\n".encodeToByteArray(), sinks.getValue("hello.txt").buffer.readByteArray())
            // One decode of the folder reads its data blocks once: less than the cabinet's size.
            assertTrue(source.bytesRead - listed < cab.size, "read ${source.bytesRead - listed} bytes of a ${cab.size}-byte cabinet")
        }
    }

    @Test
    fun passwordsArePromptedPerEncryptedEntry() = runTest {
        val asked = ArrayList<String>()
        val passwords = PromptedPasswords { name -> asked += name; "SECRET".toCharArray() }
        ArchiveKodec.open(zipCrypto, format = null, options = ArchiveOpenOptions { password = passwords }).use { reader ->
            val sink = Sink()
            reader.extract(reader.entries(), passwords = passwords) { sink }
            assertEquals(listOf("in.txt"), asked)
            assertEquals(3000L, sink.buffer.size) // the reader checked the CRC-32
        }
        val refusing = PromptedPasswords { null }
        ArchiveKodec.open(zipCrypto, format = null, options = ArchiveOpenOptions { password = refusing }).use { reader ->
            assertFailsWith<ArchivePasswordException> { reader.extract(reader.entries(), passwords = refusing) { Sink() } }
        }
    }

    private fun ArchiveEntryOf(name: String) = io.github.yuroyami.archivekodec.archive.ArchiveEntry(name, 1, false)
}
