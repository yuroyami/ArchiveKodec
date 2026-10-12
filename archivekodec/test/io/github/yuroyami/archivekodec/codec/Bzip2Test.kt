package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.codec.bzip2.Bzip2Encoder
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** bzip2 (#11). Fixtures come from bzip2 1.0.8 and Info-ZIP zip 3.0; encoder output was checked with libbzip2. */
@OptIn(ExperimentalEncodingApi::class)
class Bzip2Test {
    // printf 'hello world, hello world' | bzip2
    private val hello = Base64.decode("QlpoOTFBWSZTWcNdy64AAAQRgEAEBkSQgCAAISppmIIMmIplsZ9Qgg0gjhdyRThQkMNdy64=")

    // bzip2 -1 of generated(1_100_000): eleven 100k blocks with long runs and six-table selectors.
    private val multiBlock = Base64.decode(
        "QlpoMTFBWSZTWc/kihMASgl//9m+f6/u0n50pC0cCPbfb/etIv6k3zMdx+pn5/DJ97moYBk++p9VUUUoqLTKUUKqrQYIkUClVLQw" +
        "gqopCVLQjWgKKCqkKAAFgABWQAZGmmmJhGJkYmjTTIyMCMCYAjRpkYmgYI0MjBDIxMCMABMCADI000xMIxMjE0aaZGRgRgTAEaNM" +
        "jE0DBGhkYIZGJgRgAJgQAZGmmmJhGJkYmjTTIyMCMCYAjRpkYmgYI0MjBDIxMCMABMAk9UqqpkGENDAmQwEyYAQxAYmACMEwmATA" +
        "CDCDCaYjJpgjE0BClKkynlNqbUyNojJ5T0j1A9Q00ZGJhGJhMjRhGynlPIJ6T0GmjQGk9I09TJ5DEnqfqYeqBU1KqqeoAaYmajJk" +
        "wBGENGjJkGAEaGjRgIGgyYEaZMTJgAAABM8NDtRRWmYCQSEUAEpRBWxRjNTM2yDfyDB6O2astAd/KNFZCZCuZiFIOSUOWOs3VMpQ" +
        "7u2UysdZlBumrUzo26Y6Hd2KFG6xd3axyorcxUWLFtq5tnTVRujcqx3cplNlBTG1iLRWDZNY1RquVwpijdCsDo1HQ6Ot1rQWjbYS" +
        "3K1zVuWubY1Ux0262KxWVhO5nWZTSqd1VyxUaoNzdIoxtc23dxaisa1G1y0HKyaxaNRqrm5sljXKM7qhtunWcmdFHToOsoora5Y2" +
        "uUVQWo5XNRzRtiNum3NYi1XLa5UaxyjRtctbmrG1gDJFo0CitlZO7HWasU0tnTdbUbWKjUbRuVctjbXLkWubc5cqq5yNi2NVyNAd" +
        "dGu6hRd2ausddbrN00Vc2uUasbEc1zbd3arly0VjbFuXKo20atHK5uXLbEUXKNYxrhsUbbp1jVqxdzbpkqx0Z0rCNotndq5tJaKN" +
        "Y2xq3DdFArdZutjlumrHW621BTHTboVs62uudFM6OtTlqbdMpWN1g6wdZt1tXKrltjcndW5tiid2wUZKUZA5KGSDZilFKGSUqWjX" +
        "LRRW5XLmNo1RrFRGrEY1GxtsWq5trmUFY26xWKxWdFCisrGpt1qwctTu7ZuhutiW25rc5quVg1G1FaNRhWzdHR0zro26aqw0s1Mr" +
        "MVtmrZ03IVimrFHLm25bc1RuVubpbcsbRTuM5ClMUu7ZW6MplKaqyjdZnTKFbNRug6zo6ahut0K1SUx1nW2d3ZGNsbFHKxtrmtFc" +
        "qLmymV1nWFOnLHTZ1soxLMUFFKOjdZWKw6ooOsym6bVqzdA6N0UN0N1t0yjOsrVsymdMda3d1sRsBarprc5qNXLmwoas1CjpTKNd" +
        "y7maXOarm5tVy4axaCjaiolq6xRnRRusK2ys6NubY2NuYirmot3dauahBTVsUdFMdYrOsrZSpLXNjXNjajblo0VEpclyRclWslac" +
        "lKByEKTEVrmo1o2KKNtBW7utisbRrdMWrhjVoLajFGxUbRRtXLmKc6i1c1w2sWo1ctXINy25VRqNsaLUFiubblYqKhhSspWOjl0F" +
        "ZuisXcVh1utt1WwucSOtutnW1KU6xR0zo3WrN3dsamSdbbrFCjput1s6zU2UxRQVtiugVqDptWUJWNbm1x3Wrpaooucxti3Kuajm" +
        "3NubRzGsUFVzbkQYsu7W5zarmLUahQZ0dY1MdZR0OsUZ0xQVigoKZRWN1tutsblzYtsVy0lY5WLUYrXLW5Ywarmrm2LblWLlc1aK" +
        "tzaNsaNzVuaLmU6bdM3WasUUKam6YhblQWNa5RbVzWNctuTuqK3NY0atzic2t0qKKjY1yrlouRzblqNbXMctRtG5qybRtiNbmtGO" +
        "crlqKjZm2rlOuja5bYrYquaxaxtjEc1c1c3WxLZ0Uw6VjUam1Zutw2rlzaLcty7urRsbWKNzXLGojctyLXDbmqjbKN1sU3SsKwUK" +
        "3Q6MajVzYq3LXNW6aIjXd2x06atnTLu3WpmoHQddajWK1cuVjHLkbc2ubQasOsHTamdbZ0Z11m66Y6Z01Nt3cKwo1YVmVqwbRbUa" +
        "uG5o21yLm0KNI2YhkJQ0g5mCuTkORkFW5jVzXNRsWulGi1ndO7XNblbmrmxaIuaNza5bG2QqjW3KuW5rctsa5W0WLbY2xnOoxd3V" +
        "satwdYaqwrCmk6dMpWbrbVtW6DpnWZLOtugph1Q3WFZuhQ62ytjo5HRWbrOm6w62rN0ajHTU0Z3bG1ubc225rpWNiI5zFsbljaox" +
        "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" +
        "kHLIQyAyVySlSlTJKcF3dUaxRtuauWMarFuVbpi1Rorm1zm3IxGi2o1JrYxRtua5tcty5rnWK63JsdY1FZuhTOtujlUWunDaNGxY" +
        "5tuUW1Fquba7u25Ro2qOc5tc25tXKNy25W5zaNUVFrG2NtFtGoo1FubXLG0ajblzWLFg3KK5tctUa3NQmKuVy3Nc1FndblaNaLa4" +
        "WojG3MRtRUmqG6M6FUbrbKOjUzrN3cbpQaijTu3THWzpus1ByKysdDdbHSmysLuUY2jUHMZ3Z3WxtzW1zRbc2i07qua3K5qjWNtw" +
        "zqsTuDdHWHaw6w6BXLUWtjlRyuWucrbm2DTrU26HWpqHI6Od2UytnK7tnWdOsodbdbdKHQ6bYi1c0armLUFuarphN3cdM6MVt1t0" +
        "pmRM6OFc1cuaN3dtw20Tu2rmtyrm5q5i2jEbXNuRq4a5rGjCWKKkwWu7o1XLbkVjWNrFZ3W3NqKsaLaNYtys7tu7rc1cDUlXTNuj" +
        "VtSgraunQdNqYp0NTahlDuc26dbKbodFCs6OipGUbrGdNqY6zdFbbKVt0ZWV0NRiZx1HNysWxauVSc25rGNQbmtc2LlauVuaubYV" +
        "oEKVqgEpSlAyUychZW5XLYtyS5tzY0VcroFYVhW263R1tuXWOsoUVs6Ogo2rY5ZnTKbrOWTFGsbYJNajXMa5VFbFktubXNXDbc2n" +
        "di5aKpNFctG1c25zJWnOxUWjVFYih3Vty1BtyVijo3QdbYrSFCq1dNitWzrbOs5dDoyjJHNqo25Ra5sa5jQVqNOdY1GNW5uajRua" +
        "rlWK5roVUaNc1ubUYsTnXLbFsVRrlsa5VjVc2shbG1FuVublVwto1zW5tEWoijbd3bG2xRrlrnLVzG2Nyq5rUmNjYtgtGqMYqNnd" +
        "axtXLHddyt0x1nV3B0x1jLuFKylBTu5t1nR1qx01ZrubUboUdbrdNujOtsdFFbMrctc1rm0Wxtc3OUVo2tzmytnQo6NR01Cm2rF3" +
        "YrYrCts6bbqiszps6oJBW3TFbUdKzS3TKzcNk22NRtFuVzVy3NZNZWHW26V0KchuRqxuWK0nRyZRWbdbdzqZ1nRsUWjVXMXKo1c2" +
        "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" +
        "hkMTJoMTQyGQwgMINGmRiBhMmE000DO+R+gAi0IyjIgACtKCLRmxpg/Mxg9ZbH5823TUzKyiqjad2rltuWjbpGMW3K3Nq5atzaMV" +
        "otiMW0Vru6xjRt0rFza5sblRuaubc20VsRXKotBqopruOih02XdsysdbLtzJGqNbc1y5ua3LljcxWhckFyTJqhEzMpaBYlLMB6FD" +
        "brptSgdbdUFYpzbVzmzui1HNy2NY50uWxubXLF0jW3ObbXTu6q5tzGtzYqjRRo5q5RuRi2Lmtjljc0VsW1y5q5rm1EbRndrljaNo" +
        "1XNFbrKxW2OjdM6M6XdsrbdDu1zRbGrm1iCndGuUWLUbRubFGwFFbG0Rra7u1RsFGtFbu7JWMVo0UUa5CcrRGtc5iwXNXOatzXKN" +
        "FG6UW5zNQUdNlNqdBWasUIto5VGtzG0W1iNa5bblGK1y3TaubYuFG2M7qNuaNG1RtGoNyrmuai25MyWKbajUrFYU2VhI0ijJbUCt" +
        "WUo1YdM6zdbFbOq2OsZ062OqosW3N3dW1zaCsbYtrkKHTKKKxXWdGymplS2rSLuY6bVyHQ6OhQWlpoVKRMzESzMkzDBKUyylY6Y1" +
        "GUVty7uzrLu2RtnXY5y1yum2KsVHLY2dDrbrVqU2dNjpitlG5tysVpNFFitcjmOaty3K3NzFcrbctuURWLbljRtyjRisYrc5i1O7" +
        "Umzo1YrdbMo2rF3GajKCsK3TOjKN0cqynStzRZ3WLm3KtzG0bUblXLm1w2LUbaLXNy1y3I2Dai3NtFzd3XKiqituDMVayEpDJchX" +
        "IUyEQyFy2Vmdbu7bd3NlNXWFBQq0WubG5tY1zQRtc0WpbCiusx1jdDu7booOthsba5iStGzui7uuFo3Nuad2tc0RbY5q5UVYoVaX" +
        "JWsqVoaQKaFaFMnWKsbbd3U7tjRtwrmtGijcuQG5c21c5c1zbFsbY24aty2NtXN3di5UVk2XdumKN1jorOts6zSwlBo5aucqKiiL" +
        "mxUa1G0YrcrlW5w23NzW5q5aiuWNo1zYtotFzXIoEjdZ1RqbOmo6Hd2EUVuVblG25tjc1zbm25W4bXLm2uWNrdMGi5y1ZHW1F3Dp" +
        "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" +
        "bmObmplruuLVy0Y1zcrbqim1ZtQ0QU6bOmS2i3Lc5tupnduc1crYid06wdbHRqymzpiscjato2iqMWxai23Dc2NQRUas7rc2o2uW" +
        "kzusaNcLYtuWxa5Wtwo1iNc1oLFV02jmLa5jG0W7uZqbXds6ZWKZunRqDpzY1c3Mc5ru6jY2MWtyXdbmuaxgtFGjVctuW1ApSgGZ" +
        "hktZhiUg5KZNmKUFMOsdMpum3WNqp1lNzcp3bu7E7tXNzW5rJtFY20VqKsRtzlootuWubd3W0GLblyNaMVRaNGNFXLltuWsbcrQG" +
        "o1d3bFjVcq5G5Y1d3bmsbYjarmLltcrmI27ruVzajajGrlyuc1Vza5zW5G5ubGsRty3NrlcsY2jY23Laiuba5RtzFjRG2xq5tzFj" +
        "aitFua5aNVGtyK5RqI2ubXNRo2rGxjOusZTVlMUJbdZt1lbHOW53drmsWo5za5yCosUbGK5tzcuVRquVbhsMU2OtnWHTbu5qYrbo" +
        "ytqOthW62U1CR0xRqzOutlbdDrO7s3S7turuOmKUrEjVh06Z0dMrKUym1YrYp10bpnQ3RutqbddbDrDplbUoKrCszm3Na6VumsVa" +
        "jRY0WxtpK3LXKuVjY2uWNrlzFsbRFsVo5YrHOVRrQdHTZ1nW3W2UdDVXW2dCnTNWHWanRlNQrN3crUbGjaxijYtYOWNRc27t2jUW" +
        "NWNsmrlcuUZchXLJTJUoyRoGgpyHIR3auajRrm6WTGSA5UmdMox1t0dNqdZTVtujmtiLXNzU7trlDuxq5WIrBtk2NtFWc61XNc2x" +
        "jYttzUctc5rGrFq5rXNlbKw6zoUzVsXcVtWx1s6OtlMoys7uzrMopnWo2jW5c2xubHIsbRY2tEbFc1aNtc3K5a5y1iVtSnTp0OhQ" +
        "SbKzSasUC7ttWdHdzZ0N1lbatrm5Ro0VYqNtd3VmbFs6Z3cKFdZTrbk1O7qbOjKdHRug5cis3WNWkzorXczpqNTdbDpuis3RjVjF" +
        "FtzWuWxtzW3KuVG5axbGixVy1umxuarlmUbOtnTNW1G6FBd2xZqjc1ysbYxWNFuXKi1ubm0REUlzmo0ai0VGLbhtc1jm5qTbciI1" +
        "zVXLcxtixqsVFsW3LmrctcqiLd3XLc2NRjZFOt1mu7Ko6Yk3Rus6ZzbY2wSba5Rbmg3K3Nrm1y1jmrcjaoDaNrly1XKxubhW5UBW" +
        "uaoK4FGoop1TOtlN0cs2rGsVsW3LVG5bXMVyrGsG1FhLlq4bFyua1G1YZgJkomZg5OS5JRQrkA0rVlNt0M6Y6YpQdNutqMoU26Z1" +
        "im1N0dY1CQXd1UaiNqNY1c2u7rHK3LpWuc1iru7bmN3cVubG2irlzYijY2NRVjc1c2jlqjc20UbG3NaNzQFuVXLRFa5aublc1uDo" +
        "UzodYdFCt01KZTljbdNYK6ajajcsbVyt1lMd3KzOtlG6bUzdHW0tFQbac6tG2NjaNtyud3GU6NTbrCsru4oys1Fblc1oLUVSbHNc" +
        "0bYxWLc5axtioua5XNy25bcsasVd12ormg3LURkhTatm3WoboXdnTOs3VHNXKLaNc7ug1dKijFYrYs7ttzaLUQVkNAllampTN0HW" +
        "dMdOts5uVcixblsbmpKoo25ty1Do6N1t0KNqFbU1BtsbGq5uVoJLFci13djpsoahRtW62OmKw6xyZW63Wyug5Y13Nt02dco25rmr" +
        "ctXCsYiLmua1Chum6Sym3TU6zpWDraus66Z1isFFZkh1sqLboWrm5WDbFuVp3bXNtGuVFbm7u3NaMUBc3ChVYdCmZ0yWzuc3TOXL" +
        "UW3LcquGdxc2Na5qxtrmqK3NctGNtcLW5saubcxti1c23NscrlaNROZhSUiGSuQJk5DS0qZIZNFYsanda5Zztjc3IiW1Y6ZTbrMU" +
        "ZRWFG3WtwxWxqi0axaMctjGtyqLG7utJbFGiDlVzbljcxrY2tzFRq5uW5YLctzaLaLUWLGtFc2t0x0Z3dusSDobrOjaimZ0FMoEt" +
        "To6KbKzbudqTaOaxzVuVjaIrltOttW26zdYpTdDljVh3dWHWylctnQdVlBTUdLXMaZVytGsaubRFtGwUUCRIlDSZK5ImnX9LIBS0" +
        "qqUgsQAtACUilK/VA5KC0qFCKFBxZFDIaVUqkT+Egm9gDSVSgFoFKGlShQf1SIPUwAaQq0oBQhQpWNRnvIY8dYbwFg1MrN9SwZ1s" +
        "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" +
        "2bputiWGo5QVtzXNbcoty3CDVRVFVctyjbctG3Ci2DUWot0tW5c2gouFti26hMgMlApMhpXJXJTLJWmhWKwrGrZq23JW6GU1zG5G" +
        "2NFtGirpYqOc1o2rm1jmrc5rEbWuVFi3NXK5sbm5rcubmyUVGbdOmUp3camzrZ1nRyarAWsVRy1c2LmtrmsXObUY1qNuVbnLcyaC" +
        "23C1Rc1BtRy0bXLY13drXM7osRXNzVzlRtja5rXLXNd3auY22LbmjW5GqLY25aKxbctrlc2jVytzO7psoKCtnW2dYkMpktitzbFc" +
        "qE1GotiMYjbRc1jm2SrnNbku7RYq5uu7bloxoojnKNo20W3LljY2i1zciLFRt11s7udM3TdzmatWdbZ0ZGNUa1c25oircquYLRax" +
        "qNaLRzlO61cndtzbmMkoVyaVTJHIMgKclSks62ubaMVcttkrloja5c6DIckCkoWlChoVyVyUcdMY3MWNzbmxquVo25W3WDkdbK22" +
        "rCtRIdZ0xTdCWpuRsrZStlMUsRqd226Vtza5dIuG0Em2uVuW2K0Wo1jGum5XWzrUU2ooLu3WHTFM3NRh3bRuUVGK1zbcpCNFi5rc" +
        "qK0Yja50q10JkiUUiGTkJVI5GQU6dObW2xTKzbZTZ9aByMeYmMdA0CNKlKC0o9DKgHXRpKAowrZmrNRvizY/hjeBY2oFoSkAoDfZ" +
        "gjSI/DCg7iQHSVoRoAClF7CQORBpIJSLSKlCjSFChu5EyEGlaU6+DbrNvHWzrbKBRitsrDUDQipSghSUqjQKUitNsrM1YFH/UdDU" +
        "21NvWWLy5RTSEoRQKQBKEWgV4t68ZI6SAUIhSpSIUqUqbfOYytqbY83Nuts/lgdMrDz8A5CicCUNxKAaSojSrSBS2U285Y6G1Y95" +
        "ZuhTeohmdZimbUVsU2bU1NmEsEpUQ6eEBMkAaaVU305ICphRhTP9LOtjUx/HMzrbdjAGSo7SUclpGgBKFUpR1UZnxe7MVtiqK0tm" +
        "H+EPz5meBZigrD60zdNhQULQFIlIpTSKFAKUKLQM/XszelseBYKFfAmx0xiilArSIL3EquQLwIEMlBocoH8EzptlbN/NGN0ZWBSl" +
        "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" +
        "Ymut/A==")

    // zip -X -Z bzip2 of note.txt, 200 numbered lines.
    private val zip = Base64.decode(
        "UEsDBC4AAAAMAGMZSV0XRo4iWwEAAMMUAAAIAAAAbm90ZS50eHRCWmg2MUFZJlNZFj84ugADtVmAABBABH/gFiVIEEACEOoAFGjI" +
        "GjTI0Imoxn6qpP1QMjFGjIGjTI0EmqlDz9U/VJTQYnMxMTrdW8fHLSAUdANjsDQZBkGg7A2OgFEgFoACjoBsdgaDIMg0HYGx0Aok" +
        "AtAA7/cCBOoECfqBAnECBPqBAnyevRz69euc581VVVVVxiEwAeLu7u7u0xiJnGImcQWQWQWQWQWQWQWQ3GdtttdbbbfIECbtVVVV" +
        "VXzjzzjzzzzzy3W2299Kqqqqq7jbe222tuttvfKqqqqqu42299trbdbb38Kqqqqq7jbbfG2ttutvfuqqqqqruNttudrbbdb37Kqq" +
        "qqq7jbbbda222698Kqqqqq8LvbbbbdddddddddZVVVVVX5gQJ/YECfUCBP9AgT7gQJ9wIE5gQJ+ECBGBAnIQIE5gQJxAgT8gQJ/x" +
        "dyRThQkBY/OLoFBLAQIeAy4AAAAMAGMZSV0XRo4iWwEAAMMUAAAIAAAAAAAAAAAAAACkgQAAAABub3RlLnR4dFBLBQYAAAAAAQAB" +
        "ADYAAACBAQAAAAA=")

    /** The fixture generator: an LCG choosing words, newlines and long runs of 'z'. */
    private fun generated(size: Int): ByteArray {
        val words = listOf("alpha", "beta", "gamma", "delta").map { it.encodeToByteArray() }
        var x = 1L
        val out = ArrayList<Byte>(size + 400)
        while (out.size < size) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            val r = (x shr 8) % 64
            if (r == 0L) repeat(((x shr 4) % 300).toInt()) { out.add('z'.code.toByte()) }
            for (b in words[if (r < 8) ((x shr 16) % 4).toInt() else 0]) out.add(b)
            out.add(if (r == 9L) 10 else 32)
        }
        return out.subList(0, size).toByteArray()
    }

    @Test
    fun decodesSystemBzip2Output() {
        assertEquals("hello world, hello world", Codecs.BZIP2.decompress(hello).decodeToString())
        assertContentEquals(generated(1_100_000), Codecs.BZIP2.decompress(multiBlock))
        assertSame(Codecs.BZIP2, CodecRegistry.DEFAULT.byZipMethod(12))
        assertSame(Codecs.BZIP2, ArchiveKodec.bzip2)
    }

    @Test
    fun concatenatedStreamsDecodeAsOne() {
        assertContentEquals("hello world, hello world".encodeToByteArray() + generated(1_100_000),
            Codecs.BZIP2.decompress(hello + multiBlock))
    }

    @Test
    fun facadeDetectsBzip2() {
        assertEquals("hello world, hello world", ArchiveKodec.decompress(hello).decodeToString())
    }

    @Test
    fun zipMethodTwelveEntriesRead() {
        ZipReader(ByteArrayRandomAccessSource(zip)).use { reader ->
            val entry = reader.entries().single()
            assertEquals(12, entry.zipMethod)
            assertEquals(CodecId.BZIP2, entry.codec)
            val expected = "bzip2 inside zip, line 0\n" + (0 until 200).joinToString("") { "bzip2 inside zip, line $it\n" }
            assertEquals(expected, reader.read(entry).decodeToString())
        }
    }

    @Test
    fun encoderRoundTripsAcrossBlocksRunsAndRandomisedBlocks() {
        val random = ByteArray(250_000).also { var x = 7L; for (i in it.indices) { x = (x * 6364136223846793005L + 1442695040888963407L); it[i] = (x ushr 56).toByte() } }
        val cases = listOf(
            ByteArray(0), byteArrayOf(65), ByteArray(1000) { 97 }, ByteArray(5000) { (it % 2).toByte() },
            ByteArray(700_000) { (it / 1000 % 3).toByte() }, ByteArray(259) { 1 }, random, generated(1_100_000),
        )
        for (data in cases) {
            assertContentEquals(data, Codecs.BZIP2.decompress(Codecs.BZIP2.compress(data)), "level 9, ${data.size} bytes")
            assertContentEquals(data, Codecs.BZIP2.decompress(Bzip2Encoder.compress(data, 1)), "level 1, ${data.size} bytes")
            assertContentEquals(data, Codecs.BZIP2.decompress(Bzip2Encoder.compress(data, 1, randomised = true)), "randomised, ${data.size} bytes")
        }
        assertTrue(Codecs.BZIP2.compress(generated(1_100_000)).size < 40_000)
    }

    @Test
    fun corruptionIsDetected() {
        for (position in listOf(20, multiBlock.size / 2, multiBlock.size - 3)) {
            val damaged = multiBlock.copyOf().also { it[position] = (it[position].toInt() xor 0x10).toByte() }
            assertFailsWith<ArchiveFormatException>("byte $position") { Codecs.BZIP2.decompress(damaged) }
        }
        for (bad in listOf(ByteArray(0), "BZh".encodeToByteArray(), "BZh0".encodeToByteArray(), hello.copyOf(hello.size - 5),
            hello + byteArrayOf(1, 2, 3, 4), "BZx9".encodeToByteArray())) {
            assertFailsWith<ArchiveFormatException> { Codecs.BZIP2.decompress(bad) }
        }
    }

    @Test
    fun limitsApplyBeforeOutputGrows() {
        assertFailsWith<DecompressionBombException> {
            Codecs.BZIP2.decompress(multiBlock, ArchiveLimits { maxUncompressedBytes = 500_000 })
        }
        val runs = Codecs.BZIP2.compress(ByteArray(700_000))
        assertFailsWith<DecompressionBombException> { Codecs.BZIP2.decompress(runs, ArchiveLimits { maxCompressionRatio = 100 }) }
        assertEquals(700_000, Codecs.BZIP2.decompress(runs, ArchiveLimits.UNBOUNDED).size)
    }
}
