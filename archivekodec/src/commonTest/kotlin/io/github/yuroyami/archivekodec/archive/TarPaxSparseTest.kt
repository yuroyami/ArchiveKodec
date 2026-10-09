package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** pax extended headers and sparse members, from GNU tar 1.35 and CPython tarfile (#35). */
@OptIn(ExperimentalEncodingApi::class)
class TarPaxSparseTest {
    private fun fixture(base64: String): ByteArray = Gzip.decompress(Base64.decode(base64))

    // tar -b1 --format=pax --mtime=@1700000000.75: a 308-character path and a UTF-8 path.
    private val pax = fixture(
        "H4sIAAAAAAACA+2VQW4CIRSGWc8pOMHwgAfMLNx36RWIYmyinWbmmc5dPEF7jZ6mtyhGXTipNaaW2gzfhoRHAsl7Hz+J+a8DEYvI" +
        "YM9wBauASTQKnQPc7WuJTjHeswRsOvJtfAobJ1pW/NnTckIJBuFrRPgjxOIHlNRToTRf0+M6TKQ7DnPpTKFq7g/btTRSW6xLq61T" +
        "oLHQwGcnRVOVVqpYrJwuUvef7sB/ADPwX0vrGIeU/rdNQ9+du1T/pyzDatWwzFghMfOL91cx9f1D8PPQduJj+7b73G53xyX/pdGn" +
        "/sdPUULO/xQoPMb/fg4OzT+XbDG8/Pnwuq9oy1zh/82lv8J/ABz6r0zO/yR0T81LtiCTyWTGxydmE0IDABQAAA==")

    // CPython tarfile, PAX_FORMAT with a global header carrying mtime=1234567890.5.
    private val global = fixture(
        "H4sIAAAAAAACA+3UvQ6CMBRA4c48BU9Q2lIoDiSOjr4CakNM+Em0Rh5f0cWFEWLgfMtt7nKnU5nIZH+shoOvLv4mZqG+pqZS2c97" +
        "3GulcyfiWizgcQ/V7X1ebJMxcRuurS+1SW2Wu2KnZBbpIj73beu7UNZNf6qaSGCVai3DEOa9MUadWzvdv0qFtpmxzin72bvxS4gV" +
        "/c+u7zwRbLl/86f9a/pfQnj2RAAAAAAAAAAAAAAAK/AC11HBSwAoAAA=")

    // A 1 MiB file with "hello" at 0x20000 and "world" in its last 5 bytes, in each sparse dialect.
    private val sparse = mapOf(
        "GNU S" to fixture(
            "H4sIAAAAAAACA+3QQQrCMBCF4TlKbmASp/YgnqBgwUUgkkS8vmkWiqJ1USqI/7eZYTIQ5pVNPg0pj7IiW+1UW62eqm+9085r31ud" +
        "5t52TsXs5QvOuQzJGEkxlrm9T++L0pnOvifibv22JvJqro9Jtn72A323dBxDiAIAAAAAAAAAAAAAAH7UJaZwIAbgf10BJeI80QAm" +
        "AAA="),
        "pax 0.0" to fixture(
            "H4sIAAAAAAACA+3TQU7DMBAFUK9zipwgHdsT21lkDSvEhgOE1hUVaY1iVxROj9NuqlABQlCp6n8bR/n5kRNr0uy+2936buGHOIsv" +
        "3RC9+GuUGeb9mk1XYk1Ccq3YWuLxvpRGaVHuxBlsY+qGvBVxnZQtb+4eqsPJV3H17ltJ7GprCmWOo812/diH+XNsdaHccRKWy+hT" +
        "K7UkqybZ2HpLPrZMTX5jc6pHzOzoV8XDPuuTRSo0lV1arfMX2UbWUhvFlbNS1blpx3T+KTVspXPGFtdy/um/hv7H86/215P5V1aJ" +
        "ks45/0MI6avnvssv1JPv+yAAAAAAAAAAAAAAAAAAAADgQr2GoV/gNwBcrw/IM7GLACoAAA=="),
        "pax 0.1" to fixture(
            "H4sIAAAAAAACA+3U30rDMBQG8Fz3KfoApc2f0yS76K16JYL4AHELWNba0WQ4fHrTKrIpzAvHYOz73aTtF9KQ09NYPbjdnXcrP4Yq" +
        "bNwYPDs1nmiieUx+jlxxyQTVkozhND0XQkvB8h07g22IbkxbYddJmvz2/qn8rHwZ2nffCE62NjqTej963fbP3bBch0Zl0h4krvdN" +
        "/Pp4slrtZ73bNEIJbmRBfKGLtDaR5d8304sKnimeu9imZYRZiFooLam0Rsg6TTFTuvyVajLCWm0yBv8Qq1Stx7lYN23nS2NJnvo/" +
        "cLz/5Xx92P+kOLGcn7P/x2GIx+b9lV+oF991A9oAAAAAAAAAAAAAAAAAAADgUr0NY7fCMQBcrw+xiogkACoAAA=="),
        "pax 1.0" to fixture(
            "H4sIAAAAAAACA+3U0UrDMBQG4FznKfoEbU5ymmQXvVWvRBAfILiAlW4dacXh05tWkTlh3szB3P/dpD0nhZLTv2N1F7Y3MSxjGqph" +
        "E9IQxbGpzDLPa7a/Ks1GENeanVM81YmsrkWxFSfwMowh5VcRl0nr4vr2ofyYfLkKz31qSO5V23WuKqn9bnUdVrEZP78ZaWi3l2Lo" +
        "hvYtNqTY185Ko4owtvkBcguqyVjNpXek67zBTd3HH13Ljry3Tgr4O2OVx3Y/T+2q7WLpPJtj/wcO51/TdP09/2yUFYU6Zf5T34+H" +
        "9v3WP1NGkiHltGS1sDKnkdmrr5s5uwoJ/L+eYtf1OAYAAAAAAAAAAAAAAAAAAIBz9dqnboljALhc7+7kNB0ALAAA"),
    )

    private fun reader(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT) =
        TarReader(ByteArrayRandomAccessSource(bytes), limits)

    @Test
    fun paxPathAndMtimeOverrideTheUstarHeader() {
        val longName = "t/" + "d".repeat(120) + "/" + "e".repeat(120) + "/" + "f".repeat(60) + ".txt"
        reader(pax).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf(longName, "t/café/雪.txt"), entries.map { it.name })
            assertEquals(308, entries[0].name.length)
            assertContentEquals(longName.encodeToByteArray(), entries[0].nameBytes)
            assertEquals(listOf(1_700_000_000L, 1_700_000_000L), entries.map { it.lastModifiedEpochSeconds })
            assertEquals("hello", reader.read(entries[0]).decodeToString())
            assertEquals("snow", reader.read(entries[1]).decodeToString())
        }
    }

    @Test
    fun globalRecordsApplyToEveryLaterMember() {
        reader(global).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("g1.txt", "g2.txt"), entries.map { it.name })
            assertEquals(listOf(1_234_567_890L, 1_234_567_890L), entries.map { it.lastModifiedEpochSeconds })
            assertEquals(listOf("one", "two"), entries.map { reader.read(it).decodeToString() })
        }
    }

    @Test
    fun sparseMembersExpandWithZeroFilledHoles() {
        val expected = ByteArray(1 shl 20).also {
            "hello".encodeToByteArray().copyInto(it, 0x20000)
            "world".encodeToByteArray().copyInto(it, (1 shl 20) - 5)
        }
        for ((dialect, bytes) in sparse) {
            reader(bytes).use { reader ->
                val entry = reader.entries().single()
                assertEquals("t/sparse", entry.name, dialect)
                assertEquals(1L shl 20, entry.size, dialect)
                assertEquals(8192L, entry.compressedSize, dialect)
                assertEquals(1_700_000_000L, entry.lastModifiedEpochSeconds, dialect)
                assertContentEquals(expected, reader.read(entry), dialect)
                // Holes count toward the output limit, so a tight limit refuses the expansion.
                assertFailsWith<DecompressionBombException>(dialect) {
                    reader.read(entry, ArchiveLimits { maxUncompressedBytes = 1000 })
                }
                // The ratio limit sees only stored bytes, so a 128:1 sparse file passes a 2:1 ratio.
                assertContentEquals(expected, reader.read(entry, ArchiveLimits { maxCompressionRatio = 2 }), dialect)
            }
        }
    }

    /** A ustar member preceded by an `x` header with [records]. */
    private fun withPax(records: String, name: String = "a.txt", sizeField: Int, data: ByteArray): ByteArray =
        header("PaxHeaders/a", 'x', records.encodeToByteArray().size) + pad(records.encodeToByteArray()) +
            header(name, '0', sizeField) + pad(data) + ByteArray(1024)

    private fun record(key: String, value: String): String {
        val body = " $key=$value\n"
        var length = body.length + 1
        while ((length.toString() + body).encodeToByteArray().size != length) length++
        return length.toString() + body
    }

    private fun pad(data: ByteArray) = data + ByteArray((512 - data.size % 512) % 512)

    private fun header(name: String, type: Char, size: Int): ByteArray = ByteArray(512).also { h ->
        fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(h, offset)
        put(0, name); put(100, "0000644"); put(124, size.toString(8).padStart(11, '0'))
        put(136, "00000000000"); h[156] = type.code.toByte(); put(257, "ustar"); put(263, "00")
        put(148, "        ")
        put(148, h.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0') + "\u0000 ")
    }

    @Test
    fun paxSizeOverridesTheOctalField() {
        val bytes = withPax(record("size", "5"), sizeField = 0, data = "hello".encodeToByteArray())
        reader(bytes).use { reader ->
            val entry = reader.entries().single()
            assertEquals(5L, entry.size)
            assertEquals("hello", reader.read(entry).decodeToString())
        }
    }

    @Test
    fun localRecordsApplyToTheNextMemberOnly() {
        val first = withPax(record("path", "renamed.txt"), sizeField = 1, data = "1".encodeToByteArray()).copyOf(4 * 512)
        val bytes = first + header("b.txt", '0', 1) + pad("2".encodeToByteArray()) + ByteArray(1024)
        reader(bytes).use { reader ->
            assertEquals(listOf("renamed.txt", "b.txt"), reader.entries().map { it.name })
        }
    }

    @Test
    fun malformedPaxRecordsAreRejected() {
        for (records in listOf("8 path=a\n", "x path=a\n", "12 pathname\n", "6 =ab\n", "99 path=a\n")) {
            assertFailsWith<ArchiveFormatException>(records) {
                reader(withPax(records, sizeField = 1, data = "1".encodeToByteArray())).entries()
            }
        }
        for ((key, value) in listOf("size" to "-1", "size" to "1e3", "mtime" to "soon", "size" to "99999999999999999999")) {
            assertFailsWith<ArchiveFormatException>("$key=$value") {
                reader(withPax(record(key, value), sizeField = 1, data = "1".encodeToByteArray())).entries()
            }
        }
        val invalidUtf8 = header("PaxHeaders/a", 'x', 12) + pad("12 path=\u00ff\n".encodeToByteArray().let { it.copyOf(12).also { b -> b[8] = 0xFF.toByte() } }) +
            header("a.txt", '0', 0) + ByteArray(1024)
        assertFailsWith<ArchiveFormatException> { reader(invalidUtf8).entries() }
    }

    @Test
    fun negativeAndFractionalTimesRoundDown() {
        for ((value, expected) in listOf("12.9" to 12L, "-12" to -12L, "-12.5" to -13L, "-12.000" to -12L)) {
            val bytes = withPax(record("mtime", value), sizeField = 0, data = byteArrayOf())
            reader(bytes).use { assertEquals(expected, it.entries().single().lastModifiedEpochSeconds, value) }
        }
    }

    @Test
    fun inconsistentSparseMapsAreRejected() {
        fun sparse01(map: String, size: String = "100", stored: Int = 4) = withPax(
            record("GNU.sparse.size", size) + record("GNU.sparse.map", map), sizeField = stored, data = ByteArray(stored))
        for (map in listOf("0,2,1,2", "99,4", "0,2", "0,4,5", "a,4", "0,-4")) {
            assertFailsWith<ArchiveFormatException>(map) { reader(sparse01(map)).entries() }
        }
        reader(sparse01("10,2,96,2")).use { reader ->
            val entry = reader.entries().single()
            assertEquals(100L, entry.size)
            assertEquals(4L, entry.compressedSize)
            assertContentEquals(ByteArray(100), reader.read(entry))
        }
        val unknown = withPax(record("GNU.sparse.major", "2") + record("GNU.sparse.minor", "0"), sizeField = 0, data = byteArrayOf())
        assertFailsWith<ArchiveUnsupportedException> { reader(unknown).entries() }
        // A 1.0 map claiming more regions than its bytes could describe fails before allocation.
        val claims = withPax(record("GNU.sparse.major", "1") + record("GNU.sparse.minor", "0") +
            record("GNU.sparse.realsize", "10"), sizeField = 512, data = "999999999\n".encodeToByteArray().copyOf(512))
        assertFailsWith<ArchiveFormatException> { reader(claims).entries() }
    }

    @Test
    fun oversizedPaxHeadersAreRefusedBeforeReading() {
        val huge = 17 * 1024 * 1024
        val bytes = header("PaxHeaders/a", 'x', huge)
        val source = object : RandomAccessSource {
            override val size: Long = 512L + huge + 1024
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (offset >= 512) error("extended header data must not be read")
                bytes.copyInto(into, intoOffset, offset.toInt(), offset.toInt() + length)
                return length
            }
        }
        assertFailsWith<ArchiveUnsupportedException> { TarReader(source).entries() }
    }
}
