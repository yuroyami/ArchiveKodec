package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveClosedException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The file source on every platform with a file system: JVM, Android host, native and
 * Node. Where opening a file is unsupported (WASI, browsers) the source must say so with
 * [ArchiveUnsupportedException], and the test stops there.
 */
class FileRandomAccessSourceTest {
    private fun temporaryFile(bytes: ByteArray): Path? = try {
        // A browser has no temporary directory, so even naming one fails there.
        Path(SystemTemporaryDirectory, "archivekodec-${Random.nextLong().toULong()}.bin").also { path ->
            SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        }
    } catch (_: Throwable) {
        null // no writable file system here
    }

    private fun open(path: Path): FileRandomAccessSource? = try {
        FileRandomAccessSource(path.toString())
    } catch (_: ArchiveUnsupportedException) {
        null
    }

    @Test
    fun readsAcrossPagesAndDirectly() {
        val page = FileRandomAccessSource.PAGE_SIZE
        val bytes = Random(5).nextBytes(3 * page + 1234)
        val path = temporaryFile(bytes) ?: return
        try {
            val source = open(path) ?: return
            assertEquals(bytes.size.toLong(), source.size)
            // Small reads within, across and at the end of pages, more than the cache holds.
            for (offset in listOf(0L, 10L, page - 3L, page.toLong(), 2L * page - 1, 3L * page + 1200, 5L, 2L * page + 7)) {
                val length = minOf(100L, bytes.size - offset).toInt()
                assertContentEquals(bytes.copyOfRange(offset.toInt(), offset.toInt() + length), source.readFully(offset, length), "at $offset")
            }
            // A read of a page or more goes straight to the file.
            assertContentEquals(bytes.copyOfRange(100, 100 + 2 * page), source.readFully(100, 2 * page))
            val tail = ByteArray(50)
            assertEquals(34, source.read(bytes.size - 34L, tail, 0, 50))
            assertEquals(-1, source.read(bytes.size.toLong(), tail, 0, 1))
            assertEquals(0, source.read(bytes.size.toLong(), tail, 0, 0))
            source.close()
            assertFailsWith<ArchiveClosedException> { source.read(0, tail, 0, 1) }
            source.close() // a second close does nothing
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    @Test
    fun opensArchivesWithoutLoadingThem() {
        val zip = ZipWriter.write(listOf(
            ZipWriter.FileSpec("a.txt", "first".encodeToByteArray()),
            ZipWriter.FileSpec("big.bin", Random(9).nextBytes(200_000)),
            ZipWriter.FileSpec("z.txt", "last".encodeToByteArray()),
        ))
        val path = temporaryFile(zip) ?: return
        try {
            val source = open(path) ?: return
            ArchiveKodec.open(source).use { reader ->
                val entries = reader.entries().associateBy { it.name }
                assertEquals(setOf("a.txt", "big.bin", "z.txt"), entries.keys)
                assertContentEquals("last".encodeToByteArray(), reader.read(entries.getValue("z.txt")))
                assertContentEquals(Random(9).nextBytes(200_000), reader.read(entries.getValue("big.bin")))
            }
            // Closing the reader closed the source.
            assertFailsWith<ArchiveClosedException> { source.read(0, ByteArray(1), 0, 1) }
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    @Test
    fun missingFilesFailAsIo() {
        val name = "archivekodec-missing-${Random.nextLong().toULong()}.bin"
        val missing = try {
            Path(SystemTemporaryDirectory, name).toString()
        } catch (_: Throwable) {
            name // a browser: the source must still refuse, as unsupported
        }
        try {
            FileRandomAccessSource(missing)
        } catch (_: ArchiveUnsupportedException) {
            return
        } catch (_: ArchiveIOException) {
            return
        }
        error("opening a missing file succeeded")
    }
}
