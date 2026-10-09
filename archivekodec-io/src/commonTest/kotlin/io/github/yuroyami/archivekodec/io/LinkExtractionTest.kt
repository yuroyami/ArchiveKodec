package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.security.ArchivePathException
import io.github.yuroyami.archivekodec.security.PathGuard
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #27's check: a link to `../../..` and a file "inside" it cannot write outside the output folder. */
class LinkExtractionTest {
    private fun record(name: String, type: Char, data: ByteArray = ByteArray(0), link: String = ""): ByteArray {
        val header = ByteArray(512)
        fun put(at: Int, text: String) = text.encodeToByteArray().copyInto(header, at)
        put(0, name); put(100, "0000644\u0000"); put(108, "0000000\u0000"); put(116, "0000000\u0000")
        put(124, data.size.toString(8).padStart(11, '0') + "\u0000"); put(136, "14612345670\u0000")
        header[156] = type.code.toByte(); put(157, link); put(257, "ustar\u0000"); put(263, "00")
        "        ".encodeToByteArray().copyInto(header, 148)
        put(148, header.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0') + "\u0000 ")
        return header + data + ByteArray((512 - data.size % 512) % 512)
    }

    private val attack = record("link", '2', link = "../../..") + record("link/payload", '0', "escaped".encodeToByteArray()) +
        record("ok.txt", '0', "fine".encodeToByteArray()) + record("same", '1', link = "ok.txt") + ByteArray(1024)

    @Test
    fun theLinkAttackStaysInsideTheOutputFolder() = runTest {
        val base = Path(SystemTemporaryDirectory, "archivekodec-links-${Random.nextLong().toULong()}")
        val out = Path(base, "a", "b", "out")
        try {
            SystemFileSystem.createDirectories(out)
        } catch (_: Throwable) {
            return@runTest // no writable file system on this platform
        }
        try {
            ArchiveKodec.open(attack).use { reader ->
                reader.extract(reader.entries()) { entry ->
                    val target = Path(out, PathGuard.sanitize(entry.name))
                    target.parent?.let { SystemFileSystem.createDirectories(it) }
                    SystemFileSystem.sink(target)
                }
            }
            // The symbolic link was skipped, so "link" became a directory inside the output folder.
            assertFalse(SystemFileSystem.exists(Path(base, "payload")))
            assertFalse(SystemFileSystem.exists(Path(base, "a", "payload")))
            assertTrue(SystemFileSystem.metadataOrNull(Path(out, "link"))?.isDirectory == true)
            assertContentEquals("escaped".encodeToByteArray(), SystemFileSystem.source(Path(out, "link", "payload")).buffered().readByteArray())
            // The hard link became an ordinary file with the data it names.
            assertContentEquals("fine".encodeToByteArray(), SystemFileSystem.source(Path(out, "same")).buffered().readByteArray())
        } finally {
            fun delete(path: Path) {
                if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach { delete(it) }
                SystemFileSystem.delete(path, mustExist = false)
            }
            delete(base)
        }
    }

    @Test
    fun linksReachTheCallbackWithTheirTargetsOnlyWhenAskedFor() = runTest {
        ArchiveKodec.open(attack).use { reader ->
            val links = ArrayList<Pair<String, String>>()
            val files = ArrayList<String>()
            reader.extract(reader.entries(), onLink = { entry, target -> links += entry.name to target }) { entry ->
                files += entry.name
                object : RawSink {
                    override fun write(source: Buffer, byteCount: Long) = source.skip(byteCount)
                    override fun flush() {}
                    override fun close() {}
                }
            }
            assertEquals(listOf("link" to "../../.."), links)
            assertEquals(listOf("link/payload", "ok.txt", "same"), files)
            // The callback's job: refuse a link that leaves the root.
            assertFailsWith<ArchivePathException> { PathGuard.resolveLink(links[0].first, links[0].second) }
        }
    }
}
