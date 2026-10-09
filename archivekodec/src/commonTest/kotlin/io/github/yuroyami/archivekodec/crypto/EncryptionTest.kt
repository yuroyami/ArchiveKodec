package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.ArchiveAuthenticationException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchivePasswordException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipEncryption
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.CodecRegistry
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Encryption (#42). Archives were written by 7-Zip 23.01 and Info-ZIP 3.0 with the password SECRET. */
@OptIn(ExperimentalEncodingApi::class)
class EncryptionTest {
    // 7z a -tzip -pSECRET -mem=AES256: WinZip AES-256 over Deflate.
    private val aes256Zip = Base64.decode(
        "UEsDBDMAAQBjAHMmSV0AAAAAcAQAALgLAAAGAAsAaW4udHh0AZkHAAIAQUUDCAAs+O235L47Ns9/W2yXk8sh1Cm+j2k9crRkW6QU" +
        "J2UqPcBz0n8AJUoa21119ZEuxhH9fo1tIkPeGj9LezS60urUgHlMB6OKf7/uzDcQ8pH2a3I7mtBSjvppAjp+8zygEWP/klQaOEHH" +
        "wMtMlfoQ8+Av4tXK1Iu0JGBzkTmpN/M3jfLqu9rvMwVm6OYFM4jeYygpUvyH+fSRZWqykO52EzDwmnga6eKKricTIXvjeBfL1Q5I" +
        "cPrilrkkJltjoL7fWhCVjVC3t2M0rqV6nsTVwr2m7ZR1vBtzezbeMpiKZ3YuLCikyFZOya19RcqoQkgCkSPAPEn46GvnFXT1w1uN" +
        "u1lzK+/DMuAOT8r3iEfEKTuWCD+dbRu6nJCJblqBdXKy1wM9VDwMnB0NM5X6kkR7E2/q6S1Mdp1U3/fXoyjzdJAIyShjuXRdMzj7" +
        "XELFEtZULl9aWCC4SGYAJtqHEMFmbh3t2oMlxIKxPnZQxXfXnVor4Ygq2AqKhvft2z/d/SsLEyRN15L/5uhfcN1ZjjAT1MDTmCw/" +
        "94PMZqHVPG6d60LQXr0Ljx3CFvhOgSJWO+nywv6PeQIPUGReJyMIftmzACE6mPhLEt0zvcr9APwhQqMz7vIFgMve2aUlM/OUTNoG" +
        "Ltrw3mKDh3sr3Ar0OxY38GKPeXDbRXNTtyIZfYQiuIFmtvEIYOPmZvStAOX/B/tDOjDoiFXZF0FLE9Lnnv4mMcq5zh4r9Xb8dFo8" +
        "wm23GKLde4mB0QGdsGjrG0varchV0MgzcTp0PSWVgP99/iNgJc/RliFkxgB1grwn2GzF4/npldXCHYKfE4GdHtEm6cCA0rzCvhfe" +
        "zsM0xINfd9pqNTWWYCJsFwAMpFn8oWIxNzOkzj1viArVklntOsvae5X2w63QbGZJqD0gRcNumBM/3Xw8Az4avz+m9AIXRaNS/idj" +
        "IFcy5KfsSFTzQr8fx8VUuWVycYLUBnfP027qa+x9L0J4BZbOBLaQxlUVIn2EfruwD53dk5a062xmmlhrNq4XVUhXZzwQvCRGOP+I" +
        "L6KnYaz3GBoWrhgCLh2NCv0196h+mp98CsJbaXHZPjdvVPQJt9z39lF2SLprTfoKSzze2sS4vIDi7ImtAOTx416TksE5twds17W5" +
        "rEQkQ+99MLt7N3xbjTMCv/LNjLlputsQXT6S4f30wxTypZak/fdAC/ZPizjduGdzpwZsWK5jTWlHcXz80lTKvSemgrXKQBdBsg1r" +
        "YWNqMeuCq8IcaR80R+PMFx8KhwAk2cHDkGS56Y2+jsCrmSJfQcN+YgvYHWPdiYrjB03MLTuvCHw4cJoy5F8CctUtl3YJ2pbOFYL2" +
        "cM06rVDK5lfYtHxRpjdHpB+D1LPCMHmQVCVbq3ikrlfg7g5p4wQzMjaDhl0cv1NThWHhmWekNgHjVZsbuxRLhzTqVg+XI7nviE5e" +
        "v/RYjMHuI7kMklFkdy5FitMmYpBJNgEoelIF8ozJP0MQ3sfneUCp6iuA3KWu7+HsX1s4qBSqLqOIVlBLAQI/AzMAAQBjAHMmSV0A" +
        "AAAAcAQAALgLAAAGAC8AAAAAAAAAIICkgQAAAABpbi50eHQKACAAAAAAAAEAGABb+HLdqVfdAQAAAAAAAAAAAAAAAAAAAAABmQcA" +
        "AgBBRQMIAFBLBQYAAAAAAQABAGMAAACfBAAAAAA=",
    )
    // 7z a -tzip -pSECRET -mem=AES128 -mx=0: WinZip AES-128 over stored data.
    private val aes128Zip = Base64.decode(
        "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",
    )
    // 7z a -tzip -pSECRET -mem=ZipCrypto: legacy encryption, checked against the CRC.
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
    // zip -P SECRET from stdin: a data descriptor, so the check byte comes from the DOS time.
    private val zipCryptoStreamed = Base64.decode(
        "UEsDBC0ACQAIAHImSV0HNwbm//////////8BABQALQEAEAC4CwAAAAAAAIkEAAAAAAAA3GZt9QyFDKd0iUQskdahf1Ts13H8MBQE" +
        "SAJtAkb4FtlCQQg5A8Ix1d+JeXvEWWLzhPrT7jFyK8gqSO/0LyL0UH1Ouw/VeYP5RnI7lelZx2Vgrcdf4T+oDi92nhvaIUzt/Ymz" +
        "4p0kBnIJCSfTVCS5nayEhtyyefUHFEVI6h40H838ZTs18aMQfmxklz3OCUHYWB3Vlm0uuPl9TIgsPMSEqQ5Et8oV1CW6c9ey8y6m" +
        "ozkwBov3GmyoxExqW1e4m93bAA44iMYE+ExW13LttC6UhdBAj12/01BWwQB0WJlTb6WYiNFR2UosBrDyXuwhI2QYF2boSx+EERG6" +
        "XXJFEq21Svw14ejECLfXZlhfUEhh1AsnLjqSB0DkIGXzJxGmiuSGxF1XPmbgdI3gHaAVPmmtjktNtQc3SjH50PWXhE+SXre/zFB8" +
        "l7JH/NYzDrzrACG2ZvgAcaBXuTu9xcbBu8OpITTvOdatb1Os93niOf+lPaaxECZpxePTaz6/BjkrBVZjyhdsYZGAXE5EjnPYCvsT" +
        "jR1j1MWM3U2D0cRawA55AddJP4sFrPsCQDET/0Rx9SfQxIYlDjz8O7rsaAKJDRZPomp2BUhIxgpWLJZHhJ9wHBC+5zTEgNvAm1ML" +
        "c/B7MehpZkfX2lZIzUL3HmqmLzlj3d7/DZVMftKIZiUN+bk+QFAFAXVuJgyHAODDQ3VwLSnJYUab5mdsNOhbn29NEU5NQ4gLeolM" +
        "F5qf3WgljN1B9qg82V/Av2kd9/ii/YIz3N0BjACsWjl6BO/7mY/lM7A5W0IBMqkO+p8tB+bi6/4EEs+HdC7r3/6Ti1oG+j4jF8GJ" +
        "O4rqTdWG+6StlSd8IsCGL3eNPtaLJsns0b5dR0owpSOpsGv2niLI5sdQuaMf3gflHnDLjYtMFLmg1tP5ASOtdnFZCbkcZgn6gkD+" +
        "Xj6gwShWbVdx1bs8j0OpxDRpJ/UBBETHamuMu5Mc+076uVwpFOxkZUV3PdMLN2MDPfuR0FhpekG/WvmUwkJ7EGZiJUyGm/6I9Aky" +
        "oPU4dpEL+X2C3t3SUo/6zzODIW7FM4lUZx77GbEAb9EuhWjCP3UlJcsIf2ut8NPNKrGTiQ4DKvRay+xvWwSocwwxIYxngwOecFxM" +
        "3De8WuPClgwqV1NqhfJQxt7CaUmyTAcdXY0z216QSJEQBvHOFKF99enfvaw9dG9kaofsnN+ETrm0HqxLbcMq13GzEHh2g2Cnb0Tj" +
        "YaFx7A5RkSuFZWcDQs2+Id8QQMJYVxSf0HE/MQH2kTjON6fYW2w53fk7AEXIHwbuhKxx8PWdORKLJKkc9eEufA6ZTNeDu4d9yE0S" +
        "wQ+7lu9eh88SEVlSO7bW3dRP84uG45aZyXoypGg+Hlw641xYDDJK+bvt571e9/Ru4H6LENtOXYbCEjST2JKE3GqEzqlMRAtfh5mD" +
        "Xu1YD1ag/eIUFtgQPEprVemdf3ISFVhYT83tRTOrocbRyk8drwNzm5ifCb0HWZFcqubJubUGug27yJOMnv9fL9KfilCODWYxWXUU" +
        "nP8cJgLDL19MKVZJUEsHCAc3BuaJBAAAAAAAALgLAAAAAAAAUEsBAh4DLQAJAAgAciZJXQc3BuaJBAAAuAsAAAEAAAAAAAAAAQAA" +
        "AIARAAAAAC1QSwYGLAAAAAAAAAAeAy0AAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAALwAAAAAAAADUBAAAAAAAAFBLBgcAAAAAAwUA" +
        "AAAAAAABAAAAUEsFBgAAAAABAAEALwAAANQEAAAAAA==",
    )
    // 7z a -pSECRET: AES-256 data, plain headers.
    private val aes7z = Base64.decode(
        "N3q8ryccAASfFOpdkAQAAAAAAABqAAAAAAAAADRahtSyU6FiiHMcOkIHS8gZh+8nKHnbOSFQ9CeFh/sSkvxYev4DBNv+LxBirUCs" +
        "YXDYNQA1bgZBqiIKw6jwJNv93fUCFhjlOekLJ9dToR8TQnqmjVDM9c90pm7RDQ5z/64hyBt7pGVsXuR52Sy+8IGwbusfBs547A4Y" +
        "lAIvDDEQKeUfP36jtYrC2ODbCz6rP1TzUVfc8Cs4mkD2eQeVQMnAgxXsHlbwuRwrbalnlH+MLTtwGJbnszkHm8uEXGpkfZi3ajka" +
        "ocm+4RC24Lb9LKHVFJJNday4/5LFR4rK/swCJZtTe3ErTS/gqD1s37JbeXI0BO1MvFOKi6olSPJaXhJB3xuJHGJ0hZMZOk9zFzkk" +
        "Ce7+8u2IwQmlXEue6FyDnCrNVuhUDVQwwTAhuEphyu7gRuPFCaLw4cowD8At2/MgByC5yD488hW9dKxn04Ry0+/MkCZZvZCyDiL5" +
        "qeMQHxNBsh4aRmYeAMjDVHy8ZSuabxxrlhSlSGm5/F8tBAgowYBbQLq7IURVP4lczcrdMJiQzAeK9on07snQY1ToWKCJfpJfy73A" +
        "6lMpuYfdr7mQdAtFnTwHNa64eQHN5eZWF3VgLhXwrJLgjIoNlJeEJFH4NBPa9p5kd2wUni4yQiL21OD9B0S7ultVX9U986KXl7Hf" +
        "fXE1cjQfmAeqMG0MohwLUx5Nv5Q3oLSnW1l/o5oGL3Kh6/+gC1kC64Q+7mnjkODzhxpWU4cDJ/Zlr8lJv8uieXMfR7ELL1B0yBED" +
        "BZsfuUFUJIajaxQF9n2mpT/zOeQxvkgrjkWJFfFgBYeh8Av6J4amaU8/Zbt9XEiRnQX0vDVzroW8xzB3syg4xo50qCXY1mFiT/hs" +
        "yhXo7uGS3DkGx29DHT8qDIDj1U9KwWxvIhrEi9n9iwyg8qfmR4a5VixAh/Tk90cKiG761WvuXC4dckTlbs9Ce3ETvJGs3QY10rgF" +
        "2MeGMzIUhinRHkht4qzUNzJVZPZRh5HbmEQLGADlTOJOE6Dz59LkTAc3rqdXEhhX18aVQC+Mliabf4qJDCGgYq/jXGEBQ/3N8zfC" +
        "ad/nxs7+vzr/IRXoo620GLHs/PWv8Gqw7SXxYI4fj8eMXiozsuqXuFR/5KqPziENU0jRqwtdkcTt+CftwXX9GSYdoPJU3uJ4dJh2" +
        "Bg0oRsP3w8PhqvWyCqKG7PlHA0ylfoxrCoJv1JLh+OPDjQ2OclZvVmtyW5E2BUc5c/1DVf81xm+w2KiwqSFXjPx6MK9HTcT/UaDQ" +
        "zP6RrbC7ovIJbwfKynjHISuXpKCbbGX1uGWZYBhx6s3G13KtE6MMTwRgzVW/Qm8r2ROqZ5uRi4Nu+47odVbRIwnTOmr+OYAg1fxA" +
        "2SWSAn/hgs2BGICSWsSjMrVLS/7a95yj+zjIH3Lv8S8or9e7T85OuLcfDmAM0wOOuyW05rrg7BS/Be6r6H47MktNq90SfhjYP0e5" +
        "TWsX5obSOYGGYvS3QzEo3Km8JIweaw3gF6/L2E30rg85mVxoiglnWNh4Fw9hwC/EZBJ792rJYTjyupp3FWs9xzxCzl3KeDZ5EET2" +
        "AQQGAAEJhJAABwsBAAIkBvEHARJTDzjymv7pI32M2E+IXCnz5lUhIQEAAQAMhIeLuAAICgEHNwbmAAAFAREPAGkAbgAuAHQAeAB0" +
        "AAAAGQQAAAAAFAoBAFv4ct2pV90BFQYBACCApIEAAA==",
    )
    // 7z a -pSECRET -mhe=on: encrypted headers too.
    private val aesHeaders7z = Base64.decode(
        "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" +
        "agoBrhOwqgAA",
    )

    private val words = ("the of and to in a is that for it as was with be by on not he I this are or his from at which but have an " +
        "had they you were their one all we can her has there been if more when will would who so no").split(" ")

    /** The fixture text: words from a fixed list with spaces, commas and full stops. */
    private fun text(size: Int): ByteArray {
        var x = 7L
        val out = StringBuilder()
        while (out.length < size) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            out.append(words[((x shr 16) % words.size).toInt()])
            val r = (x shr 8) % 16
            out.append(if (r == 0L) ". " else if (r == 1L) ", " else " ")
        }
        return out.substring(0, size).encodeToByteArray()
    }

    private val plain = text(3000)
    private val crypto = TestCrypto()

    private fun options(password: String?, crypto: CryptoProvider? = this.crypto, codecs: CodecRegistry = CodecRegistry.DEFAULT) = ArchiveOpenOptions {
        this.password = password?.let { ArchivePasswordProvider.of(it) }
        this.crypto = crypto
        this.codecs = codecs
    }

    private fun open(bytes: ByteArray, options: ArchiveOpenOptions): ArchiveReader = ArchiveKodec.open(bytes, null, options)

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun testProviderMatchesPublishedVectors() {
        // FIPS 197 appendix C, RFC 3174 and RFC 6070.
        val block = hex("00112233445566778899aabbccddeeff")
        assertContentEquals(hex("69c4e0d86a7b0430d8cdb78070b4c55a"), crypto.aesEcbEncrypt(ByteArray(16) { it.toByte() }, block))
        assertContentEquals(hex("8ea2b7ca516745bfeafc49904b496089"), crypto.aesEcbEncrypt(ByteArray(32) { it.toByte() }, block))
        assertContentEquals(hex("dda97ca4864cdfe06eaf70a0ec0d7191"), crypto.aesEcbEncrypt(ByteArray(24) { it.toByte() }, block))
        assertContentEquals(hex("a9993e364706816aba3e25717850c26c9cd0d89d"), TestCrypto.sha1("abc".encodeToByteArray()))
        assertContentEquals(hex("4b007901b765489abead49d926f721d065a429c1"),
            crypto.pbkdf2HmacSha1("password".encodeToByteArray(), "salt".encodeToByteArray(), 4096, 20))
        val key = ByteArray(32) { (it * 7).toByte() }
        val iv = ByteArray(16) { (it * 3).toByte() }
        assertContentEquals(block + block, crypto.aesCbcDecrypt(key, iv, crypto.aesCbcEncrypt(key, iv, block + block)))
    }

    @Test
    fun winZipAesReadsWithThePasswordAndAProvider() {
        for ((zip, encryption) in listOf(aes256Zip to ArchiveEncryption.WINZIP_AES_256, aes128Zip to ArchiveEncryption.WINZIP_AES_128)) {
            open(zip, options("SECRET")).use { reader ->
                val entry = reader.entries().single()
                assertTrue(entry.isEncrypted)
                assertEquals(encryption, entry.encryption)
                assertEquals(99, entry.zipMethod)
                assertEquals(if (zip === aes256Zip) CodecId.DEFLATE else CodecId.STORE, entry.codec)
                assertContentEquals(plain, reader.read(entry))
            }
        }
    }

    @Test
    fun missingOrWrongPasswordsAndMissingProvidersFailClearly() {
        open(aes256Zip, options(null)).use { reader ->
            val error = assertFailsWith<ArchivePasswordException> { reader.read(reader.entries().single()) }
            assertTrue(error.message!!.contains("no password"), error.message)
        }
        open(aes256Zip, options("wrong")).use { reader ->
            assertFailsWith<ArchivePasswordException> { reader.read(reader.entries().single()) }
        }
        open(aes256Zip, options("SECRET", crypto = null)).use { reader ->
            val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(reader.entries().single()) }
            assertTrue(error.message!!.contains("CryptoProvider"), error.message)
        }
        // The default options carry no password at all.
        ArchiveKodec.open(aes256Zip).use { reader ->
            assertFailsWith<ArchivePasswordException> { reader.read(reader.entries().single()) }
        }
    }

    @OptIn(ExperimentalArchiveImplementation::class)
    private class CountingDeflate : Codec {
        var calls = 0
        override val id = CodecId.DEFLATE
        override fun compress(data: ByteArray) = Codecs.DEFLATE.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray {
            calls++
            return Codecs.DEFLATE.decompress(data, limits)
        }
    }

    @Test
    fun tamperedCiphertextFailsAuthenticationBeforeAnyDecompression() {
        val deflate = CountingDeflate()
        val codecs = CodecRegistry.DEFAULT.with(deflate)
        // Local header (30) + name (6) + extended timestamp and AES extra, then salt (16) and verifier (2).
        open(aes256Zip, options("SECRET", codecs = codecs)).use { reader -> assertContentEquals(plain, reader.read(reader.entries().single())) }
        assertEquals(1, deflate.calls)
        val tampered = aes256Zip.copyOf()
        val dataStart = 30 + 6 + ((tampered[28].toInt() and 0xFF) or ((tampered[29].toInt() and 0xFF) shl 8))
        tampered[dataStart + 16 + 2 + 100] = (tampered[dataStart + 16 + 2 + 100].toInt() xor 1).toByte()
        open(tampered, options("SECRET", codecs = codecs)).use { reader ->
            assertFailsWith<ArchiveAuthenticationException> { reader.read(reader.entries().single()) }
        }
        assertEquals(1, deflate.calls, "the decompressor must not see unauthenticated data")
    }

    @Test
    fun zipCryptoIsReadAndMarkedInsecure() {
        for (zip in listOf(zipCrypto, zipCryptoStreamed)) {
            open(zip, options("SECRET", crypto = null)).use { reader ->
                val entry = reader.entries().single()
                assertEquals(ArchiveEncryption.ZIP_CRYPTO_INSECURE, entry.encryption)
                assertContentEquals(plain, reader.read(entry))
            }
            open(zip, options("wrong", crypto = null)).use { reader ->
                assertFailsWith<ArchivePasswordException> { reader.read(reader.entries().single()) }
            }
        }
    }

    @Test
    fun sevenZipAesReadsDataAndEncryptedHeaders() {
        open(aes7z, options(null)).use { reader ->
            val entry = reader.entries().single()
            assertEquals("in.txt", entry.name)
            assertEquals(ArchiveEncryption.SEVEN_ZIP_AES_256, entry.encryption)
            assertFailsWith<ArchivePasswordException> { reader.read(entry) }
        }
        open(aes7z, options("SECRET")).use { reader -> assertContentEquals(plain, reader.read(reader.entries().single())) }
        open(aes7z, options("wrong")).use { reader ->
            assertFailsWith<ArchivePasswordException> { reader.read(reader.entries().single()) }
        }
        open(aesHeaders7z, options(null)).use { reader ->
            val error = assertFailsWith<ArchivePasswordException> { reader.entries() }
            assertTrue(error.message!!.contains("header"), error.message)
        }
        open(aesHeaders7z, options("wrong")).use { reader -> assertFailsWith<ArchivePasswordException> { reader.entries() } }
        open(aesHeaders7z, options("SECRET")).use { reader -> assertContentEquals(plain, reader.read(reader.entries().single())) }
        open(aesHeaders7z, options("SECRET", crypto = null)).use { reader -> assertFailsWith<ArchiveUnsupportedException> { reader.entries() } }
    }

    @Test
    fun zipWriterEncryptsWithWinZipAes() {
        val files = listOf(
            ZipWriter.FileSpec("dir/", ByteArray(0), ZipMethod.STORED),
            ZipWriter.FileSpec("dir/text.txt", plain),
            ZipWriter.FileSpec("dir/stored.bin", byteArrayOf(1, 2, 3), ZipMethod.STORED),
            ZipWriter.FileSpec("empty", ByteArray(0)),
        )
        for (bits in listOf(128, 192, 256)) {
            val zip = ZipWriter.write(files, "a comment", ZipEncryption("pässword".toCharArray(), crypto, bits))
            open(zip, options("pässword")).use { reader ->
                val entries = reader.entries()
                assertFalse(entries[0].isEncrypted)
                assertNull(entries[0].encryption)
                for ((i, entry) in entries.withIndex().drop(1)) {
                    assertEquals(ArchiveEncryption("winzip-aes-$bits"), entry.encryption)
                    assertContentEquals(files[i].data, reader.read(entry))
                }
            }
            open(zip, options("password")).use { reader ->
                assertFailsWith<ArchivePasswordException> { reader.read(reader.entries()[1]) }
            }
        }
        assertFailsWith<IllegalArgumentException> { ZipEncryption("x".toCharArray(), crypto, 64) }
        assertFailsWith<IllegalArgumentException> { ZipEncryption(CharArray(0), crypto) }
    }
}
