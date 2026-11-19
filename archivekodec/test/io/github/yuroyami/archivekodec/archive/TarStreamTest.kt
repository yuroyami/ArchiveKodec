package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.tar.TarStreamReader
import io.github.yuroyami.archivekodec.archive.tar.TarStreamWriter
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TAR written to a sink and read from a source, one entry at a time (#154, Stage 2). */
@OptIn(ExperimentalEncodingApi::class)
class TarStreamTest {
    private class Collector : ByteSink {
        val out = ByteArrayBuilder(1024)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.append(bytes, offset, length)
    }

    // GNU tar 1.35 (--format=pax and --format=gnu) and bsdtar 3.7 (--format=pax) of one tree:
    // a long directory name and a long file name past 100 bytes, a symbolic link, a hard
    // link, a UTF-8 name and 300 random bytes. Gzipped here.
    private fun fixture(b64: String) = Gzip.decompress(Base64.decode(b64), ArchiveLimits.UNBOUNDED)
    private val gnuPax = fixture(
        "H4sIAAAAAAACA+2aXWwUVRiGJ10ldjHBiFErW1nQhBg423Nm5szsJjRESYWiAS6MYlsTpjtnf3A6s8zO0jYGTQwIqT/xog2IpgTTmBQhmgYBa0" +
        "nUDZIG5aLREGhRjJFaf7gwTdRgjLNg0u3QulnCnKTs99zMZM/s7Ga+ed93vnMmUrdR61jLNJ3Z2bpsrlXwAeyiyPLVrYt3i4kkCoSKqqpgSZQl" +
        "AROCiSKEOwQO5LKOZrt/RahMJBxuc9JtrJ6oMYpjUVGlEYmoWBRplATdUe26URybGo3P/l0BmAO4kq/z+zcKGlcpnVX/hf3p+sdyQf+Up/5ty3" +
        "L+77hS43O4/kUJoCE9bbO4Y9mdqD3tpJCGbM1JMRsZlplEptbGkGOhTC6bQu7HKOMOonZmGO5e1kGWyVAqZ+o201Frp8OyKG0ip5T/Y4UIRFaI" +
        "Qtz7RCIClrDibsD/OSBOOTyRY2pMjMaCYszj61ESwZS6VaFRsPVbT/++iN5yNKOuDP+fpn8iyhT8f+7X/1qylPJ/SaHT87/gNDL4Pw8IpeFCPe" +
        "v9vRESaYP9d0oTsa05zTA6i06asGyUtCwdtTEtm7NZxOlwgjO3JrKqKFGsztyaFI/GZx8F2+ek/2tlL/n8h736FyUFC2EM/u87OmMZ0ENl67+o" +
        "/7M1U4+0pk2u83/X9X9ElFwbgPyH/g/goH9fRF+G/mX3Yd+jfyxSyH8efLy3+aWnG3/LJA8O/NzXPLqn78SO298IrU0uqAl8PbzwRO+hy9Vk+S" +
        "O9SseC8GOff3i03urs7lvSc+D93W/i9pYj3/Q2NPzQuu7Trt93bVlZkzg99LC4O3H4tLVl/djrn3VXbbzoTMxvebv7woC+7e6ek5uTx74f1Bqb" +
        "Gp58Z/DRt2r3bDhTfT8JXPnOWnnx+B9s4fae6kX3Nu1f/dAn/yTir+29L/TCL5lXBvU1kyH5rlPjgfMtNSfv/HHVgbNLl7x8+bnRZX/ui5756N" +
        "xt2wNPPfPXsfHQsn0j9wwfGW7a1T9y5dLO1Or5dyzdOjK2c2j/+NgT5w5fmvfuphWPBze/N/pq6NnJxeGqTecXVU8MaMt71z247Sf6xcHmv1dN" +
        "1h7PbKgaX/HAROCrs9Khxl/nnVr/7YWu/hcXN6tda76s3VEB+W+kzee563+G9T8F1v/4AE025L8vovfoX1XVMtb/JCKpQlhMMcOwCnNBkP+c/N" +
        "+XK34D/R9VYf0P+j+Ai/79ttnS87+KV/+iezj0fxy4WnsQdUXrvzj/NVu/6U5wA/kvY1j/hfwH+OS/H6IvK/897/8QmWIqhAmPhxPo/4r9Pz9k" +
        "5j+I54/q+f6bd81Lzv8R6qk/JYX1f/B/Dv4fnXr9x1P8IGRDZfi/L6Ivy/8lr/8rErz/w4X8EKgWAAAAAAAAAAAAAAAAAAAAAADgVuJf2P/C3Q" +
        "BQAAA=",
    )
    private val gnu = fixture(
        "H4sIAAAAAAACAysuTdJnoDEwAAJzU1MwDQToNJhtaGpkbm5mYGxkYsxgYGhgbGrGoGDKQAdQWlySWKSgwFCUn1+CTx0h+SEKioHxn6ibklmUml" +
        "ySX1SpW55ZkqGbqFuUWJKRWqSbk5+XrpuXmJuqW5KvW1BanKELFNYtAErqlqfm5ABZxSW6+XmpuhmleSlFqSm6SZUlqcW6mXlA9SWJOfokxL+J" +
        "maGZIVCdsSGDgbGhkYHpaPzTA+jp6+k7+ACj2SczL5uG+d/MxARn/BsZGaGkBQNDQ1NzcwYFn9H4H+L5Py0zJxVqZJ5uamFpYk5OJZKhaflFuu" +
        "n5+Sm6uamJxaVFqXolFcMxjEd6/BPK/wbAwh6l/jc2MjYyYFAwGM3/NAcpqakFXKPZYETn/6LEvBS9pMw8Wrb/8eV/E1MT1PafoaG5melo/qcH" +
        "2Dkjuj7M821B+qpNr5ZE356+ZG8La5+MRzq/JPOVk8J75615x2morTnPrIJfwenQhm22+ZVTlihOXbi6Y4JBecyWq/NcXR8neR3o+tSeZSOZdn" +
        "qPilFH2trT+Vl+d3oPTmEKeFDykjtm9pS7m1LKhKYeTUjf/nBXomeUq8+cXY4zZaf7n+OUMGT+fT/f5sGO76nCtVM5pcWi5jsr7/6XltwzQ1ym" +
        "+nVB264U9y8yJgLHnzPfipE8yvPUfuF1JcWmd7G31X/Msji39SZLLXNI+M/tz2XUZ10SObnlZFT7yku/n7VmOHNzKBVeutO6Z/7zO9431z5jWx" +
        "Sh48aVsOx2t0zkF3kFpohb0pwvNyVqz/OSK3themxV9B/7L7I7CvyZnutIvWQ+e914jecbtuN+9+52rayTjzbvcj8j2zJc838O7Zr+iP4fsEFP" +
        "fP/f2ADU/jfKALYx8mncKBxt/+vTOpgJt//M0Mp/IyMDk9Hynx4AHPejDcAR3f7LSCxKoWUpSzj/o43/GZqYmADbf4b0KJxGy3/9w3vyDq9PPr" +
        "wt5fBKmgQ04fg3Ro9/U/PR/j9dwOE9o4X/KBgFo2AUjIJRMApGwSgYBaNgFIwEAAAoTdCpACgAAA==",
    )
    private val bsdPax = fixture(
        "H4sIAAAAAAACA+2abWgURxzGj5yVJhYsVbSpl7ragohOMrM7s3sHBmkl1dii+VB8SVJwk517sXe7596eSSi2ULRK+kI/JGhtiVhCIVZRgq8xgv" +
        "WwEmz9EBTRRGspNU1b/SABLZbSvVJ6yVp7ObnZct48X/ZuZ4+73bnf88x/ZurU1hVU1ahZlUg2edgIQqgQIqSPtpxHCJEkCoiIiiJDScSSYJ8g" +
        "EvIIrR4XlExYqmn/FNMwrP+6Llu78+YKRBIUmq1IjFYjJUBgwC8qpFJCChRF4kdldqv6QCsMZFpjD/+sh6sAZFNfxfo7svGffjORfyRLskcgnH" +
        "/mqvvH/1WgRUzabBlmG2iJWGGgAlO1wtQEUUMPAV2NUWAZIJ5MhIF9GsTtRtBCo1H7VcIChk5BOKlrJtVAU5tFEyCi29dbk+l/GQkIy0hG9oUS" +
        "EqAkKTIpcP9nF6d5lRhw+L8fVUJCJCgTf5mYsX+EA0pA9Ae4rT9+/s+IfDValYP/T+BfxGn+uf+70/+ZDDBVXatsiuh5z38Z4xz83x4MIMjH/9" +
        "z/udzgnw31k+cfE+zg3w4A2/8h55+5TuxqeGdN7a14aF/vz90NQzu7T2194iPfitD0cu/FgRmnuvbfLkWLFnbJrdOFl88cOlpttHV0z+vc++WO" +
        "j2FL4+FLXTU1PzStPN1+Z/vGJeXB8/0vijuCB84bG1cNf/hVR0ndDWt0WuOnHdd6tc3PdJ7dEDr2fZ9aW1/z2md9L31SsXP1hdJnkff+d8aSG8" +
        "fv0hlbOkvnzKrfs+yFk38Emz/YNdv31i/x9/q05WM+/PS5Ee/VxvKzT/24dO/l+fPevf3G0IJ7u/0XjlyZssX7+trfjo34FuwenDlweKB+e8/g" +
        "/ZvbwsumPTl/0+Dwtv49I8OvXjlwc+rn6xa/Urbhi6H3fevH5gol667OKR3tVRd1rXx+80/k630Nvy8dqzgeX10ysvi5Ue+3l6X9tb9OPbfq+r" +
        "X2nrfnNijty7+p2Pq48p/J/2hEf5NN/a8oucz/YayIPP//v/k/rMiyHyr/Pv83vjX28FaerYXCPxvqJ8//g/N/WCKKRxDDdo1pVFqtFuffHf9n" +
        "88Afof7D6flf7v+8/uNizz9rm83KP5Sd9R+WMa//3NBffc+hLmr+x+W/amr5N4JHmf8lfP8Hz38ud/KfCfW55D908q+IxCMgNwYnvP4b5/+pfj" +
        "11sDl1VEv15PGRZ+t/hMjE/hchFAn3f1f83y+kl/Or0/8DR++X8WwoDv9nQ30u/i85/F+GBPL6zw2l+jm1xaxM9gcjUfr39i8d0E1JNRptG7cB" +
        "LGiYIGQYGohRNZE0aQ5OkTX/net/thcU/P4fphvr8nf7fP2vuMWO+lzynzj4xyi9/gM5/8z51yiNc1S5uLi4ik9/At8RWz0APAAA",
    )

    /** What a forward read gives: name, size, type flags, mode, time, link target and data. */
    private fun forward(tar: ByteArray, step: Int): List<String> {
        var position = 0
        val reader = TarStreamReader(ByteSource { into, offset, length ->
            if (position == tar.size) -1 else minOf(length, step, tar.size - position).also { n -> tar.copyInto(into, offset, position, position + n); position += n }
        })
        val out = ArrayList<String>()
        while (true) {
            val e = reader.next() ?: break
            val data = Collector().also { reader.read(it) }.out.takeByteArray()
            out += describe(e, data)
        }
        assertNull(reader.next())
        return out
    }

    private fun describe(e: ArchiveEntry, data: ByteArray) =
        "${e.name}|${e.size}|${e.isDirectory}|${e.isSymbolicLink}|${e.isHardLink}|${e.unixMode}|${e.lastModifiedEpochSeconds}|${e.linkTarget}|${data.contentHashCode()}"

    /** The same, from the seekable reader; links read as their target there, so data is compared for files only. */
    private fun seekable(tar: ByteArray): List<String> = TarReader(ByteArrayRandomAccessSource(tar)).use { reader ->
        reader.entries().map { e ->
            describe(e, if (e.isDirectory || e.isSymbolicLink || e.isHardLink) ByteArray(0) else reader.read(e))
        }.map { line -> line.replace(Regex("\\|[^|]*$"), "|") }
    }

    @Test
    fun forwardReadsAgreeWithTheSeekableReader() {
        for (tar in listOf(gnuPax, gnu, bsdPax)) {
            for (step in listOf(1, 511, 1 shl 16)) {
                val lines = forward(tar, step).map { it.replace(Regex("\\|[^|]*$"), "|") }
                // Hard and symbolic links carry no data forward; TarReader reports a hard link's target size.
                val expected = seekable(tar).map { line ->
                    val parts = line.split('|')
                    if (parts[4] == "true") parts.toMutableList().also { it[1] = "0" }.joinToString("|") else line
                }
                assertEquals(expected, lines, "step $step")
            }
        }
        val names = forward(gnuPax, 4096).map { it.substringBefore('|') }
        assertTrue(names.any { it.length > 100 && it.endsWith("good-measure.txt") }, "$names")
        assertTrue(names.any { it.endsWith("\u00fcn\u00efc\u00f6d\u00e9.txt") }, "$names")
    }

    @Test
    fun theStreamWriterWritesWhatTarWriterWrites() {
        val long = "d/" + "x".repeat(120) + "/" + "y".repeat(90) + ".txt"
        val files = listOf(
            TarWriter.FileSpec("top/", ByteArray(0)) { isDirectory = true; mode = 0x1ED },
            TarWriter.FileSpec("top/a.txt", "alpha".encodeToByteArray()) { modificationTime = 1714979289; mode = 0x1A0 },
            TarWriter.FileSpec("top/" + "p".repeat(120) + "/short.txt", ByteArray(700) { it.toByte() }),
            TarWriter.FileSpec(long, ByteArray(512) { 7 }),
        )
        val sink = Collector()
        TarStreamWriter(sink).apply {
            for (f in files) {
                if (f.isDirectory) putDirectory(f.name, f.mode, f.modificationTime)
                else putFile(f.name, f.data.size.toLong(), ByteSource.of(f.data), f.mode, f.modificationTime)
            }
            finish()
            finish()
        }
        assertContentEquals(TarWriter.write(files), sink.out.takeByteArray())
        assertFailsWith<ArchiveFormatException> {
            TarStreamWriter(Collector()).putFile("short.bin", 100, ByteSource.of(ByteArray(10)))
        }
    }

    @Test
    fun partialReadsSkipToTheNextEntry() {
        val tar = TarWriter.write(listOf(
            TarWriter.FileSpec("a.bin", ByteArray(5000) { 1 }),
            TarWriter.FileSpec("b.bin", ByteArray(3000) { 2 }),
            TarWriter.FileSpec("c.txt", "see".encodeToByteArray()),
        ))
        val reader = TarStreamReader(ByteSource.of(tar))
        assertEquals("a.bin", reader.next()!!.name)
        val head = ByteArray(10)
        assertEquals(10, reader.data().read(head, 0, 10))
        assertEquals("b.bin", reader.next()!!.name) // the rest of a.bin and b.bin's data are skipped
        assertEquals("c.txt", reader.next()!!.name)
        assertContentEquals("see".encodeToByteArray(), Collector().also { reader.read(it) }.out.takeByteArray())
        assertNull(reader.next())
        // A truncated archive fails where it ends.
        val cut = TarStreamReader(ByteSource.of(tar.copyOf(700)))
        cut.next()
        assertFailsWith<ArchiveFormatException> { cut.read(Collector()) }
    }

    @Test
    fun aSizePastUstarGetsAPaxRecordThatBothReadersUnderstand() {
        val size = (9L shl 30) + 3
        val records = TarWriter.headerRecords("huge.bin", false, size, 0x1A4, 0, 0, 0)
        val entry = TarStreamReader(ByteSource.of(records)).next()!!
        assertEquals("huge.bin", entry.name)
        assertEquals(size, entry.size)
    }

    @Test
    fun aTarGzStreamsThroughBothLayers() {
        var x = 3
        val files = (0 until 4).map { i -> "f$i.bin" to ByteArray(400_000 + i) { x = x * 1103515245 + 12345; (x ushr 24 and if (it % 1000 < 600) 0x0F else 0xFF).toByte() } }
        val gz = Collector()
        val session = Codecs.GZIP.encoder(gz)
        val tar = TarStreamWriter(ByteSink { b, o, n -> session.write(b, o, n) })
        for ((name, data) in files) tar.putFile(name, data.size.toLong(), ByteSource.of(data))
        tar.finish()
        session.finish()
        val compressed = gz.out.takeByteArray()
        // Read back through a gunzip that feeds the TAR reader as it decodes: a pipe of two sources.
        val decoded = Collector()
        Codecs.GZIP.decompress(ByteSource.of(compressed), decoded, ArchiveLimits.DEFAULT)
        val reader = TarStreamReader(ByteSource.of(decoded.out.takeByteArray()))
        for ((name, data) in files) {
            assertEquals(name, reader.next()!!.name)
            assertContentEquals(data, Collector().also { reader.read(it) }.out.takeByteArray())
        }
        assertNull(reader.next())
        // And the seekable reader agrees.
        ArchiveKodec.open(compressed).use { assertEquals(files.map { it.first }, it.entries().map { e -> e.name }) }
    }

    @Test
    fun sparseMembersAreRefusedForward() {
        val header = ByteArray(512)
        "s".encodeToByteArray().copyInto(header)
        "0000644\u0000".encodeToByteArray().copyInto(header, 100)
        "00000000000\u0000".encodeToByteArray().copyInto(header, 124)
        header[156] = 'S'.code.toByte()
        "ustar  \u0000".encodeToByteArray().copyInto(header, 257)
        header.fill(32, 148, 156)
        val sum = header.sumOf { it.toInt() and 0xFF }
        sum.toString(8).padStart(6, '0').encodeToByteArray().copyInto(header, 148)
        header[154] = 0
        assertFailsWith<ArchiveUnsupportedException> { TarStreamReader(ByteSource.of(header + ByteArray(1024))).next() }
    }

    /** What a push read gives, with the archive written in pieces of [step] bytes. */
    private fun pushed(tar: ByteArray, step: Int): List<String> {
        val out = ArrayList<String>()
        var current: ArchiveEntry? = null
        val data = ByteArrayBuilder(1024)
        fun close() { current?.let { out += describe(it, data.takeByteArray()) } }
        val sink = io.github.yuroyami.archivekodec.archive.tar.TarStreamSink(object : io.github.yuroyami.archivekodec.archive.tar.TarStreamVisitor {
            override fun onEntry(entry: ArchiveEntry) { close(); current = entry }
            override fun onData(entry: ArchiveEntry, bytes: ByteArray, offset: Int, length: Int) = data.append(bytes, offset, length)
        })
        var at = 0
        while (at < tar.size) { val n = minOf(step, tar.size - at); sink.write(tar, at, n); at += n }
        sink.finish()
        close()
        return out
    }

    @Test
    fun pushedBytesGiveWhatForwardReadsGive() {
        for (tar in listOf(gnuPax, gnu, bsdPax)) for (step in listOf(1, 100, 511, 513, 1 shl 16)) {
            assertEquals(forward(tar, 4096), pushed(tar, step), "step $step")
        }
        val cut = TarWriter.write(listOf(TarWriter.FileSpec("a.bin", ByteArray(5000))))
        assertFailsWith<ArchiveFormatException> { pushed(cut.copyOf(2000), 300) }
        assertFailsWith<ArchiveFormatException> { pushed(cut.copyOf(300), 300) }
    }

    @Test
    fun aGunzipFeedsTheTarSinkDirectly() {
        val files = (0 until 3).map { i -> "f$i.bin" to ByteArray(300_000) { ((it * 31 + i) % 251).toByte() } }
        val tar = TarWriter.write(files.map { TarWriter.FileSpec(it.first, it.second) })
        val seen = LinkedHashMap<String, ByteArrayBuilder>()
        val sink = io.github.yuroyami.archivekodec.archive.tar.TarStreamSink(object : io.github.yuroyami.archivekodec.archive.tar.TarStreamVisitor {
            override fun onEntry(entry: ArchiveEntry) { seen[entry.name] = ByteArrayBuilder(1024) }
            override fun onData(entry: ArchiveEntry, bytes: ByteArray, offset: Int, length: Int) = seen.getValue(entry.name).append(bytes, offset, length)
        })
        Codecs.GZIP.decompress(ByteSource.of(Codecs.GZIP.compress(tar)), sink, ArchiveLimits.DEFAULT)
        sink.finish()
        assertEquals(files.map { it.first }, seen.keys.toList())
        for ((name, data) in files) assertContentEquals(data, seen.getValue(name).takeByteArray())
    }
}
