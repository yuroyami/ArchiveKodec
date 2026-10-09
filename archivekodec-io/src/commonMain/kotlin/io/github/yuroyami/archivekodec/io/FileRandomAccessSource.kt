package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveClosedException
import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.RandomAccessSource

/**
 * A [RandomAccessSource] over a file, read on demand: opening a multi-gigabyte archive
 * holds only a few small buffers, not the archive. Pass it to a reader such as
 * `ArchiveKodec.open(FileRandomAccessSource(path))`; the reader then owns it and closes
 * it with itself.
 *
 * Reads go through a cache of [CACHE_PAGES] pages of [PAGE_SIZE] bytes, so the small reads
 * readers make (ZIP's end-of-central-directory scan, TAR's 512-byte headers, metadata
 * blocks) cost one system call per page; reads of a page or more bypass the cache.
 *
 * The file is opened read-only with the platform's own API: `java.io.RandomAccessFile` on
 * the JVM and Android, POSIX `pread` on Apple, Linux and Android native, `ReadFile` on
 * Windows, and Node's `fs` module on Kotlin/JS and Kotlin/Wasm (JS). Browsers and WASI have
 * no file system to open, so there the constructor throws [ArchiveUnsupportedException].
 * The file's size is taken once, at opening; do not change the file while it is open.
 *
 * Not thread-safe: serialize calls, as [RandomAccessSource] asks.
 *
 * @param path a file path in the platform's syntax.
 * @throws ArchiveIOException if the file cannot be opened or its size read.
 * @throws ArchiveUnsupportedException on a platform without a file system.
 */
public class FileRandomAccessSource @Throws(ArchiveException::class) public constructor(
    private val path: String,
) : RandomAccessSource {
    private var file: PlatformFile? = openPlatformFile(path)

    override val size: Long = handle().size

    private val pages = arrayOfNulls<ByteArray>(CACHE_PAGES)
    private val pageIndex = LongArray(CACHE_PAGES) { -1 }
    private val pageLength = IntArray(CACHE_PAGES)
    private var nextVictim = 0

    private fun handle(): PlatformFile = file ?: throw ArchiveClosedException("file source is closed: $path")

    @Throws(ArchiveException::class, IllegalArgumentException::class)
    override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        require(intoOffset >= 0 && length >= 0 && length <= into.size - intoOffset) { "invalid destination slice" }
        if (offset < 0) throw ArchiveFormatException("negative source offset $offset", offset)
        val file = handle()
        if (length == 0) return 0
        if (offset >= size) return -1
        val wanted = minOf(length.toLong(), size - offset).toInt()
        if (wanted >= PAGE_SIZE) return readFully(file, offset, into, intoOffset, wanted)
        var done = 0
        while (done < wanted) {
            val position = offset + done
            val page = page(file, position / PAGE_SIZE)
            val inPage = (position % PAGE_SIZE).toInt()
            val n = minOf(wanted - done, pageLength[page] - inPage)
            if (n <= 0) break
            pages[page]!!.copyInto(into, intoOffset + done, inPage, inPage + n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    /** The cache slot holding page [index], loading it into the oldest slot if needed. */
    private fun page(file: PlatformFile, index: Long): Int {
        for (i in 0 until CACHE_PAGES) if (pageIndex[i] == index) return i
        val slot = nextVictim
        nextVictim = (nextVictim + 1) % CACHE_PAGES
        val buffer = pages[slot] ?: ByteArray(PAGE_SIZE).also { pages[slot] = it }
        val start = index * PAGE_SIZE
        pageIndex[slot] = -1
        pageLength[slot] = readFully(file, start, buffer, 0, minOf(PAGE_SIZE.toLong(), size - start).toInt())
        pageIndex[slot] = index
        return slot
    }

    /** Read until [length] bytes arrive or the file ends; return the count read. */
    private fun readFully(file: PlatformFile, offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        var done = 0
        while (done < length) {
            val n = try {
                file.readAt(offset + done, into, intoOffset + done, length - done)
            } catch (error: ArchiveException) {
                throw error
            } catch (error: Exception) {
                throw ArchiveIOException("read of $path at ${offset + done} failed: ${error.message}", error, offset + done)
            }
            if (n <= 0) break
            done += n
        }
        return done
    }

    /** Close the file. Later reads throw [ArchiveClosedException]; closing again does nothing. */
    @Throws(ArchiveException::class)
    override fun close() {
        val open = file ?: return
        file = null
        pages.fill(null)
        pageIndex.fill(-1)
        try {
            open.close()
        } catch (error: ArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ArchiveIOException("closing $path failed: ${error.message}", error)
        }
    }

    /** Describe the file and whether it is open. */
    override fun toString(): String = "FileRandomAccessSource(path=$path, size=$size, ${if (file == null) "closed" else "open"})"

    /** Cache geometry. */
    public companion object {
        /** Bytes per cached page. */
        public const val PAGE_SIZE: Int = 64 * 1024
        /** Pages kept; the oldest is replaced first. */
        public const val CACHE_PAGES: Int = 4
    }
}

/** One open, read-only file, as each platform provides it. */
internal interface PlatformFile {
    /** The file size, read once at opening. */
    val size: Long

    /** Read up to [length] bytes at [offset]; return the count, or 0 or less at the end. */
    fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int

    fun close()
}

/**
 * Open [path] read-only.
 *
 * @throws ArchiveIOException if it cannot be opened.
 * @throws ArchiveUnsupportedException where the platform has no file system.
 */
internal expect fun openPlatformFile(path: String): PlatformFile
