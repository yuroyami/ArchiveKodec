package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.rar.RarReader
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
 * RAR (#126). The RAR4 archives and `unicode5` come from libarchive's test suite
 * (test_read_format_rar*.rar, BSD 2-clause): the stored archive named in the issue, RAR4's
 * compact Unicode names, a partly encrypted archive, and RAR5 Unicode names with a hard link
 * and a Windows symbolic link. The other RAR5 archives come from RAR 7.00 (`-ma5 -ol -oh`)
 * over t/: café.txt, text.txt (60 lines), sub/file.txt ("hello"), hard.txt (a hard link to
 * it), link -> sub/file.txt and emptydir/, modified 2024-05-06T07:08:10Z: stored (`-m0`),
 * compressed (`-m3`), encrypted data (`-p`), encrypted headers (`-hp`) and the first of two
 * volumes (`-v1k`). Every listing matches `unrar lb` and every stored member matches `unrar x`.
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
        "UmFyIRoHAQBYhkiuIQQAAAEPG0fYlHnzs4RX15jfyS6EzZTh0D7x90gjhVSwaAMWIXm8f1xelVrcwv7wQ8ittl6ekIBxuC6fCLA4UKl4zCTZEFDYZeQ2hfHi" +
        "UiN8TGJXcCaM+8rRY8yjoO7cczY2tEzepZGDN2BT0SbwEI7heXtgiqUCEC07mbDaMa1Xol1/J0XSxrgCHxjKhs42lRnvQQFodzKGM2SagxMmK1zDwJWjZhIy" +
        "c1zC0Wy0DJCIlHyqw0jt1UdyKqvr3SX7nPMOXnIzn6YgTiRBr1ClRi8p+MbV3SogaNBkIbfJleEpVTqOppnCgdbFjQg6oh2pejfsEk8PIAScbsuroFHmWrYs" +
        "jrpq//0J/eLM4VRk8AveQUefBvSo2oQN083trFEcpTt6auJ0bZOPOXXy71FA1iOBCM77ZjoRDcGzpUJCYxXXfi/u2lJyqhLs/gLvCtrTU5QMLzc0Rd5HLXV6" +
        "mo6OpAS42e/o7yz3ngGjwlhcfornvGPkPRgTYNeOO5LN6XPzSfhUaJau+q2fALEC5k50cC7T0mSZHNBgVqKCx8fKrkBh+6BdPKos5uUci4HmGYNEMENy1xC2" +
        "7FwyP5X7qappiDYAquRxc3Kp+weXRCuwjcVO7IfP2clT+UvCuX2k8Epux1KAchzV4NbdA0iMaI5GFD6f5NcX5dxBoI45EZCOiZegXkg5OFkM06uBodYdjmf+" +
        "fwTuVicf01VrL3bj0zP+yaUFyQABWZpK1NG8oORipDJB9ncPMW/eFZwv5anST6tTzXCW6Yj3M5Nlg8C0UFPg4XdWSTOqRgJgR5MdoJ2TfV+78zQFtQ5iyS8a" +
        "yOx0o/aUUmnkM90t77OLLIwbKrj1XYbXe2+7fUqZGJZWWlEAP7W9zPY6T7nVwuZZgu7WhkvgJmT5ke/wW5Y+pWncERmhI9autqteic+67ws8RTuTzBr3rHUE" +
        "AE/PjAeuHKt3zgoTEWPmteuZ1naAty0WgHwB9GGo0Ft/xqVjcENJkNvtnvO3g7jsHXtplGtAW3z8EC9GGtQ4WUlt64olDrey+4UMe5bTxPaWWl+gqKzBVs1u" +
        "mWw8RkjP0lhxnZx33HOdYN4g+KZHjr4MzLtM0UeL5XysOXV+nb6CmPXfv1wlhg8EY9AefuHHNOBviuz7DNrSv+YdAsSpu8rTI08lsGpDxwO2KbPXfeSKc1+Z" +
        "z/zPgCkuxMZAEILlaomyIXD5RIv5sX0+7pINrDRiHL6b5oSEYGk+iFrpVb1MblBbPtdgEkCsE7z8E+UbI+kDiDPyCZ/vU4bZGeaMiyDscOghLBtVGvwLKRZ3" +
        "Uo2JnWkjMZKcA2yUSU7nlwkSxUZGKY1/UgweOZtzWQKQZexTyIZ5Hg697nS87sAcBRUu2YAtdKoCfEFzQjSnLZCXCUrmUYRtdBr8W5LCoAQ4WD0qGohRRnTL" +
        "uSfz12YD5FihQBjHx7dr4u5R4RELVriZ0ftLNy6mv4RCtXIADvVkpj9o4ura9MpYVIRXmzDMoc3TE69kIkuVCbWXCn6tvaGFPSSKabN7ttDqZzv29Rh+NEHb" +
        "FzlVt30XsFVpgcNHv27i570RnHp38PgfuBuR2PngfHRUrVkuwsZ0kbSefOeU5bnKDtHee/60QPV2Rg1AR4zlq7015cVcC5V4sADIzMV8kEsDhcC/1+SjKCE4" +
        "H//w5c7sBrY0BSTESzBo+ZaQCVBvokxD6YYQKvsLr6/yLcXcakAsJFkAACpxKyBPfOS5/rRYBnH7d+EXOUc8A4gV5q/dKrzGCZGsbHS6r9pbeLxo2Fh7vMVB" +
        "74yi2FIBOZdxKAjCX782QeU224MCRDz8SBdrtTXrHZNRFoqSa8BvkdiYzw9zZRcja2sYb+hLBDu3bg75gjjkOCSHkCB+YPRlAZp521/yTLf2JnPjO0ICFmq4" +
        "YtVHuKSzs9SYD9C5roTP1BXgh3mkiWCoKTLCvgjJkz5EyzGDLTLtvWaNLsAK8XqZiX64svUzEAAF/WF5FUnW9ZeZjpyZqyayg1sn1R3LwMW2NK3hnzmMWVOi" +
        "DKuSUNXfMO83rodelw6nFC5mu5QeMMP5bD6YdlMabqXYn5NCr5tNaH6lf4ZncN1RbT7ZIVU+Y/kR+wzzi8cVVGARK6tvELRtFTui0rX3NxRJcA2XjLDYKu/m" +
        "sAg56zuYK8fLPzdq75W54tmABfDzAYRmTTe6TFKzBrPoQhkbDIO4rE1OMl9DmzPCr83XPSuHLtJoEzBKtcDdKwqkBmMrRc/MuVenkYfDUNrdVV0Ur5yzYQff" +
        "+JHFEQQ6kDu1FkIimJCWFtVafYyRgY5n2rahIZ6p3SiqxPwP3jqNdmyZ+Etems6ze2TAsDT6s/RWnWT/O7ZYOmU1NPnkaRZ8kvWg6wdT8j2MOb8mcd1VnN/U" +
        "M3+jVgL/5DOyiTYtNy8h43+jI6pGrfrPb0caqMB+AomyTy6HdmSfCNhvEOpkloATs7kmezTjiT5GapGIcZ3UKgcV0bt80njXNbTV190BiC+pZL/Ohzyg5Cs3" +
        "KPcSJwMzmi3eBOepnKBSAD0+6SgI68wU3DAUjSH9ZAW+A+Ofy4Kd9MRFZDa1F6UVZYrhkryihRM5l433DexAZC4ln0uXWYNHMwL/dhGHQtEYIpV/nDcfm/iB" +
        "F8FRsJ4V3uC4/DJs6So57CX/50dzNhT/LrCjugMw9NL5wWxq5E5AeE3jSsF07NhYW8YtoQjoy6jVDvMWvHMomVDcgvTuC5mD5FSC/57i2Xp5l9EAzOQBoz9T" +
        "h0uW0yxGCUJQVHSGWivXNXU19Lq5xrnyK0Ryx7Z/fLddZm4gGNp/OUNE7XxosNf4kxlSIZio9kJ7bVnUgP1reu7VLftj6IrdVXMsL29MTRYeBGPY+Aa+cfds" +
        "r7BDdy+IoMqB5sHVqscG+lV4Jp543Kcb4FL3fVCwXRNZQjo4ffFHbBXQm6MgjNSZAc2eNK+xjklWLzc/LYsS9wGTb2TfFutroUwZgrkej0/Myyq1m/sd7X8Q" +
        "Z2ACJ0vst5RUFXzZvGTvAo5TffFCcNf984jzEM9E/6l/D/iQqNbasaU8xJPzg+yyNo5u5T/tjvPJ9fMf7RoGttTFaTHEdsnCOP6iQkOmR16vbcAR+qNV6ftV" +
        "XWIER7VzUGtByo8CkX38c7Xj6z3S7T8MzKTwvzqHjfuYkfPfCXcpzsCRFfZOAVfhQjAjQHfaqJKXYfByyFQPFRe+9X1XoUZeT+5pptBb7OuCGGduLhTrpX/3" +
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
            assertEquals(text.size.toLong(), byName.getValue("t/text.txt").size)
            assertTrue("compression method 3" in assertFailsWith<ArchiveUnsupportedException> { it.read(byName.getValue("t/text.txt")) }.message!!)
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
        for (original in listOf(plain4, unicode4, stored5, unicode5)) {
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
