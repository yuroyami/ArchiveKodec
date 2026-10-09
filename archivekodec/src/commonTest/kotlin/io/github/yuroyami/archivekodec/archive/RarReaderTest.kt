package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.rar.RarReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RAR (#126) and RAR5 decompression. The RAR4 archives and `unicode5` come from libarchive's test suite
 * (test_read_format_rar*.rar, BSD 2-clause): the stored archive named in the issue, RAR4's
 * compact Unicode names, a partly encrypted archive, and RAR5 Unicode names with a hard link
 * and a Windows symbolic link. The other RAR5 archives come from RAR 7.00 (`-ma5 -ol -oh`)
 * over t/: café.txt, text.txt (60 lines), sub/file.txt ("hello"), hard.txt (a hard link to
 * it), link -> sub/file.txt and emptydir/, modified 2024-05-06T07:08:10Z: stored (`-m0`),
 * compressed (`-m3`), encrypted data (`-p`), encrypted headers (`-hp`) and the first of two
 * volumes (`-v1k`). `solid5` is a solid RAR 7.00 archive (`-m5 -s -mcD+ -mcE+`) of s/code.bin
 * (12000 bytes of x86 code from an lz4 binary: E8 filters), s/audio.raw (10000 bytes of 16-bit
 * stereo: a delta filter), s/text.txt and s/text-copy.txt. Every listing matches `unrar lb` and
 * every member read matches `unrar x`.
 */
@OptIn(ExperimentalEncodingApi::class)
class RarReaderTest {
    private val plain4 = Base64.decode(
        "UmFyIRoHAM+QcwAADQAAAAAAAACEUnQgkDIAFAAAABQAAAADQqLIvrd22j4UMAgApIEAAHRlc3QudHh0gAi3dto+t3baPnRlc3QgdGV4dCBkb2N1bWVudA0K" +
        "nS90IJAyAAgAAAAIAAAAA3tEybbRTNg+FDAIAP+hAAB0ZXN0bGlua8AI0UzYPlBf2j50ZXN0LnR4dM3gdCCQOgAUAAAAFAAAAANCosi+Y3faPhQwEACkgQAA" +
        "dGVzdGRpclx0ZXN0LnR4dMDMY3faPmN32j50ZXN0IHRleHQgZG9jdW1lbnQNCqHIdOCQMQAAAAAAAAAAAAMAAAAAY3faPhQwBwDtQQAAdGVzdGRpcsDMY3fa" +
        "PmR32j7m53TgkDYAAAAAAAAAAAADAAAAAJ2r1T4UMAwA7UEAAHRlc3RlbXB0eWRpcoDMnavVPsVd2j7EPXsAQAcA",
    )

    private val unicode4 = Base64.decode(
        "UmFyIRoHAM+QcwAADQAAAAAAAACoKXQgkpQAAAAAAAAAAAACAAAAACJp4T4UMG8AIAAAAJVcgr6C5lyQVoK1gqKDdINIg4uDX1yQVotLg2WDTINYg2cgg2iD" +
        "TIOFg4GDk4NnLnR4dACIaGhgMIgwXKqwZVcwRDDVMKipMOswwDBcqrBlj4nGMK0workwyDAgyTCqrTDlMOEw8zCAyDAudHgAdADwJjmE5Jh0IJKTAAUAAAAF" +
        "AAAAAtydb0K2VHQ+FDBuACAAAACVXIK+guZcir+OmpK3gqKDdINAg0ODi5a8bG9uZy1maWxlbmFtZS1pbi2Kv46aLnR4dACIaGhgMIgwXKoib1dbd5VEMKrV" +
        "MKEwpDDrMIANVGxvbgBnLWZpAGxlbmEAbWUtaQpuLSJvV1sALnR4dACwdnpoa2FuammULHTgklMAAAAAAAAAAAACAAAAACJp4T4UMC4AEAAAAJVcgr6C5lyQ" +
        "VoK1gqKDdINIg4uDXwCIaGhgMIgwXKqwZVcwRDDVMKipMOswwDAA8CY5hG14dOCSMwAAAAAAAAAAAAIAAAAAD2nhPhQwDgAQAAAAlVyCvoLmAIhoaGAwiDAA" +
        "8OoJflkOdCCCSAA2AAAANgAAAAMlgvtdWFQLPxQwKADtoQAA6KGo44Gg44KIXOODleOCoeOCpOODqwCIaGhgMIgwXKrVMKEwpDDrMOa8ouWtl+mVt+OBhOOD" +
        "leOCoeOCpOODq+WQjWxvbmctZmlsZW5hbWUtaW4t5ryi5a2XLnR4dJtqdCCAQAAPAAAAEAAAAAMF0LYV3YRnQB0zIAC0gQAAYWJjZGVmZ2hpamtsbW5vcHFy" +
        "c+ODhuOCueODiC50eHQAkP8Qf+S5/CeDMD0uHNLEPXsAQAcA",
    )

    private val partly4 = Base64.decode(
        "UmFyIRoHAM+QcwAADQAAAAAAAACXOnQkhC8AIAAAABAAAAADXIatpqVMMUMdMwcAtIEAAGZvby50eHSej/+lWN0VR0i2avsuPYrqcVzVP5UnWuGwncVn4bwd" +
        "m/5qr+g8AT8QZjB0IIAnABwAAAAQAAAAA77ikM2mTDFDHTMHALSBAABiYXIudHh0DBDJPoy39FVBCn4hDUkJcB+e/9qGLLUmw+fvoMQ9ewBABwA=",
    )

    private val unicode5 = Base64.decode(
        "UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAAPP0yECgCAwuNAASNACDmxubrgAAADPCfkYvwn4yOLnR4dAoDAkaJeFby69kBSGVsbG8sIHdvcmxkISPZiwhIAgMc" +
        "AAQNIAAAAACAAAAd4pK94pOQ4pOh4pOTIOKTgeKTmOKTneKTmi50eHQKAwJGiXhW8uvZARAFBAAM8J+Ri/CfjI4udHh0kNHOR2ECAxwABACgCAAAAACAAAA1" +
        "8J2SrvCdk47wnZOC8J2St/CdkZzwnZOB8J2SvvCdkrgg8J2Qv/Cdkr7wnZOD8J2TgC50eHQKAwIeXHqeBOzZARAFAgAM8J+Ri/CfjI4udHh0HXdWUQMFBAA=",
    )

    private val stored5 = Base64.decode(
        "UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAAjOPT/iECAosABosApIMC2oE4ZtB8steAAAELdC9jYWbDqS50eHR1dGYtOCBuYW1lCgBnxQEgAgKYDAaYDKSDAtqB" +
        "OGaGEue4gAABCnQvdGV4dC50eHRjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUg" +
        "b2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21w" +
        "cmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGlu" +
        "ZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNv" +
        "bXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBs" +
        "aW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQK" +
        "Y29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxl" +
        "IGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4" +
        "dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2li" +
        "bGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0" +
        "ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNz" +
        "aWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9m" +
        "IHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJl" +
        "c3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUg" +
        "b2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21w" +
        "cmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGlu" +
        "ZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNv" +
        "bXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dAr8wHM7LAIDEQAGDP/DAtqBOGYAAAAAgAABBnQvbGluaxAFAQAMc3Vi" +
        "L2ZpbGUudHh0bctxwCQCAoUABoUApIMC2oE4ZoamEDaAAAEOdC9zdWIvZmlsZS50eHRoZWxsb2DPapgyAgMTAAYFpIMC2oE4ZgAAAACAAAEKdC9oYXJkLnR4" +
        "dBIFBAAOdC9zdWIvZmlsZS50eHRf6N3LGgICAAMA7YMB2oE4ZoAAAQp0L2VtcHR5ZGlyLE76xRUCAgADAO2DAdqBOGaAAAEFdC9zdWI9mCL7EQICAAMA7YMB" +
        "2oE4ZoAAAQF0HXdWUQMFBAA=",
    )

    private val compressed5 = Base64.decode(
        "UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAAjOPT/iECAosABosApIMC2oE4ZtB8steAAAELdC9jYWbDqS50eHR1dGYtOCBuYW1lCrML6eggAgKpAAaYDKSDAtqB" +
        "OGaGEue4gAMBCnQvdGV4dC50eHTAvCY0BCL6M73CvzZZ7ISChAKDf8dK+t7Z+N54Mym1IVPkN0UbrWfkgPzAczssAgMRAAYM/8MC2oE4ZgAAAACAAAEGdC9s" +
        "aW5rEAUBAAxzdWIvZmlsZS50eHRty3HAJAIChQAGhQCkgwLagThmhqYQNoAAAQ50L3N1Yi9maWxlLnR4dGhlbGxvYM9qmDICAxMABgWkgwLagThmAAAAAIAA" +
        "AQp0L2hhcmQudHh0EgUEAA50L3N1Yi9maWxlLnR4dF/o3csaAgIAAwDtgwHagThmgAABCnQvZW1wdHlkaXIsTvrFFQICAAMA7YMB2oE4ZoAAAQV0L3N1Yj2Y" +
        "IvsRAgIAAwDtgwHagThmgAABAXQdd1ZRAwUEAA==",
    )

    private val encrypted5 = Base64.decode(
        "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" +
        "gwHagThmgAABAXQdd1ZRAwUEAA==",
    )

    private val headers5 = Base64.decode(
        "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" +
        "Y0R62IsAn8Q42bAAg1H45A==",
    )

    private val volume5 = Base64.decode(
        "UmFyIRoHAQBt4SgnCwEFBwEGAQGAgIAAvHXK4CACEvEGBpgMpIMC2oE4Zh1sRvaAAAEKdC90ZXh0LnR4dGNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29t" +
        "cHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxp" +
        "bmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApj" +
        "b21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUg" +
        "bGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0" +
        "CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJs" +
        "ZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRl" +
        "eHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3Np" +
        "YmxlIGxpbmUgb2YgdGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2Yg" +
        "dGV4dApjb21wcmVzc2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRleHQKY29tcHJlc3NpYmxlIGxpbmUgb2YgdGV4dApjb21wcmVz" +
        "c2libGUgbGluZSBvZiB0ZXh0CmNvbXByZXNzaWJsZSBsaW5lIG9mIHRli0dRJgMFBAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==",
    )

    private val solid5 = Base64.decode(
        "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" +
        "sShcl2jVVBVq6aquNEqz9ArfFPTw8rSSWvySKLX2/ZXJ4a83fJ/sXxrKXzTe05dzhHY447yKkOrIc3qzsvO+qmrPtgFLEqjPe8s0a3QtWkiti77w1x4rq8Pl" +
        "f38OlM+2Ulp9Onj1RTTtNXrimrkqW/BQi8XBT/N4nlmuWVL7fmq8tGdpPVL0Un3/R1uZzZDex29dd1q4lv1ehy+xy+Svi7j1ypWSfzrKf1+PpeXnSEhwtzsu" +
        "U1QUHq9Bq8vkGv92S9MNxXsss3SnvV5OrJWqpE6sqainnatSPJ9LRfhZ+pVSVbwTu9beMXhrrhmTE5PHaBm1zdrtRtNRt91udp1ufyO/7pX4StCsT6pg7lhb" +
        "v++B1TLn27TtbhAwO3ierBSfHk+BKyn159HJqEoqU9H71FZqLgpUteWuyqo1N7VVdWKVij9aSvrSU1WfT3T8r8fF2Jbj8mQ4HA4nBb9vtNA56PP57UhPv+bk" +
        "Wvl7raWf/H188rzAjDxxz1yTcVrmyEzrS6ZtlybNfr4wYDI3qmw6jc6K+qM6tVtT8qQnTEgn6qfavm73VVdFcVFdWU9RY7vgb1k8CzZZl0mVcmly1kG7RPAh" +
        "d9y/TIeLxeaf+QTWuFWu4Isy4zL57F6nZ/0Zbox42m/jOT0e1I/50ZyUmfP86BOmKtOmixWpWaetU71cDy4WJVy8rUi3R1qtP0RKkl/RWTkjO8WS8vT7G3/z" +
        "iP39urzC55/ZNiEmsODYV5bLV4TZqJiZluz5+L4IuJjIaOhGky6tbNocpj2Rl/bKXDKXi7Ky1WXotQhBa8t9ZxToAj6uoa6krKApTrdcVVid+ui0srWUw7Bf" +
        "mPOfw0OmWf3J02G/h46I6W//vt+2SovfNJ1ddVdO/fFruH1mNbmvTvDhstpE7jhRcpw+BOcmakZj5zM57lT0lqn2KxJOWapUvKqoU6ve6W/fVYrk9Tnl33/W" +
        "hTztDM9yW+HQ8HWkeRv/84EQ4G4V61LhmGzCZrG3/AY1Xo1pRmqyb8/i7XP5/V5RjjPsO2Oj1o23S5L+NVicdkFq6hPxOv1LgvsvVtepfc8ufErN0afNXWcq" +
        "idMTpbqFCVnFKd8Urxhr+zX5l/fJ5nPuL1rs5vNgbh+Vuuxxu51pv2TH4+irQ4BP/bA3j+cBqM1n9C+aaHeTe9g47mcuP8XNm+rLytR7pr51VIn1c3diX5qk" +
        "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",
    )

    private fun reader(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT) = RarReader(ByteArrayRandomAccessSource(bytes), limits)

    private fun listing(bytes: ByteArray) = reader(bytes).use { it.entries().associateBy { e -> e.name } }

    private val text = "compressible line of text\n".repeat(60).encodeToByteArray()

    @Test
    fun sniffsAndOpensBothVersions() {
        for (bytes in listOf(plain4, unicode4, stored5, compressed5)) {
            assertEquals(ArchiveFormat.RAR, ArchiveKodec.sniff(bytes))
            ArchiveKodec.open(bytes).use { assertEquals(ArchiveFormat.RAR, it.format) }
        }
    }

    @Test
    fun rar4StoredMembersLinksAndDirectories() {
        reader(plain4).use {
            val entries = it.entries()
            assertEquals(listOf("test.txt", "testlink", "testdir/test.txt", "testdir/", "testemptydir/"), entries.map { e -> e.name })
            val byName = entries.associateBy { e -> e.name }
            val document = "test text document\r\n".encodeToByteArray()
            assertContentEquals(document, it.read(byName.getValue("test.txt")))
            assertContentEquals(document, it.read(byName.getValue("testdir/test.txt")))
            assertEquals(0xA1FF, byName.getValue("testlink").unixMode)
            assertContentEquals("test.txt".encodeToByteArray(), it.read(byName.getValue("testlink")))
            assertTrue(byName.getValue("testemptydir/").isDirectory)
            assertEquals(0x81A4, byName.getValue("test.txt").unixMode)
        }
    }

    @Test
    fun rar4UnicodeNamesAndRefusals() {
        reader(unicode4).use {
            val byName = it.entries().associateBy { e -> e.name }
            val kanji = "\u8868\u3060\u3088/\u6f22\u5b57\u9577\u3044\u30d5\u30a1\u30a4\u30eb\u540dlong-filename-in-\u6f22\u5b57.txt"
            assertContentEquals("kanji".encodeToByteArray(), it.read(byName.getValue(kanji)))
            assertTrue("\u8868\u3060\u3088/\u30d5\u30a1\u30a4\u30eb" in byName, "${byName.keys}")
            val compressed = byName.getValue("abcdefghijklmnopqrs\u30c6\u30b9\u30c8.txt")
            val error = assertFailsWith<ArchiveUnsupportedException> { it.read(compressed) }
            assertTrue("compression method 3" in error.message!! && "2.9" in error.message!!, error.message)
        }
        reader(partly4).use {
            val byName = it.entries().associateBy { e -> e.name }
            assertTrue(byName.getValue("foo.txt").isEncrypted)
            assertFalse(byName.getValue("bar.txt").isEncrypted)
            assertTrue("encrypted" in assertFailsWith<ArchiveUnsupportedException> { it.read(byName.getValue("foo.txt")) }.message!!)
            assertFailsWith<ArchiveUnsupportedException> { it.read(byName.getValue("bar.txt")) }
        }
    }

    @Test
    fun rar5StoredMembersLinksAndTimes() {
        reader(stored5).use {
            val byName = it.entries().associateBy { e -> e.name }
            assertEquals(setOf("t/", "t/caf\u00e9.txt", "t/text.txt", "t/sub/", "t/sub/file.txt", "t/hard.txt", "t/link", "t/emptydir/"), byName.keys)
            for (entry in byName.values) assertEquals(1714979290L, entry.lastModifiedEpochSeconds, entry.name)
            assertContentEquals("hello".encodeToByteArray(), it.read(byName.getValue("t/sub/file.txt")))
            assertContentEquals("hello".encodeToByteArray(), it.read(byName.getValue("t/hard.txt")))
            assertContentEquals("utf-8 name\n".encodeToByteArray(), it.read(byName.getValue("t/caf\u00e9.txt")))
            assertContentEquals(text, it.read(byName.getValue("t/text.txt")))
            val link = byName.getValue("t/link")
            assertEquals(0xA000, link.unixMode!! and 0xF000)
            assertContentEquals("sub/file.txt".encodeToByteArray(), it.read(link))
            assertTrue(link.isSymbolicLink)
            assertEquals("sub/file.txt", link.linkTarget)
            assertTrue(byName.getValue("t/hard.txt").isHardLink)
            assertEquals("t/sub/file.txt", byName.getValue("t/hard.txt").linkTarget)
            assertEquals(0x41ED, byName.getValue("t/emptydir/").unixMode)
        }
        reader(unicode5).use {
            val byName = it.entries().associateBy { e -> e.name }
            val hello = "Hello, world!".encodeToByteArray()
            assertContentEquals(hello, it.read(byName.getValue("\ud83d\udc4b\ud83c\udf0e.txt")))
            assertContentEquals(hello, it.read(byName.getValue("\u24bd\u24d0\u24e1\u24d3 \u24c1\u24d8\u24dd\u24da.txt")))
        }
    }

    @Test
    fun rar5Refusals() {
        reader(compressed5).use {
            val byName = it.entries().associateBy { e -> e.name }
            assertEquals(CodecId("rar5"), byName.getValue("t/text.txt").codec)
            assertContentEquals(text, it.read(byName.getValue("t/text.txt")))
            assertContentEquals("hello".encodeToByteArray(), it.read(byName.getValue("t/sub/file.txt")))
        }
        reader(encrypted5).use {
            val file = it.entries().single { e -> e.name == "t/text.txt" }
            assertTrue(file.isEncrypted)
            assertFailsWith<ArchiveUnsupportedException> { it.read(file) }
        }
        assertTrue("encrypted" in assertFailsWith<ArchiveUnsupportedException> { reader(headers5).entries() }.message!!)
        reader(volume5).use {
            val error = assertFailsWith<ArchiveUnsupportedException> { it.read(it.entries().single()) }
            assertTrue("volume" in error.message!!, error.message)
        }
    }

    @Test
    fun rar5SolidStreamsAndFilters() {
        val expected = mapOf(
            "s/code.bin" to 0x57905e5fL, "s/audio.raw" to 0xd912dcd9L, "s/text.txt" to 0x82c88ff2L, "s/text-copy.txt" to 0xc8c1bc42L,
        )
        // In listing order, each member continues the solid stream.
        reader(solid5).use {
            val files = it.entries().filter { e -> !e.isDirectory }
            assertEquals(expected.keys, files.map { e -> e.name }.toSet())
            for (file in files) assertEquals(expected.getValue(file.name), Crc32.of(it.read(file)), file.name)
        }
        // Out of order, a member decodes the stream from its start again.
        reader(solid5).use {
            for (file in it.entries().filter { e -> !e.isDirectory }.reversed()) {
                assertEquals(expected.getValue(file.name), Crc32.of(it.read(file)), file.name)
            }
        }
        reader(solid5, ArchiveLimits { maxDecoderMemory = 1000 }).use {
            assertFailsWith<DecompressionBombException> { it.read(it.entries().first { e -> !e.isDirectory }) }
        }
    }

    @Test
    fun damageAndLimits() {
        // A changed header byte fails the header CRC; a changed stored byte fails the data CRC.
        assertFailsWith<ArchiveFormatException> { reader(plain4.copyOf().also { b -> b[30] = (b[30] + 1).toByte() }).entries() }
        val offset = listing(plain4).getValue("test.txt").dataOffset.toInt()
        reader(plain4.copyOf().also { b -> b[offset] = (b[offset] + 1).toByte() }).use {
            assertFailsWith<ArchiveFormatException> { it.read(it.entries().first()) }
        }
        assertFailsWith<ArchiveFormatException> { reader(stored5.copyOf(40)).entries() }
        assertFailsWith<ArchiveFormatException> { reader("Rar!".encodeToByteArray()).entries() }
        assertFailsWith<DecompressionBombException> { reader(stored5, ArchiveLimits { maxEntries = 3 }).entries() }
        reader(stored5, ArchiveLimits { maxUncompressedBytes = 100 }).use {
            assertFailsWith<DecompressionBombException> { it.read(it.entries().single { e -> e.name == "t/text.txt" }) }
        }
        for (original in listOf(plain4, unicode4, stored5, unicode5, compressed5, solid5)) {
            val random = Random(original.size)
            repeat(150) {
                val bytes = original.copyOf()
                repeat(1 + random.nextInt(3)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
                try {
                    reader(bytes).use { r -> for (entry in r.entries()) r.read(entry) }
                } catch (_: ArchiveException) {
                    // Damage must surface as an archive error, never another exception or a hang.
                }
            }
        }
    }
}
