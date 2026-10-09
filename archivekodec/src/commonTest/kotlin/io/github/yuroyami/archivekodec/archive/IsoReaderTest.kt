package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.iso.IsoReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ISO 9660 (#31). Images from genisoimage 1.1.11 (-r, -J -D, -D, -J -r) and xorriso 1.5.6
 * (-as mkisofs -r -J), gzipped because they are mostly zeros. The tree: README.txt,
 * "Mixed Case Name.txt", big.bin (5000 random bytes), empty/, sub/file.txt ("hello",
 * modified 2024-05-06T07:08:09Z), a/b/c/d/e/f/g/h/i/deep.txt (nine levels, so genisoimage's
 * Rock Ridge relocates h/ into rr_moved/) and link -> sub/file.txt.
 */
@OptIn(ExperimentalEncodingApi::class)
class IsoReaderTest {
    private val rockGz = Base64.decode(
        "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",
    )
    private val jolietGz = Base64.decode(
        "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" +
        "5Y4yTmWuWHsyeNIIFjtQ4raKWGIKe1zJr0sm3vFqDZudcfHM4IkLb3TJa7DXj9gvn3XuSZm8fgkze/qeiuBLHZX09ZvUm0J+N1iRppOtZ9Q83v+8g/D1mpgH" +
        "4W8vBbpmPs5xKVzYnnbgsOPDpt1JwnnMskIid+bUrjHzdZflxxkanE8zEb206EXxmxj5LLu2nx5oqsRfGLbt+QChyVvHpEgtTO//0tvds9n30CEV6cb6vLMv" +
        "RI5Kn+zy+VI76uWgJqNZJafMkl3lqsXCpW4YHdYKXphQOji9YYbxjvlOgnPjTh0MjWbEPhI9ZjXlpv/stMkbQs0/khrjCef91RrilhuMNBbUyQlbpFMorCq/" +
        "SSdmvr7kdcGw2W7hIoePSFiGiYUsEHry4Z6ZpUxQAlPba5uC71OlDRK1ZfY2HdWt8RdevnecP241S/iSd0GXr9vwgftnfRy2fiuRFd4/vL/OItEkCebOxY88" +
        "jR59OpXoU5P4zO/1TyLTnKs2FbwJHvbGQf9mhEJK0Eqv9P3nXoTp1p+8VR5Y9zpz0nP9yTXTo0fHnCtN8mVd0Uk5VrXp2c8lQW+TxTR1fq4SvBI//PShfbtk" +
        "3CzyrtcUDbyz+faAOfEPm8uLvb74kyEGodXvnsl6Z7vIz5G+ssPY8uCAds1XxWo5nQLyozkfjM1url4SmXLuyUa36nvp55nxetukxa7WyucJNX4YYdT6wGqo" +
        "XHyo+dVr+7fIFSWf3nO71Dds467Z5+Tt61o3xFyaNkohgzbXsaxIysfXILlqyPPMAyemzDhfPHhTqsk4v/ouSS+LqOih403dtStfjjpuevtO54nNc18eSW+I" +
        "9H/DfLztbXjSg7Md4fO+5LaI2Le96q//QEb4mIbDolTJ7fPEDCqMbeZHifrGnzSsPM9Rk41xfBIRIzVKM/mXi6V2n6a2194pDGjVinJ5PTdh2ZcMs8nvGjgc" +
        "4bkB9yfVXnOsHPuydqlAP+WCNI10Qe23A8iojjO/xU9s/1A9uT1TZL6+ZT+bqMfedRIbV1yUmeNiPW5vzcLI4xO8xYVniuZ37dV+ero2a9w7lYrT1y9ELK66" +
        "5p2pYttfR7x/cpHkw0uP4+KX7j71oulS+8Yl0/1TC3QcGEMOqH6uWykbfnZiTKxtjdUcu7Y0zbWuqktFI5Jvjrka9zJnM1vYbW3eSoVkum31z1VPWbKz9kgF" +
        "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",
    )
    private val plainGz = Base64.decode(
        "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",
    )
    private val bothGz = Base64.decode(
        "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" +
        "AAAAAAAAAAAAAAAAAMD/c/8DXXjgEwCYBgA=",
    )
    private val xorrisoGz = Base64.decode(
        "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" +
        "Pr0O7XfCuDLlIJ8lo/Z1eJgmLa/t3qsd+JeOlcM5eSWvtnsb8+0fnnmQarCkWOXIIRVJjeZV9mnq+qullB+sOhIrvemNbZyFRFjAgzz/wVL130Qmlqmvq28M" +
        "NXOrj5xXv801ZG2uc4HYp9hfQhN2xgSKBcvkW85Z9H2f8tkXGhq3HvaaWI9d+7to3YJ62xbx/qM/fXebpZR5OzzQoSjsodjgthy/lqz9hWOV3twdd05Ou3a8" +
        "mVy27dDK+VL5/sNVIl5/uqG7+mpAUf9xQalN7hfx51pXYk5/Fde7neI36Uq9Q0Bk1ObzwVOO33xXKXh+0lx20Z1Zse22/o83GEZ15sasslhjX1pyX32Fi8yc" +
        "D+tvCg4Hefb1RPhW671XFDqIphU4+3ePysrcFp1dk77k+Oud+6KKdtyvLdSqLx9cKd9wWeZJWFmAx7VZ2o2m16b0xHceMhUZ0PykLewxCLqQMNFi3VmtJ5Hn" +
        "FUVW+wt2pI18W3FqVdkKTeG25IRdVk3fI0PsDMdoHxju/CpGxr5aVHN4iNpqBfUG56Rqw5C8iFMxo8VKYhfmDK6alrg83sjheVmVQ5LrjCCf81c8R21/7/lM" +
        "4uPp3HvHx357/7OXpbOK2OzeykqFTUUjPS63eN8xuiRb7zei2/Tzujk+necdXm0ulPiwKDfNqS9iUfF/R9+6PxyXH1Nb6Nx6S2RxllqYqdbUg80h/b13WqNT" +
        "qpsDWltnldR4PjUecLuT6D5Ox/WM0vzm29mH1tZUOJkInnXkZp92PR1/30810z5SdvmPfpvW9DuVZje1tAr+qPY4U6WbdV0vapHLyxTTrUZZ7SUTvxdN2JP4" +
        "pXvI2ZakzY0Nw75U1JyITe6ysjruNKlo72Tjtx81Hg2ElN6+sN5kr+HMnKaUu2V3QuW0qhzFfyRcCGseHefl+1r5hLv15icCx1NtyRubZLd/1ZK5uMT/XoGy" +
        "aax0k0Hq7w5mdzJqjJJyhG9tXmySGpD9migyfd4J/TMVStqO6vtK5dU9+sODJNrHV9nMdytv3/JggdJI+avS8dcFF542zJsdMEf+soNMa1j4WdVpBvqZYWae" +
        "ry+Mbn256MTK6guOr6QzNNSExyW3TrhXZtCi1KRRNyXuS4t1rm7cfe9Pkivk/Y3CtxlM9PzaeEhYmP5mpn5266ue6J8VJ/1a+eL1m8rvmyTT1ys/NYj1elE0" +
        "S172Q8jQP79qGNhUHanq/GIb/C53m4Xs006lylan1MTREl1O1sOlNU0mSj43v5R3fCD4pGjjx2qva23rpZbYqdbvd9TJKpS2KUtaGVssdFPdYCx3OVl3x/mI" +
        "vnEbLHtljjV/vrl3f4n1RSOtBZE3kty3fzl7qtPi4R9/dk9TvXpJmN2VIGKosftbkMutq1tSH3sU+e+JF/zw3OdW0SMYe606/ViR9Sulse/61h/1NsnbvlJh" +
        "cFFQ3cFrR1/8NvOlUrJb/k7dPP+VErdakr8vzym3kJma+nrujREjQjdHbTj6yPX5B98ycdXboq49LzcHjl+s+fTKRc1Mlwk5Bx07z7ztfl9xS2gvlLRamyGV" +
        "9SzFbMcvJSI6rtfy+9uHv1ry+YeonFGVTVKIdOhJD1HXhiGCwL/62uKmnPM0UvW7Ha4gLfNC2ahpiNFlG+utFQdGbtbvlrWbEHLm+ZXFbhp1dbXW6m/+rnZy" +
        "ibNtV+xs1gyQs6vTdNAf19wzOfiA4MT2KItwRY8xWuOrZdqaEwyXWL/N1NZQ/yYsqC1P3DvhSv9z1Xl2pwZsKj7cNpA916ixMV9scYXZRyspl6B/2tfZxgjW" +
        "PDjkf3CwvUmxac3VfK3cSyZJn37TP7jV8tq8P972Of+pFPJNOWJXzuw12TOkbEc/PbpM5K2BYF2biWZ6QM6kgRnWUT+cD8dE6uVatdh4t+WNrdiyqLE/UcX4" +
        "4OADpfqpe4aE+Zs02ZjPX76xslC2fcjEbB/58uju6dfTZzbsDjRc+ssJnyt9o9wNxVxrd8ctMz/7t7FaR53jkAWFYzUmlesdeSW7RSxgU8/lvqjSEMNxq0t3" +
        "Z5jssZfUTTaML36Snro1Pfj0sYxLdySKnu3afsluVs+a2/IO1ZJL/Pb5W7scVhx6aL/xtLufoxUsAmpWbN0ht8dsxU9OPss69bsGsuxUWwxDdOJcxmSIS8Tt" +
        "SPM2z06uue6Rm+66dF1Q3qmUn+r2eIcrzk42mlFt7TBGa9A8POruRhOHv22u6onuHpIWKG+dmmVZ2+/bpTQ5KVfFvmaCRsvkzmPlN08l98+Nm5uz7UxdU09T" +
        "mKBj/vnYVSmWjqeelzU4XjoUk5lbrOXyeO2k5R2dwnfLCuZ6pa3d/ip3m+LYFnGf8/6BhQ1H9gY4Wfz1q9OwYmO1ms6JUvJSDeYugdHbU97lF6wSvVcn8rxe" +
        "xypJGCR4V5zguij9kc3Gp073H2tn3N75+aJB49zwjxW9upMH70t/zDcteBC08mz5lIjq4DYVrzOuV361MBgZNu6NieygYZFrm0RxwJTa9d1/n2/MG5CqmpC3" +
        "627wu5M+tsPSrCaKH1kqTHVa9snookK804vIvNSXn1tkQowPyLp+jDkZcFb+SZ+IVYeN7QuhtefhoqkCZ7OjRs9uRAdNUI1wfHLQyKUmVe7ZwQynRzqCWy/v" +
        "dc2NVpXolrvLEwngf81nc7CHOxkAAPhX2erh5u7jMWmDn2+Qh2/QCIIAAPAvsMnD29uPDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACA/3P/ATedlmYAcAYA",
    )

    private fun image(gz: ByteArray) = Gzip.decompress(gz, ArchiveLimits.UNBOUNDED)

    private fun listing(gz: ByteArray): Map<String, ArchiveEntry> =
        ArchiveKodec.open(image(gz)).use { reader -> reader.entries().associateBy { it.name } }

    private fun read(gz: ByteArray, name: String): ByteArray =
        ArchiveKodec.open(image(gz)).use { reader -> reader.read(reader.entries().single { it.name == name }) }

    @Test
    fun sniffsAtByte32769() {
        for (gz in listOf(rockGz, jolietGz, plainGz, bothGz, xorrisoGz)) {
            val iso = image(gz)
            assertEquals(ArchiveFormat.ISO9660, Magic.sniff(ByteArrayRandomAccessSource(iso)))
            assertEquals(ArchiveFormat.ISO9660, Magic.sniff(iso.copyOf(32774)))
            ArchiveKodec.open(iso).use { assertEquals(ArchiveFormat.ISO9660, it.format) }
        }
        // Without the descriptor, the zero system area is a TAR end record, as before.
        assertEquals(ArchiveFormat.TAR, Magic.sniff(ByteArray(1024)))
    }

    @Test
    fun rockRidgeNamesModesTimesLinksAndRelocatedDirectories() {
        for (gz in listOf(rockGz, bothGz, xorrisoGz)) {
            val entries = listing(gz)
            for (name in listOf("README.txt", "Mixed Case Name.txt", "big.bin", "empty/", "sub/", "sub/file.txt", "a/b/c/d/e/f/g/h/i/deep.txt", "link")) {
                assertTrue(name in entries, "$name missing from ${entries.keys}")
            }
            val file = entries.getValue("sub/file.txt")
            assertEquals(5L, file.size)
            assertEquals(1714979289L, file.lastModifiedEpochSeconds)
            assertEquals(0b1000_000_000_000_000 or 0b100_100_100, file.unixMode, "regular file, r--r--r-- or wider")
            assertTrue(entries.getValue("sub/").isDirectory)
            val link = entries.getValue("link")
            assertEquals(0b1010_000_000_000_000, link.unixMode!! and 0b1111_000_000_000_000)
            assertContentEquals("hello".encodeToByteArray(), read(gz, "sub/file.txt"))
            assertContentEquals("sub/file.txt".encodeToByteArray(), read(gz, "link"))
            assertContentEquals("deep".encodeToByteArray(), read(gz, "a/b/c/d/e/f/g/h/i/deep.txt"))
            assertEquals(0xb515ec2fL, Crc32.of(read(gz, "big.bin")))
            // The relocated directory appears where it belongs, not twice.
            assertFalse(entries.keys.any { it.startsWith("rr_moved/h") }, "${entries.keys}")
        }
    }

    @Test
    fun jolietAndPlainNames() {
        val joliet = listing(jolietGz)
        assertTrue("Mixed Case Name.txt" in joliet && "sub/file.txt" in joliet && "a/b/c/d/e/f/g/h/i/deep.txt" in joliet, "${joliet.keys}")
        assertNull(joliet.getValue("sub/file.txt").unixMode)
        assertContentEquals("hello".encodeToByteArray(), read(jolietGz, "sub/file.txt"))
        val plain = listing(plainGz)
        assertTrue("SUB/FILE.TXT" in plain && "README.TXT" in plain && "MIXED_CA.TXT" in plain, "${plain.keys}")
        assertContentEquals("readme content\n".encodeToByteArray(), read(plainGz, "README.TXT"))
        assertEquals(1714979289L, plain.getValue("SUB/FILE.TXT").lastModifiedEpochSeconds)
    }

    @Test
    fun fileContentsMatchAcrossTrees() {
        val reference = read(rockGz, "big.bin")
        assertEquals(5000, reference.size)
        for ((gz, name) in listOf(jolietGz to "big.bin", plainGz to "BIG.BIN", bothGz to "big.bin", xorrisoGz to "big.bin")) {
            assertContentEquals(reference, read(gz, name))
        }
    }

    @Test
    fun malformedImagesAndLimits() {
        val iso = image(rockGz)
        assertFailsWith<ArchiveFormatException> { IsoReader(ByteArrayRandomAccessSource(iso.copyOf(40_000))).entries() }
        val noTerminator = iso.copyOf().also { for (s in 17 until 32) it[s * 2048] = 2; it[16 * 2048 + 1] = 'X'.code.toByte() }
        assertFailsWith<ArchiveFormatException> { IsoReader(ByteArrayRandomAccessSource(noTerminator)).entries() }
        assertFailsWith<DecompressionBombException> {
            IsoReader(ByteArrayRandomAccessSource(iso), ArchiveLimits { maxEntries = 3 }).entries()
        }
    }
}
