package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioStreamReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.tar.TarStreamReader
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.Codecs
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Owner, time, device and xattr metadata, as independent writers store it. */
class EntryMetadataModelTest {
    // Python 3.12 tarfile, PAX_FORMAT, gzipped: notes.txt with fractional mtime, atime, ctime,
    // uid 1000/gid 100, alice/users and a SCHILY.xattr.user.comment; dev/null, a character
    // device 1:3; pipe, a FIFO; old.txt with pax mtime -1.25.
    private val pax = Codecs.GZIP.decompress(Base64.decode(
        "H4sIAAAAAAAC/+3Wy2qEMBQG4Kx9irxAY65mNVDoZgpdFLrqUjQwgpdBY/Hxm6oMwjClFJS5/N8mEiJZnJzkZzGLn9/TYe/S3LVkFXxyaeTCqNP3OC+4" +
        "FJrQgWyg73zahu3JY5KSpr6o3E7YqRacMxMpTrPlrAyzp2pFStKPl/3r2ycbUu9b1neuZVlTVa72u0MRSUWr5d8BkyYicH3qxruO+cGvuMfPAUj02O/C" +
        "mqnvhdaLnk+I0EZqa7ke+19YmxDKt+z/tCwy9+s613b3V/+DK8sGrfmwcvcV131ZkrX7P0kuvv9n3yIsF4SqW3r/58dxHtWt1P9YHN3ae0z3v/57/UP5" +
        "Zah/gvy3OnYF+T+kgvP8r5D/tyDMnNWfBDL6A2rKfOX0/5/730ojN87/OAoAAAAAAAAAAAAAcEe+AWQdoJIAKAAA"))

    // GNU cpio 2.15: `echo f | cpio -o -H newc --owner 1234:567`, gzipped; f holds "x", mode 0640.
    private val cpio = Codecs.GZIP.decompress(Base64.decode(
        "H4sIAAAAAAAAAzMwNzA3MDQwMDS0MDQxNgACC0NHAzAwcTEC00bG5hABA0MzJyDX2dkSxodQbq4G+IERjJHGUMHAwGAAtZMoQKw6dOAEY4QEOXr6uAYp" +
        "KioyjAIMAABZdaZxAAIAAA=="))

    private fun streamed(bytes: ByteArray): List<ArchiveEntry> {
        val reader = TarStreamReader(ByteSource.of(bytes))
        return generateSequence { reader.next() }.toList()
    }

    @Test
    fun paxTimesOwnersXattrsAndDevicesReadTheSameBothWays() {
        val listed = TarReader(ByteArrayRandomAccessSource(pax)).use { it.entries() }
        assertEquals(listed, streamed(pax))
        val (notes, device, pipe, old) = listed
        assertEquals(ArchiveTime(1_700_000_000, 250_000_000), notes.lastModified)
        assertEquals(1_700_000_000L, notes.lastModifiedEpochSeconds)
        assertEquals(ArchiveTime(1_700_000_100, 500_000_000), notes.lastAccessed)
        assertEquals(ArchiveTime(1_700_000_200, 1), notes.statusChanged)
        assertNull(notes.created)
        assertEquals(1000L, notes.uid)
        assertEquals(100L, notes.gid)
        assertEquals("alice", notes.ownerName)
        assertEquals("users", notes.groupName)
        assertEquals(setOf("user.comment"), notes.xattrs.keys)
        assertContentEquals("hi".encodeToByteArray(), notes.xattrs["user.comment"])
        assertEquals(EntryType.FILE, notes.type)

        assertEquals(EntryType.CHARACTER_DEVICE, device.type)
        assertEquals(1L to 3L, device.deviceMajor to device.deviceMinor)
        assertEquals(0x21B6, device.unixMode)
        assertEquals(EntryType.FIFO, pipe.type)
        assertNull(pipe.deviceMajor)
        // pax -1.25 is 1.25 s before the epoch: floor seconds -2, plus 0.75.
        assertEquals(ArchiveTime(-2, 750_000_000), old.lastModified)
        assertEquals("-2.75", old.lastModified.toString())
    }

    @Test
    fun cpioOwnersReadTheSameBothWays() {
        val listed = CpioReader(ByteArrayRandomAccessSource(cpio)).use { it.entries() }
        val stream = CpioStreamReader(ByteSource.of(cpio))
        assertEquals(listed, generateSequence { stream.next() }.toList())
        val f = listed.single()
        assertEquals(1234L to 567L, f.uid to f.gid)
        assertEquals(EntryType.FILE, f.type)
        assertNull(f.deviceMajor)
    }

    @Test
    fun timesConvertAndCompare() {
        // 1601-01-01 is FILETIME zero; one 100 ns tick after the Unix epoch.
        assertEquals(ArchiveTime(-11_644_473_600), ArchiveTime.fromFileTime(0))
        assertEquals(ArchiveTime(0, 100), ArchiveTime.fromFileTime(116_444_736_000_000_001))
        assertTrue(ArchiveTime(0, 1) > ArchiveTime(0) && ArchiveTime(-1, 999_999_999) < ArchiveTime(0))
        assertEquals("1700000000.25", ArchiveTime(1_700_000_000, 250_000_000).toString())
        assertFailsWith<IllegalArgumentException> { ArchiveTime(0, 1_000_000_000) }
    }

    @Test
    fun theNewMetadataTakesPartInEqualityAndCopies() {
        val base = ArchiveEntry("a", 1, false) { uid = 1; xattrs = mapOf("user.k" to byteArrayOf(1)) }
        val same = ArchiveEntry("a", 1, false) { uid = 1; xattrs = mapOf("user.k" to byteArrayOf(1)) }
        assertEquals(base, same)
        assertEquals(base.hashCode(), same.hashCode())
        assertTrue(base != base.copy { xattrs = mapOf("user.k" to byteArrayOf(2)) })
        assertTrue(base != base.copy { windowsAttributes = 0x20 })
        assertEquals(base, base.copy())
        // The entry keeps its own copy: changing the returned bytes changes nothing.
        base.xattrs.getValue("user.k")[0] = 9
        assertEquals(1, base.xattrs.getValue("user.k")[0].toInt())
        assertEquals(ArchiveTime(5), ArchiveEntry("b", 0, false) { lastModifiedEpochSeconds = 5 }.lastModified)
    }

    // Info-ZIP 3.0 on Linux: a.txt, modified 2023-11-14T22:13:20.25Z, owned by root; extended
    // timestamp (0x5455) and Unix ids (0x7875).
    private val infoZip = Base64.decode("UEsDBAoAAAAAAKqxblej/yqtBAAAAAQAAAAFABwAYS50eHRVVAkAAwDxU2UA8VNldXgLAAEEAAAAAAQAAAAAemlwClBLAQIeAwoAAAAAAKqxblej/yqtBAAAAAQAAAAFABgAAAAAAAEAAACkgQAAAABhLnR4dFVUBQADAPFTZXV4CwABBAAAAAAEAAAAAFBLBQYAAAAAAQABAEsAAABDAAAAAAA=")

    // 7-Zip 23.01 `-tzip -mtc=on`: the same file with NTFS times (0x000a) at 100 ns.
    private val sevenZipZip = Base64.decode("UEsDBAoAAAAAAKuxblej/yqtBAAAAAQAAAAFAAAAYS50eHR6aXAKUEsBAj8DCgAAAAAAq7FuV6P/Kq0EAAAABAAAAAUAJAAAAAAAAAAggKSBAAAAAGEudHh0CgAgAAAAAAABABgAoCWTxkcX2gEAAAAAAAAAAJ+BlKfheN0BUEsFBgAAAAABAAEAVwAAACcAAAAAAA==")

    @Test
    fun zipReadsNtfsTimesExtendedTimeAndUnixIds() {
        val info = io.github.yuroyami.archivekodec.archive.zip.ZipReader(ByteArrayRandomAccessSource(infoZip)).use { it.entries().single() }
        // The extended timestamp holds whole seconds.
        assertEquals(ArchiveTime(1_700_000_000), info.lastModified)
        assertEquals(0L to 0L, info.uid to info.gid)
        assertNull(info.windowsAttributes)
        val ntfs = io.github.yuroyami.archivekodec.archive.zip.ZipReader(ByteArrayRandomAccessSource(sevenZipZip)).use { it.entries().single() }
        assertEquals(ArchiveTime(1_700_000_000, 250_000_000), ntfs.lastModified)
        assertTrue(ntfs.lastAccessed != null && ntfs.created != null)
    }
}
