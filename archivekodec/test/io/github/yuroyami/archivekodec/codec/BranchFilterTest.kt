package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.checksum.Crc32
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The xz branch filters (#154, Stage 7) on every target. Each fixture is xz 5.4.5 output of
 * 2 KiB of synthetic code for one architecture, dense in the instructions its filter rewrites;
 * `x86s` and `arms` set a nonzero start offset. The 7z reader shares these decoders.
 */
@OptIn(ExperimentalEncodingApi::class)
class BranchFilterTest {
    private class Case(val name: String, val size: Int, val crc: Long, val xz: String)

    private val cases = listOf(
        Case("arm", 2048, 0xf31ba621L,
            "/Td6WFoAAAFpIt42AgEHACEBDAB4Dowp4Af/B/BdAF6W1WsGKzRZ7TRmiPYrE2ANOePiEbqjTKNJbVEQMXOvqejVOEynJUXXfJutRHQISJCVO0" +
            "pWgWTr+3LrtYsxi804o0IGjDm9FetJTUyFfmcOgC2QjQOuEQTS1L0PbXkHkPZExJw+I7HDliJN6kdHSK1we0ArrMRgKrbedGZrhw6C15ofO+WH" +
            "zmCz/bMsd0skCV9m5fxZt9s2s5ryW4AbE9C+xw34WICw32Sl5SZLwcliLh3COXYWkyfM16Ql7TP9bzqDGsnsX5aJVd5//KY7Z4W94uZ5NOb1KM" +
            "eNsVxgD4e2arcmFLR5DgToHIHf7yOBBHjbNXuiCZfMmJanb8IC8a1d7s7rzh6s5ey8B1zFNLz1UhG/l/PktGmQuy4O6bJA0DGdlXcJtHKhhggX" +
            "PI7XxeWT2wYaSSzcv8iL9r4ad7QMogVFEBuXDIOFdroEeXLDKJpvgrM4Fj23NCxH/20VMjF+AvsFcVDyO4A7TeZYDwX72HrRkVKBK0SrLYtFSN" +
            "XboSgSfAqVqL3cRBwVvRXW/+javOvMCkeqEWHUe3WJ5bBBhPuQnXsK58t+ZiHW7YXb/G+b+a27ImpysR1H1X2ctpw/iO0WKoKpfr5GC4VX7t9X" +
            "RR8TvLGsgu+0P3X5D6X001drmyDT/OeCnY0xK+mSXFgppyaQzBSw1eak5qy9FxJpCmt2ht83sKdM5w2t4sbsvhUcejTwNV6N5ufsV/sZkk/WQv" +
            "v+A90jpieabMvgeWpEZv2qmiEogz83NHfa/JLVSdTxFmnpqWwtXAv/ZS9sZuwZZTZXm1WJdNAg93i3jMt3IkJf+jKGzsbrcoAN2lymNSI8V3aI" +
            "YIPqVXswxf8OTGPZZbtb1G+if+JHBc7T2eY8nd+EXd7FzS+n0q85PpA/hzjxxdJLH3XF9hQXjbBtYcosN8aDB0WbHbS6VuXFD5tOuWVkVD1H1F" +
            "Qe/zOPSTt9rVlwJS5G+Cxb3G9cmho+zPG1653TfLaeJQ1tTPJpjHbDnsLri3nZBmLacmnQvsWHXzGJ89ggI4Ya1SytMLJ9GZmhg1opccX3rJ40" +
            "h3tzgkpy1DKiHoeCHtEnODEcC3Tjm+KG0xTKUclj2qGpT9xjTMDLY3mhHeq8VzWyknv+cF+iWvlIC5ITte9C70r24POl+FhQkhmHRU2zZHyc/B" +
            "/5h+gUWjrVSWuLtcA3H10be96YEHF0dBG+v4mgXYlg8D9yMHIhQjMNaUWV0ekEaXznzrjHDR+tVunM89ELK4FtShIW8zxCUz+phlABUt1ihIdO" +
            "PJ4W+wD3ubhADjwBPYrk04Df8JgK1Tu+MUCjSJVElEkTvzBNdARDWgr/3sl/0pPe3XoCdbwaMNWjhnp4sFSr14O9Coe8LmDtfYYtoFh7lSVU8s" +
            "ePcqXwlDtLY7jBf1nKRt3NUu+47f/fDPpr4stp57fOq7FEG+NAwl/URaR/RWF6QcyFzYosomHo9x+g7Fj5f5PDQYKCsrQJON+JgH99uO4ikqwK" +
            "Uaf5jGSrJ8orLBzpVD/h26O5RAQI0eX30PuWYYUy0cdACkBJKQmuBCi2L3F53BvObTD7yB8MwuSHaEPFDhH1BnH+3H5oy+3AbbSyaR3tudirvh" +
            "9h4jt9wV+xWpEF2wN6ngNYrxnYrBUcfPcGZQwpsQCwstp4YZle0DiiBbrqa7pXzcT86QrJsWZx5KcEv7DYpEJWWV0daCSg32mDBp06jXGWkXbY" +
            "hV1W1AGfpfxbgp8HoiOU9iX6MhmZHoUPd6oX/vNQv+BauwMxiFPiiyVeUXAE5AskGCwQ7rWarldtaxoaHCWv7wZHezjyY0R5Yw5JM5QRjrDd2T" +
            "2p038/kkzuLDnXT8BNCIuPqYe0nRMBFYSugTHlpxO51d49/aiD149+YglK9TC2Mpe4lFxhNmIl+J1kdw1N0nNsT77UW+ozW/P36Ug5HMK9bJEC" +
            "xG50O4GATsx33jYfUMUfd9+1RkemeWzsEgjz93McXw0YuuSaa4QVYITjDa71Ta5V9i5jVhdQ5cLvYy+bzFm0GG0jZ4RiJJ3ehFwJrpsIMdd6/J" +
            "xppSiSJibPfz0HGaUOOaOod6nlBW8JyMjccdUfPyXvKWIkDQroodhxESjq1bw5sxLu/Qlw9vPsH8Hw42l+g4wFNRV6yiGbVlc9GgMk8iJaqun5" +
            "Rl5ore9aBsCY6Onw639olaETNPJDtuexVIN8lUwLr2H6VFj/WAZ/nDCWTDmuxwHK4IpBAdIjNfIU0TNl1BEsv/IYLsH8Wx+JJw+KIQ1PbBbSom" +
            "uqZmeIyqtwpgCjGAHUuUV4kYa1M+HRfzy67Lf/++nwQTzaNheszPFCw9hx1fxFB6kbFfSWPK+17vVA0gejRPpIqWNAOMMc7SZENT+mknqmnnjR" +
            "6CSqDlbLp9CSbBurX+Yxj1pP26y6ZNvQOaie15DbOokmkDmBwI2SzeWOQMSWFLZpVEC9oenPPeDjPlzm/yBnWBS8wvZKufZme7d0pCUpVJZqQf" +
            "xZJUvsJYZFG5wQhVK1+kUTH4hhzv99xGdZE7EPMmAA1oOHcbFUYJ6cdl+SKubSMOeALzFDzU/VUa+WcuJUAMdoW2pT+8lF3+s4JloIyW9HxTNT" +
            "s3m+npgMRO1BbpEMenx8r6N0t53ooHn1FviNHBFgCX/UsEBb0k9Sa+OU/Ae/HrOLJZ0Bf0bxKR/sWPpCMJUbJFcOjwt2E9ECKznmwL1oRVx0gc" +
            "QAIaYb8wABiBCAEAAAocApoD4wDYsCAAAAAAFZWg==",
        ),
        Case("armthumb", 2048, 0x76923e74L,
            "/Td6WFoAAAFpIt42AgEIACEBDACtvNrY4Af/B9NdAAE8stIJ6pkYHVeoCBJwNvS4LuflKNWYHBwVf3mbTn1Ot8Cgs+KTsv3UfwadEbgDkc6vLG" +
            "kdmYG8cmh8Y0+JpCpOaL8dJcAXvk6ermZJsGurtJQbMzgE7ACz4waBZ2Px9k5jD/lUg54vy8ko+G94FepyRbFLfa9Ng3S5ux3dK8Ul/my2nFOw" +
            "rhlBPm1GVBn3mbnZrFrUdIBs8PkX0ukd9aL922eYGgAwf5RflN/K6dkO4Y21BXK7edm2VrxC4rkV88hasDLuAlKorUPEGT5ZCcw4IMuYnjQQO7" +
            "qQOunIfLa1fgtm9XdQ7Fm7RSH/+OYYzHY69p+2RuYXHmhptmlTsKh3Svn+CXguO+yCvsiMV1KvmPvXmNzW5qy7LWgtG+WRb0pD+sXNPsfOxfms" +
            "a0q3UOgloPUlqFDCjlcrZCnRo6RZAPk1vtGNh8S3B+qtKKuyBDzMrJ+J+Xqc5r+kU/QavWmcjF9VGv6wVEz0rRZhA3gJVOQy8C90K4BXsjqvkc" +
            "UjYOCOav/psG7P3DfT85pxta3doidZmqwSh0h6X3UOwMvypbMYgj8q/AGPx8cijEO6SE4o7lh+gIYZ7QVR2kleLGjbmAvnEVlu/hA+i+jZXj+c" +
            "VW4idJvygih36R8/CJPH2ddnKaX2h+lnxqetJVXSl0J1cnKWBeG6bw4vk5k2KueldLVVyEU6Sg19Mqd6UfASny9NAWjxb1E9XObf4wBHiLEE9Q" +
            "2GlBSt37m4+Mq8LcOC27Fd6itNC2TLe6J5CVKkCmArU/EkO7/K1U+tfOtgdxVqQ/SW4oHmOr4XU9xnGNdMZoH6cNcPDDD9CoklD8Q3nDD6x1dV" +
            "2v1lCZ3Zjla+FUruKnLelBoqW66MScM/vrF8eZz4X5KRgAK0ZvmsCI51Rkr+erEV0Adm2NPtr+sykpPWiY5tuXhVmgAOKGWrcM4lRhz62L5/q6" +
            "RAX0M8dtzcZZ5RYIyTiabdzpCu8Fx6riX0IHi/j1f/RHT5xsBTik2W6+3LiHbWcN4Jlu88n4pd0NVpFEpu0dMLnMZsRfpbkZZm16z92Ou7OuHx" +
            "ql46yqyN7JxuqMeAwLu6G7Xf8xj9ix3J52/LjJfmQ3zwriH7zmRS/Ih1yhT6hL2yZMC+dGt0FpntB6rqn/96NViWVmHoH8Hf7YbUXXHqzmOugS" +
            "SKSWA35eaKlL7rvgFluXPsCPY0ehdZgkijYDOSASmlNIK1BL3FvaQOCj/QPuImWo155j6NCRp6HkWPMDQwtLa4KN4teQdhQIFGGNl7gQo+Sgz1" +
            "+gX/5LiRaJV2+PUXIupYG3/+pIGaqpkfp4FW7JSKqwOl3X9459aXgbwZMBMcmv+sUtmHUHvqPkpsdgLcxTHNwUwh3FOvRMAMBjO2wvOdgHizVR" +
            "rHkvpkcAENSrF9DrM87PhaKDA/8VONmhK05JHFIlve6+Ir5KF6d6brGelp81tZyd2b2VrSjfaTn5tXxdenqO4OSsZ1qHQJTRuHHS0nawOsm/7C" +
            "r9Nm5T0f5cNxvSkaiy6hsS0BUID0cf/Z6bO4Th9oPZJlynyOCVYVZvG8E/raNSV6r67jZjIDb95ls5qOQJ11St+L1QK/3oRVzELXvee4UCKX4L" +
            "M08WYl6oaETvmQi/PeTmb2irMAwZjxsAaUmfoxzNFntvtnoIcPwh9NKpRpUJuuN/U7xzZ6rscBrq+H0HspNrfEKdo/0ZXc7vr880tqf0TlHg1w" +
            "3HiiKfS488pBVI+AMi7kAfnOGq/eiY02Il3psDT8Yz+NSyxrXEWloC9ulBLF5UoQY621cBnHfydGxCq0hEE6UhJv0u5u8DgIb1qKT9+MxUzxoQ" +
            "JcW7mfNRNxIWe1NADcu5srykIvKrtASLvwer/zRZRAG6px7yXTOK/qOKRDWsbLBca8QF+ypeF4DlsshXQWPUQEWtpvkZNvGQ7Sec0NxOw5+GUY" +
            "SsPJzcLEuEGrA9WrwiFB0YWSh0P2JiAgp8E3sQR00cgqPFtUXCRFF8qNHIPkwX8SLhV4n7IkoXZzEJ5TGfTtreoICwP0+b2GWt3nGpwR8R9v32" +
            "oTZopIU0rVmVbnKThfhU4V2I+wYi6uRJ5NTx3knXaQ8NbY+cchUQ3b0f+qWtRkw6YmVci5aFOTCHIXGTXtHPOr9RfTM9n5BRmigQG59WBgOPwy" +
            "Fh/cAPpAu5u2E/I/I6NA9KhQpz3XGMZEN2t0+xKb853eC3lzKpoCIgZKNN6w1+vAvo8BA4thV2oYKwKQN86gTUqHx9H/LLEpKocev8lEtH3Pm2" +
            "9/XAsVy9hxWiX/zidwagV5oFqpmjDHn28WkRraK7eYddW+PgIAxAUaWg/jXuM0sxgPJ1BxVrGhOTFPMVTpLIK6XWxrg10Wr6J59uHbKihWr1Xv" +
            "rBZn+2LKFARkBKwq9++deIkI6imKcMSYVEM2zX5iXbcDJI+Owm6mRIH/Ki9nNbpqQ7zqX2cUQmv4XzXK91PA0dFmgzqn0JC72GVtG/mvQTvslu" +
            "HL+R3EeKhGaLAWPdlZCErpRSFddmAUpHKt1BhsFr0FvFWgCaUE+9gibiXlqcunjuMuXrhAUBuvDBuvlFxr1TdjdcAaBRqi95vhaeqMeyV9VxCl" +
            "BzsoUXVUGbkeC/3WBl9LHAg1mvu8ZrcMhbYITCTpI4Oc6KSoNoFaTyNA6XQjj2vQyk8Pl8oAAAB0PpJ2AAHrD4AQAAAGvfnMPjANiwIAAAAAAV" +
            "la",
        ),
        Case("powerpc", 2048, 0xa980ffb3L,
            "/Td6WFoAAAFpIt42AgEFACEBDABzr0Rk4Af/B/hdACS3ECDSV+RdFKmaptgBHvkmjl6MgRXcXuaXyNa15UWPdt8uoL6j+KXy0rtAObyn4q5KfO" +
            "lGKB1b2qR8UQt8nlhrZlEDFPgDD+hHvSJL45Pog3BAmcEgPSWEMfSm/7dWv+TyqXe9ponluHAvPrEqX49mqdiQNRrJ3fJI3TOOo/jpxohuS5dS" +
            "hwjNwGf08waDfid8q2o4OoTEE3Pw+b+I2OyQFat4CcOgFtRrvJjo7GIa/eZZE2ZAs14yGtdEDweq4f+TLxtW2MRYI8ymgrFQANhw6oau5L7OUd" +
            "QSD9o6pT15Oly+FD4vNyIfKoRmzHyrI5kXLWKZWQ85D3Nt3BcbSp1sVdRZslwGaF3UQloEKxpDHKpMLgxmzfLrNV0uoljTxL1MByRF6az0Ho3b" +
            "GS4SoTDMnAh2rUn2CfprdhNE2vTKHuFUjIRiHPz5h04+FOF+lnn1o2sjGZGL/TE62AV+HEc4HV/JDZNHeMjgHQgH150HwKgrIEsOmbPS48iN+3" +
            "Ur6tuTd3B2rO3DlrySvtnCtFa7qwtADsN+S5jP9V/rEX6vX4uHDecY4AKOKsvUvYQyykEzEaSJgeBp6Nof0754wTtVuAD6DkrZF7RvD9eY+fpv" +
            "ZFGUqFWzf9IfQXmoOtTxTmZ9n9ojfy/4KeleCsp1e+BXKpxfrcsVWl67PWrve2yEKNT5qOOuZfkAk4iKI71MCjFrVRl1vUJQ0EEClafe1R8aSN" +
            "YToUlmeDM37slbdH9Ly6IrSBgVQhc/GDBo/0uoG3DmTcLZJVr+9kfsHo26fv4qJ9akuyiHyZvTV35gZbGaNnv9J/T2/0WGspRN7e9MThnNu7wh" +
            "GvjW9Bytce8p9yAOMjI6bS/DpCbbgu/x//PdEjaSh8AdtwCvR57g8k0dew6kYpYFOLOJCxzsLC1nVoA9Vu2QK1HwJmJ1dY/vpt9q+1x/kyLlGS" +
            "A9YN1ZH3Hngz91NZ2P8mgXdaQ4InEIGjsB50hWsmI41xLdgRHIoYQ9tPhOWDWEAwAOuUbrmr5ekvImhBq85ZJ9fBNZcUTBR7oAeaWxr7kMyjjn" +
            "Y5t2+NjRx7+HZJ0TEBlNCXIFFPi3uVwmp5cVLsFHQ8ZaiFoS9fr1RekqamYG/3vAjyzfevAfTrj/up+CXrxE4NcVzVa7eAuTVIXzRO/ZOPouW+" +
            "HpXKAaER2seCA/IDatbTniGO4/7lfWl2lXYQpAkjOhNVx56LLgb0gz3qGs4JOXlzZ1HSprKaFAzqEX81EM8gTpiAGVvHsU08fT4/OC6kJ3bU7k" +
            "KVJhgUeNvZ3Mte7qQtx8BRzPvQONoJjbJ2usWRo24iPo0oi/qOdcTXGB+CHh044kzhBiZwBf1CLLM+29GU17c2OnEqjfObssJiES6RKRT5Tgy1" +
            "WGgmztrwF3bWxHtWLMFY/e67GErLaFwYh2z1DxP6TdOAiPMV/sm4g5JyNPUgrXTGCk41OWS9mZusm3mWfAX9P7veYdRBu4xrIvjmjDFHUVe8tr" +
            "7wxEbtg76esE2AzGUQU5QD+Y7CD9kLl5lm8tbsGjzF8YHwaTLEs+NkmbLe7yEbzXMRkjPN0MTReaovfpqYP2GqjZ8lXy+pmYotJa6hic+gvDJr" +
            "/89kZXXRXrf66GYfGaIcilUGtvp9OME+6ou4y2nm5tb4Y3MKAvxyN+Gs5kkeVYBBvkxYnG589Uawyd0JnnQlxBz/VYRY9u99La0f6Xr44mzcD4" +
            "N79XeTZnLI+ewfj3cfW/a/RJkmCMMRvjqXSgnqjCVPTSMQmHyCQhvu8CWY7Qe7UCVo7Kc6WT9Du54Bzmero5K2RqEWcTWoHNYJmeN74nxQKwvh" +
            "fjY5tU5xaoHs8YYx9uYhNR5cEDfnBYVFsUjCi7JEDqEf48xnyjyfi48jD6itPjrMDFlct4Z+Z044KGs2dWKULpVJ5kQBLaaDVIAqKMXx9SElNl" +
            "m6sM/w7PkG0EHblYoP3lB50ShTkAPxP6h/i2ssyckPcMtSIagBf9+yAbRwD81rQpzQB06vZ2L+aeBfpu2wBTGskA9k3s4Hagk9TkJJZd++yU+e" +
            "nHbojX5IGRrq8zfq6GAx0Um1CoYT+LP4Oj8wY3KTG6gZXKirvXTZ9I83LNJyLXN8m7eZ+Kt8c7Xpl14Nrr7KkS36Fh9ONasiMeLo3ai3HvflEc" +
            "bJIYbmjCTOXwNNnE4qyawTVTLk25FM5zI1DzsQ53E5LBvKQ25JV03Fyg/aoUeN+zEBY1zYK1Eqc8yVm81S+LcQwwBiSeb3NUuv4MKJoEYzhHpz" +
            "1srmw8Ridhi4/Bk7Mlisq7xIZ7/6wPuP9XTSjWeI/EBTAx8musX+wrzNV0++7WdB8Uu5rvbqQmDKTjarmupXB/lelw8hSL2arRu5XulR6ncmTR" +
            "jOMdFi/HjD5F4h04FGkW1tTWHAUZgoSECPk7lbM3jhd8xmVz6WYJ7iZD5C4VxIdYHVjCN3jDpd8edt3YcTBQmJQDdMMy6UPH8oLjFwoLANlzo1" +
            "57QrBjCaxM03Weiib03cDr/SpFWRWb5+Sr0NgBD3Ui+wK+SaULixutGXtKTV8Y0VK92flv0Hto8UTRLqsG/9Vt0oI0QbJKkhiROnWXhjnZ16U8" +
            "vNRmUvmPOTU71pmRuygPUoBFTsgVnB12tN/NTeA4TIANtbOh6KIH+6volpl6yaMu3/v6qr9BOfMGOs/8tdXD4CGlhLtaVxW0sL0vnHLdu7CWnL" +
            "k4EnRkbi9ZMQCz/4CpAAGQEIAQAABXQKxPPjANiwIAAAAAAVla",
        ),
        Case("sparc", 2048, 0xb8330057L,
            "/Td6WFoAAAFpIt42AgEJACEBDAAIb4YT4Af/B/tdAFqxg0DR//QxK81Z4eCBOAa2vHtdKbtMZkMDhe5xxBXp0jHn9C99le42NgOBfjwF9e6E7c" +
            "Uz74o+P8O5BXYjIqlvWoR+61gHuGHWOYgeIVojU3ZH4vxVFAT/6SINr5pKWjHl5yLsB2SRZPbPE06ehll3O7JPl8WKfRPNvdHY0lxIjdNitUIH" +
            "WWAqSSUuKnmaujdyhtaQEfiVdABmBoFe+k0ZfhpX62ThRZ9hUpV9GQlCa9/j8lM7icli+iSwvzHjK0Ui1UuXQ7XiKvLKJaRaAQVKcu1OjYQemi" +
            "EMkiV85f0hP/YL+himg/Ib6Mhzb//bQkyefMo0weoJhTCwJgcmJP0UKqvEFWb56OPYAzrS6wj65oo35VoTB7/agYDIi/iuwfQ+GIx6hfjjrI5t" +
            "9KlUC/w5ukjy1JdHBwz5lFY4F+z5VtVwb7tXtgnBvk6UXJp4MtuewNRV4ffBFKzdrnRlzeMcX7mtKDCKQnFUVdcM2KYlhnBPKTlXt2/H6HXfjd" +
            "+hRtl0B4e/TXbS4LKUJyAc0wAzCPzhSOwIhARCjQmJLdjkIduEmEsa4myz6peouDxPLmRUpMkd0eaYSUPQsW7zvYG2PBXC75f24abHWtoVdGtq" +
            "JxxRv9uE54YDLJ0Qg84mLqnWlcXAj07iyW4WxFLbTUbI05zvNBTwLdFa96baFhh4vLLg9X+dtjkryzm/JLND4aFAbMet5NNn++8F+8qCDT+sHX" +
            "1RbMYQRkWmYyBO+w6bFNAS3dmeSCaTKvKliM7HDe3NyzoNZ8081eT3CSm1VhARW/sGpeUHIA8MwhPOBzae4Ua5riZxOWIf5Om5QEbK9FKYhaW5" +
            "dpt/ShpvM84EwmmnlWjAX6WaEApD3m8MQf4TmI5lX03ZNzwOMWussjHz7Oimu7PguAFGQvWwkBLDx/CSwSd0Oyr5XY2RiW0yc+LTg66ZjXQjKY" +
            "2Rn4pyAJkyX3I5vlWYWcTv99O6xim9pXQa7qHQa2qwEdUguGndDM6TtUpHDWedoRG0sOgQ1dpqTJRm/EQFMmwjGTwA09pRGw/ki18meqC0AcKd" +
            "+E0HPU6HdHb5ys7oa3im9ele+D+nWqWbkTVNLIQhZ8QdgntsP/qh8Kwlu1lbm24OLJn6g2oMbmDhwfY5E5bZ208x8hJrRi0dDwskoUTffylmdX" +
            "kALjmtCejw/i7ki9jW+BdY2ozTg4drfH084jZeM/NX3bKgoBFyxyG9kpXzlW500s7CseTZ7+lLLn3Nivt491PKg3R9z5McSjGW1WJBwHmVqMU2" +
            "bOhcZuzJbxkO/MyUXSLyF9rVmreV5ORqRTkEPuOXd5roe7zMmBDd3Z7sUb7ipFdSIxRXUFKRa9OGMJM3uYb51uqsSjrf2Pj354MXuqXXIU0bjr" +
            "y9Vj7w2vuuc7tGUriRe9EjrALBl2+lylfAdEWd5lpL/eBy4O6cYmzIFzJhkvFG+EliY9DG+eLy0s+TVUsEQX67D6Hb+LtYY1EsFSZYnh7JCOAB" +
            "Ao7s21V50LRgF14fvRTx//oF8qrVtzAESqUDT9pvU9Zl87DcjDqPycbDpiFqedGVEWzpGP78tVb3/69IDxK32hwMs7BVoHHH/JPP+n6LHqaS7L" +
            "mELkLdsusxagOmMz/NvquDuLZZufZSEqrpLUi5/OmJKA4Q3dzdwPX+J1PldFV6g340uk+A9ax7mYW5tuLFB+Z2AXBlvgYTXjakx/pSCZYa/oab" +
            "/nONEYM0m1BamjcY3H3nXcLm+uHRoef1kgCcsoblMNBRwTizAGE8PvVAwRuLz65vEQc06pLb9PUrVyOo2/7XWjd+mHwq3JdaRrzUywoF0KpWoK" +
            "3U7vLUMg+XcjZagIC8M8WY8YuNlZRiCCtYaKAWCDBcTTboqAhbn0Dy+KqVcj/mEZA7Ymj+pLE2H2dczX62fZW1m3kvSXYaVy8sYyJjNknS+ZQH" +
            "aHp/2wcYG1xo0CqX8JY/V6rqVW0TUSZQC11SLc37acVAqx1s4fkfk0Ej54YSneZ7G+mfNeDszk9O57uiJ4J19iS9njqBl95hwrZzc3qQCs2fwK" +
            "ECOtJ2nWbPTXFg9CojapnicvyGoOndnDhfD2cjgxT6KxnI3u42ukb8crgzs17qmyX/j2m7QtRHwfKcgwMFawlBHQhpOxRcL1Pcbiy0PAXAJwGs" +
            "Bh3UmOuAKyoCPwfhAIQLeYM6wiSskJpdrekXkmi6NQDnp6Mp+R2+Cjq/pQgv6SEMHEG9tYBBVjphVAKeLP/a2zWZ2TsuFVvj5f2wm/C1Rgq3z4" +
            "oOhtgCfkQivWmHr3O5cX4MVKIJ8ej4/1sbuW38K8+CkRaHK6bWgoIRL1+zjsJj6CFV21ZlS6/d3DUnwm/kvGJ6Ze6tKaclt3K9XDWZh624LmPZ" +
            "0ws8sasG3Jho317HyN2xKNOemF/ZRRX7JzMoRwDZCBSShSJkOFOAWJRABOKjEF/iHpKSOhcxZoGGA8oDYAgSGEktBAogh+maA6ps8plTZkRsXj" +
            "leZJELhrEAodtkUlcBekZSxdOylbU7UtjDgrcn7kwOU04D1dXmZJlGQfnI78TrJA/qCxMnx/wjuoZZqioSwfL2ksuj6reJOjW1zqCxzjEiq9xo" +
            "RdcbLKu55zxlFQc1Fn9BVYQTWNyf/ffUPzNbXQu8i+gXvx3ulgAFGOyv/A8WguU//VMtLMHPbnwX6pvXRRbqHY/5UjPzQvvviMbWDTbFRSxFey" +
            "PGLAzkTdPz7SZZAAAAVwAzuAABkxCAEAAA+TI4yT4wDYsCAAAAAAFZWg==",
        ),
        Case("arm64", 2048, 0xfb4b1b8L,
            "/Td6WFoAAAFpIt42AgEKACEBDACmHRKV4Af/B6VdAFE6f0Xmb5HtGsn8AaXyYUnClbrfmNxu9s1m4gY3+jlv7PazDueGYJDWRCN1ZZZ0ocOo7l" +
            "8RXaVGrfuNWxP/+wGuAJjH1mphZl7wDSAOsOznfN8mP87eDKrFCmAR60wgejKVGiOa80UdcuXk0kyUtoZ4ydOrw/cbEH+fvi6Kqfg28cMKyVv2" +
            "aPzk6eq6znzakDTfyN4snpvUKOfy+X0HAEj1nMuHAFVbjiMyBEsawyspCHp5z+yYr1owBfjQwttgQE5jQL4vLNZ4pGSrVnR7k00SD0Q46YT44y" +
            "nvlgrY+foQsgzsJutFBWKz3R1HVTqxaTAsAejf01oynRmdk2Ys2+SV3mF5UoSXY819QOiLKFhIgaVlM3/lsJ+im3NU15eba01sMkWtq/fcB+n2" +
            "2Vmmcg+a6fUxseRRdSxPeJ8LWSmrX209toB+y/x1w7p4ZB0BEsc36rtXexf8U232avTaypad9zGOUcbSn7a1zIc5sCLJmc2Wia3cTPK9HobpWx" +
            "Q9dPRtXPnFF742J9bfhDfvqjz6SCGK/b8XU/1+0W/kOdURKKD9iUBIcPbDI2B5PCa4z4uNdUooOBI1cpHfQxxV83DlzvNUcsiX486cQAn5u9WW" +
            "94AT9x8c9CrOcXbCVa8MhGobMGzPuyBNJFGzMKSHMzmZ29QNCXU/J9GvmbgrYgxdSa+LX6KHKXYjwJK4SzdUXXk71J/S90AaOLvKJEn+kNXLTI" +
            "JLUjc1I2M00GCO3r59aGY5PnZ9S86mlEh91NGnY+433FTy/40q5QvqmV3tfUlDys69W99/qM29Fb5xnBmbMNGub9Y8av0o5xNxiRSvB0Y0+JKd" +
            "Jr8VuEnfrfsOi3V/JNElPgayUvozShA6tbL75H7mZiZaSw9RvxJ5a6UOW/Av5s/+WKPAapVoZSQYQzQlnsDcCB7nNn0EtExhWlDAx6sysJk1W4" +
            "YoWEUMeihJ8CmdMpw1Sv8tnS7MkGftuQwu99VDt8a/ZwKQ9viJVnfSCoNxkeLTKLqefEDCL63psn2uqFZZuf675+Hn1qIn26WozEo3nRpJV4ps" +
            "7bdWieuRrrLfOAoYcFz/YONFGlJ6umuNumAsgZvOAgVtuQEoH5u/stBp+Lh546MazmOO+NuXBkAkYrB5JjGR+itnbOvn03N6w3YLCv87a5zucC" +
            "u56EVZkubxyxMQnrCNrNrl9lb+AR2tAvKUGWAKM0ITkELJqZ/btSenNZVihi71AY8YVgXF91dTs1iKNkjlbhxf+CQC7gF/zqrPC0OX+FdQrf7A" +
            "bqDQMGyuE8waiKS4FDb6GBExS0CdYj2RH5TEZey+sY5qWr8Lg73MWyC5YjkB6GXZXJ3K/2Zj5EJbhAGBnCCNUrEUMKfOcK5OPs9RFLsLqJ8YTE" +
            "562VuqJzlJXc2EUL+AoXQROZ44q1rrU2lKSsbMNSI8gmE9ZOnrQqb2IJ9qjRwcJMMAKADztHlWXgL4kSvB2g2FYgq0lgpu8jRmlpp2fSsPvkyb" +
            "ou9Ht7MyLy60pwz5e46pmtBsIF5QD4326CiNe3L0+AU7GA3cJ92JX46KsMbQ6r4orhOCHwAVteeHIPjuhlTdCSVB8OGl9wAmQ78HPAy3RWrQnR" +
            "kzVxlVDnWwe6vZTHDabrWFjs1d3nK9SgvGy6FLaLsqiJj3+SlCjfuKx8lJZwgnBur0TrJi3JKYXO6sbp8ATLu2DganIzS6lvqkYnzWR8ZcoOYH" +
            "ND609UUqqELAtvEd9/wiACF1MfCKEaCXiTWDDSY4QJx5DTv45uQzZOxYOMbtDqbqcKSw5VLSNk367PCBhu/Tm72MhSnNFcoRpVoRh81Zyoj4gy" +
            "7ArDbWB29Obs1kuulxnQINhRlX+BpR31O/639AuBVx+SK/LPrcOzywIoNHrEiB/75tJ2w4l3Fn1qCrgTYBHp8sShbIEz2/B9Ig9+AUnmy5VXo8" +
            "qOOtThT6J1ig0Oi9QPmJ5bqFf9RgCQWcQjNc8/9cmLODo/ldwLfBOXRgEyihcxl0Be8g4+KVfVvvY6Mc/wYlXWffhxgWPKUT/SBPOD4qPZGn8k" +
            "CZgCuyQj4tfJYJWSCY9Mm/OuuW9KvR10bzI1OP5A/uHJds6iSJrH1AkD4ZSo/egRKZaBBohJmZHyUDyLGuz9TI/a0xS/sI/W3oBJRBt6lu+grT" +
            "bT4wFKZxrUdmSCyw9FEQWjuCf+f3eZQ3gL5CKq4yHcbfzxTPf2VgYej7GLRE0nQYqCc/C0yj6JAevgtzoRaop3m58JQN1kRGB8RsaqmgwN82kn" +
            "Y08MBc/F+rf2k4DyytwYrDGBT64Bk54eQzC3mT6WhjVfR+fEcE6EEdzA8smDAamd/utozRlYmTeDLMLhY+xZ89HhF0Wy1Tn1BkS5s1zu8+RbBs" +
            "RBJlwuHn+HmYvYtCMUFvvv9snJdK5LMvu71O0+WRdloZaSWIeeR1hc5HldxKrNVmkiNY22VdzohpNPPF03PRBj7TPjmw9y72ru9i7udloqnTT6" +
            "Zpb+WSGTwvwoL8GxyMSxZhzUik3T4XOoowsIibbnqK1o66vngtEKRoQjoDnjsHDLZ8wR4rTFpPInr89mTbRJhKYBAjp2xL2F/n1wgoKBXAcs2V" +
            "YmhF4pzjWAAAAAAAuLG0DwABvQ+AEAAA7FYuFj4wDYsCAAAAAAFZWg==",
        ),
        Case("ia64", 2048, 0x2cd3c0c1L,
            "/Td6WFoAAAFpIt42AgEGACEBDADd3dDiAQf/skGWgcfAY733PPSpcBOzXzKxAYgabMjsO/hyl0BoRVw8SYMDNAMvwXOgApvA1l5b1/Us1EYGym" +
            "hHMTOHuMJZXzDElW6yx00Klh3yH8Da+Vk3GbxP/lMG4DAZIdio8HNSEagI0x2g8LFT0pzzWOrNXTAmlEs2hc4eS32+Ndg8T1dTqu3V/M2kbIw8" +
            "SCcQ3kxQPEc9TJ6s2uL//lSPqP1SWdwrMp+Kt3s+5Ic7t1i9TV34BvTeQOvHSWczGodw80tSsbI6okEjiVA/qKE5EHaSVjO31wqlSv+scvKOH8" +
            "iWwlFSseovq/EFzsRY6XR4hPBSXd5XjCqc9/wwfE1uYJfJWlFuetl2p+zSUfJggGhGPFi4jNO0tHwR4Nm96JYwQL5akATcJ3M3VhbryiBXiPmI" +
            "XVfC/uNvZrEgho9Tc1A9nVxSCgbo93PXRV21FZnY1rlf2edEY2tl/I6f+INHYGvUXH105LwEsk12BfrOjbARqlQX85DMRgvtMedY5vronCBdHN" +
            "8KdBXtWQD+AMuwGLI6XjLt9HsTiUdRQgn+NrBddVUdCmdPpTk8kG5DEUUgDvhV0Wn5uTOaikvw1Ir62HNEVrLOG1USzYL0f2zYptiLWlmTAp3/" +
            "J84j+JMpdjJo1jlZt0dYmI4COzjmeLMEuCoJUXxtRIL9qoCa8dKMD2CGklozOKIU2628z1YJvSj4uaBWcLf+vrLYprgLzc9NQDMtXjBn6OEc/N" +
            "J7kQAvVOBnW1dZnjzMUbKavLs+R9FgwTdTsfg2k8BEnruYWAZBWDF+UVj9LJ2q44+hl7ykcNAV5VU8G05Fy6Srv6p3JJuA699TkjKCxR4FD++2" +
            "sgxa6LqLWNNmapSbbzeRt7Z1rpBJXV+2VFRWKE+fey9nJhOgUMJbeSO4O3Jl1S9fIbOOsHXhUB2wy+G5xLDruSoToEC6TFqQOiRvawyPCz2wo2" +
            "AQ5OZY/E+mTKSEQfvbTYIAKARpUpkre6ZtRT5LcQxlAaiwvVb3IuJJAQyHWnLQ3tsg9v1alsnich+FI7KQ2pKD4FQ8XlHMUZo/q03PiPahgzCF" +
            "vFUxCVORSDZycDLdiFvQBdVb/fSlzew4YvLF6HYa+HYFULlKEx3VPcBjU5vZDfCYwlLRCuwOYkuVO/Q9O0VIg3xcUFuf5ZDe9If30EDW8LbXXL" +
            "JJINMeI1mGg1dkmghX6lzYgvaC6n39aUMLoHWAp7VTmIkb7n7vN2On26h/QC5wUNNv17+QcAbx3y4sMQiukFqygLd5gzicv9dpP0ggC0NWndXj" +
            "oEfkg+2pG6b6gMO/VzA4iklbLZ6H66BoZWAxTF02ZmqViFezHz0Xlx1owDJT93etyNe0FFqdIu9iuIWvWpbC7fW1nCHJGTPqncjMN13TIUq4w6" +
            "soDBMNFsnIzP9bObfdYP/B6Hr3Hq2Z0KnbUHmQ0Qw2jZc2WV8mpPieDVVYmK9TgAwpeT6tPm0oqRNet/1KA0qFa5UDXNS0UKefW11kSex3R9/5" +
            "J6m+P5g+xlSX5qDGQn5AR8Zc4OJYsXBTsvt5U2dHO6p5Sq6boBZQWxwiaMPWWiyRQnmX60jAEld8DZ11k3LjYH6iAQmw4JBVnagcAuc0PMIYRC" +
            "M/+FB/UNAX5+GyF+8MU12cLtDY11tSvZQ/3V2ZzJi0+ovIipJb0fQnos9cYvsoDhM7AG76UvyhQuK2/l6qI8IliAh0Ul95whLTONVpU1KjM5mg" +
            "vm1cGHuIdCjNlcqLHK9VICptVXg9E98UAFsqhg2AfghyVVISFm2neC0hk73sI8FwEa9fV6XL/rk/QpFT8YHcwKEtXjHWpww8kPK5LZPH2KhWjl" +
            "39SUHRyiun9PDne6uoQrdTuO+LZMk8/YlsTJRtoP6jVbYVUXgNp8r+q+UvNZi4BFzWyAfUs1r5j8h7ANE4GLVU2dbQuYSOEFlAvnmz6DDcWtAu" +
            "EsrZuExsjgb8ZJC+lViWZDHkgab0CfIHycWQT7ZZ12RYvVjnTTM1/cD9gE5bUzGwrHj5VG5GMwQXHQg/KFNRO2Vi2mfs5YJT5+oQKF9RNjXLfB" +
            "SgW0dIGpiaQOeoXDBTtkwxCYczGhk0+uDkslL397uvDuPJERImlOWYxqFQ0aKA4Cp/6YVG9aoMmNDJVxci0PMNGU9vMNMZfYBaGVY5lnESV+RC" +
            "qqGejr5IVeFVOAzy0fi5YJQDCd07IMSRUjcDEcAdKtJd+94AHVBEdVoWs6n76al/NEKGAIwI+0VUU5roSwxZbNQpMDT38KaUUxBsc+KzjWyq5X" +
            "3a9+g0Y138AVkxq0p22PcWtTHotHBQ3BNBDjWjMkOonLRuSI2gX73UQzO/XYBGeT0+p4hAlltSm6pDrUrtagDOw+KA75hXuNxQkAfoD62fVbHs" +
            "sBF5Udx9HjoEORiqZxG9IcikGF4Rmx7mrjnI/AK9OhGIQlZccVPlhpR2UEUT2rvlMAuWXvnCE9AeNhpZFTOUQegVcV1TV9w8S1knvzLa92koFm" +
            "NSeJ0Xcal/expdGaD8GEunW/D8yXlt3omML5+SQFj/+1Vy+sr3I1pOYlEVu89wWzpS9s+n/Hwero3RIjYv8C8SVrhCsW1PXG8HBg6Mabg/b15Z" +
            "0o22pTZGuInqrd1YqrZXsOBHxI5vf8xYt3PoiP3YWFK7LSEG0zCWiun1SDjRT1iZJMdIKNFeTxYaTeJIKdxe/biZGaAWvOGHFB3COKFRWP2aMR" +
            "j3R8AjG8zqtxAu+lUAwcDTLAABlBCAEAAAQQI91D4wDYsCAAAAAAFZWg==",
        ),
        Case("x86s", 2048, 0x36c78bf3L,
            "/Td6WFoAAAFpIt42AwEEBAAQAAAhAQwAh0vlS+AH/wfXXQBTpUyNVLwqexX/oTU4qGSLNiOOHxS1T3LayqSAupeYiG9EB4RgAAJ7kidJGoIC0X" +
            "/BG8i+l9fHaE5HK43Y86d0QLYHHW3lZJxT6ckph0dQgvrQ/terTl2rVrzzo2EzPRluO3zBjPW2n0mi3hxPxeYR/Xtz+wGYujHC9/0y45ypnykq" +
            "hB/PCa3BgGO6H7fDBI255QZXBAzxQ3KlfmnK92SUZbiB+5/6b3SH2TzJWOZ05t9bIavrw3qT+t1pdwwOfqRjK95PMlP4Fe6CY5Cc4A0WkfXp1Z" +
            "pUfXe3ei3sU1ylRYIG4Cusy7pojC7g5cBz/UrYWhXyY8hGba7AfJ8k8n5zrz4Sq+xca+TONMDWL1ZHcU/8xNepv/Xw9BRDkuG7kp9/BXvkvKPT" +
            "mn3nONXN3LwvqFRYghYwnEEgtcgKhH/RNq5dYkIxJi/6J+3Rs2CjVOmjF5Mjvmd4vvZ3SCcsLhlUPreFSOFgj4YXXpBFRj+rBJ3EKLlOBiiZpT" +
            "zGkoe2upZTP+F+s+aoNkaO5o2/04WHTKCt2cgf5kWROHfT4glM6q1CMw5U5Rev0qL/rsyqSPOYkEQKIiM6CVheBnhsV0tus9KD62ptIInlq3/Q" +
            "HkV2SrCm19+9N56XslOe/yx//V3tKDTgajPoyHxHtlmobFX0Q5wD8fo+iszJFpWavUpmjIAXAofgKDIt/F+X5370a3OnwZTYsMq0YHAVvsRUBO" +
            "cBzlNgj56OyQzpnUPGxRBMdWK3LNLgbjrVaDB4c95MG2VtK021HWBq37434SlT1vVotqAYfz2lLKRylQZ0fW1xjYEVL31Re1cNrQHUfOGa55/w" +
            "+EdFUAn9qDJF7I+8C0YXMQPhOBE+WUt4Ianpt9U8baz169uIJiEHMhK8yp6hJ2D5YCuqDTEuV+GInv9kyLnTctnN7bR4iqdcQ/+FwENyuButgU" +
            "LPGfXe9AoXkXlvxJRl+j79uy8XCD7My2hQ/jIH6GqFbxqviPHEmUPsX0E7Sj+4AOLmre3a2uxO8WU+eGSMYcv6WzQVG9vBXGNxJwAxdTwPP2P7" +
            "jYJQLVeDtUrKrfiIusGp7bay7sL0fJlUnTk8bGFLjAkHZorb36zuL3CoxlAmFfPjz78l3mWgAxphPvR9vUzkPrTEKCTr8M36eie1gHSjh3uTqD" +
            "BcbwY3qf4jYuTXgCcaowkIABJ9/iowJxKGqYKWWXq8ZvT9ZWMwSbsvetkIqPN6kmXQHTat+b92z5KCSqtliDOKhI3ZzxvJkd0tXI9YlHknp9Uz" +
            "Q835HCBTknxeM7vfOwGy2iM/1XsHcHq1l1QTiJsBVTuOL7ilkzQZXq8uxNCrvrldBJj/IwMeVEg6JSYaZiTEEm8ostuFUrpMFmwVCt1rzZlaGg" +
            "c9EwY+VJSj12+Rm6s1SDjcvB8/nJW2Q3hKEwaVykbCx4/EKk0Z0pvAA0RrJPhacjS/51HoN4FlmQowgT8dzbY6kk/RIHJVApcoE6rXnULw8LgC" +
            "Y3EI+246+QPYj6HT2pf0yXnJNlq1KzSaHU3NppsDcNnd6vWyU8CqJ1jlaP2efoqQc1AYaGMyaqOxu3kJIcxLAXbXmviUmXd5GkODd9TANlIJpr" +
            "Bj/1o58KmadNtrlu0I6ld54v49vcl5aZHhn9bx5fmlSpe19/ZkdKTPEegbj9s/b+CR6QzCU5RIDnaS+rXB+C8o6sYOGqWoEXBdUcM21mr6q3h9" +
            "kUMoMA/hTbiPB+x0ePSF/QkESXIjb+Z3odGC2BXmYCuFsw5Pi34uddo4082XvSgTHRM0SFyFu0Wvqi68JDyxSRULLlZm8d8B7yJCEUMPNo9F3X" +
            "mZIXo3dARTDo+NR/bUbHxvDKW8AD9n5BjImKLzxrAvQ5C1LA7lFGxx391JGvsXlNUSKH4n6ILbppr7RVmJMBU/kNCIOSHPKJGPTLyj8bksjzT9" +
            "w2cjLhmz3WfqADrY5r4eyW82PoiG4IfoSUm9PsAwuz6qQRF+qJcsm8RmmSM+yCDrK7sgU0kcF+0A5qRetCykkkTRjMBQ4aTabFjJMRhs1h55tK" +
            "WlhIXWuy2DlavAkUD/3kesYBeuPsx2Tn/bLcNmksXstgnSMrlo4qlhAvSFaDOQ50DHZrc7qHgiD4xVmC8EqT/KXYMOuEs8f4CXdXG33lIj33bH" +
            "q9hW/JPXXpnsWebd0qFJ5EhmGtVUdITklTM7euAu8gmcF5VCGGxZM5nZ95+qW3gknVZjWvGeXxt8FzjFKlgqAR2fdnhn1yZxKLg/njcK+3SCWl" +
            "a0IJ4dR5TCipQXQI/fAPl4v4hnuQqbcJvEgaL0UfSVEbpuZqTrrPppyxCpa3pqdMBnoXmQfYxbSWRsYOaPzWYt0JcN8BsaiUhicF3x67StC0KP" +
            "bV/jBsR98e1GlzapL2TYbWjUbda87egY5fc7/4/+0/PKhlHCcNSlYETpFDt7JOPkcWt+g0OmWBVXEd3dhaufODUB2qEM7uFuz4U7gGiS7H2qq3" +
            "0e5Ltj+1CM6PFj/1Mgbc+cjxsslkhx7T0MqfdgAKaU4mUU1js77m9muGUce82I5tCXtEW711u+ObhOIIPyD1iPvg2hPdD6HzjMlDovmfS9leb3" +
            "Ryk5lCxdcFghNyiTlgj6sDtGJTQOh9T9kppp42WtMPK7/eR3gm4Ta7RYCT3c1CMTNbi18Znl70VgKgprIQAAAPOLxzYAAfMPgBAAAPA9fCM+MA" +
            "2LAgAAAAABWVo=",
        ),
        Case("arms", 2048, 0xf31ba621L,
            "/Td6WFoAAAFpIt42AwEHBEAAAAAhAQwAp66R7uAH/wfxXQBeltVrBis0We09sBliqOsBqXsXlzHV3TqrHHoJ+NF7tB4rTaZ9bzABl/Zx+IaJDe" +
            "3aeLIfxNAtTMDuQBFFEcgiLhiGA3/RBFFwU4/A/HTAg/TsSsiIjcelAMYRbkfs5ZRoaGYcrfLh9Dhnt+zXO80laOXJg5c1DHmMOPjrLlHtUNVu" +
            "4P4PWjSYnsJGhRZzahccNFcFU6Z7vzuDc0NDZ0vxG6xC5TyvM4ZfYLSX6ULsI313K0rV3FoJcblq+KXR+EsWDIUMOTGQGwsSwjeV7sKp8Kb+NO" +
            "1Kgk1JBcxJ5/uBc0aYygipVhO+C57iwFZaRhnDlqznL9ZwO8zryAAjomqzkDBEWU9AI/gFiBVkag5r8aGjtsb24MaEn7pegAn7CazKp7ioBDhL" +
            "RPDj3Ul94wuDFaFHZv704VWBWyg4NH+4HorHCB/QwH65bdFVVo7DDs4E2SEIDi+mQrsESgk/soK9epIkc1OYCLvZ2UQuYE5CX3yJg+IZIGoHaJ" +
            "tH22GVLCjKfeCHb/zGuZSX4sWSuLN91I5XjPJ564q5XAJVM6UzunGThteLi/vZiV2jZ46HgT/ok36BL2q1CVkaiB38aTOszTLhQWRISqsDnf3X" +
            "/UveJzBs1vylbkawqUetRAQYC5zBZdsram1+CJ3fTehW0mLYZGf2v7R3TC4IMzHjw5M61bR340Dn9nK2aLYaGv2H1IPzOQ2H3LNCzvEzk94Wmf" +
            "UZ3fwvDMYp0oqQV7apz4beSwp/ohUN4sBj0/ZKcEGjSExEyszbk34WtiLFMVdtysL2+tKBwzUvo63Qg/BKFrj5QgiHXL+uvExj41vsR+DtD3Mg" +
            "kCVmcMM7uCEkjON1KXOBknAC+SiubrsKS4U3QoIFdjGv/IpckkP8V27kMZaDNphk3jee8hyZN1O5LLDRvECO9/Y7GXa2iTETD+Q8bhWEOPm6oD" +
            "VXZOnDf1tlOgO2w/JZ2se4gpMwyliTg+ANrV7vCJUmMa7LbWlcAeCehKFJDskRhyTpzL4w/zhHBM8ceyv06unm85WXlJyMfW8KDHYiuSULFqyQ" +
            "ZSAeDvpTab0Z9FYzPdfL+BIMoymrCfCpM39XIiu7CvMFW1xzHdqj1YoxVGZyxHoxYaFU1INFWgCU4PqPaKojFhE6itX92tWfwJSJdYfxitJqf6" +
            "pXyYd17edagh4J8AJvhrH9odVr4mq6OF/0U0TMYnGDXl0xfQXchRnomMpFRFtam3lt/PdcA+9PNbDtTKjRtT4YNlkHaAfmjFhJIwoZ5he6D5Am" +
            "96LrbXdXmaWIJKqe8CiY6TgmW9cO4X+R+wp8PMQL1oCxlMcf8yVrATJQm4VHanC26Nw+4eqnNAT5hlQJxBZGxxUrQ4DsN1gBlk7XuuvXWLguqz" +
            "ysFLFviX2y8PGt9Atd4K6jkeWNk1bf9YrJ2APuaWqwZc7FySs4CJTzS6E3ETeCurcKjTMDBhi+Op//2sEjF78CDOPeoK4ysH/bweEUAun+uLTN" +
            "9RrUfgzpCU1SpUisLlP8/QljmTEnqeBc3sisOdf1H/W3hiy5IuI813FQukP4EQv4uHJBg17Wvl7KIkX06010DxHR3xazSELOyhPvvyaEp2AXY2" +
            "HSpXwgxuGzZTJiD1RtElE2UV3Z9fYIW3pJojTnU6myX/V25/3KaOPUKcELfnuNS82Eb2PJNvsTRUZO4vNKs3y9rN3r9JLeSSg4foGpSymlJNeP" +
            "3zLgmlMl0tsX4enYR+SBQrJzHdUqDLUEenvdgoF15ezPtYnKYYzEziJS+gJGJs9PghugQXBaP/1ZvWbbee4crfUOnXL+3MOhuYnCCM/45GoAVN" +
            "Z+AdHLR15xop03HpcZZaYFnL7lZzxSPxWRDMnSg915UbZVSEFh+Ex26zd6Spa8BJnI2kxh/RdFqwlMuU4hSGhy8YZ1DuvJTt0aXKlzfwiIN8be" +
            "IEe/Qy1h8a1ymGgrgyYKvjoTzovzQ9DjnwwEcEySrkpa18GE/3D+6X8HGSFO8St6jrP5gGKbczouIUoeE362YClALmnAO+BDsQwqDBCJsev4op" +
            "bWCje3uCgeZNGIAmpMjf5bqz6kcJEgV9RwNVRwFKgY+zkf+e2Z+zegFPUH+hhEXCtVJTsW+w8OGGvVYoIdhm1meAzCn/j0weJY8c+qBHm6W0ut" +
            "6MLCFpBV570VwEeI8OZaIhcOFgqFhO7ySduASsAHxu3p3NG4xw5YpB0Bj85zPuAxTr+1QSSm9gkiTCQWCOEeeUTExW2ua0oOX1IUmwQCGSdUcu" +
            "z700x984U5Qba+PLXXqU2IIqWOc0Yx7HppmN6oPu7jVz1WmjQ3iWYcS+9AAauwc8l4XUekjEuI0dPJM13vxPhenaVvzJduYHe5KLzMmsnhzMFA" +
            "VT1tJT6p8V7heeFtCRTLWloNoG9p7LQds2WhZlvBEw4wOHn6NGfpIj5cCAwiLTgxp8H20QgN4HyhwVrgO/1bhSsngqWLshT8RazeTxK0sn9A/m" +
            "LFvS/a42CMckdspl9AgTn51sDxWhR1ICZ1tTmlPXozE0CM7/uZ7zDW+xvsDh9ggnnQnmjyd7F7Qy2T0bsUhe58scC6wB6hF4x/ILTWkTYHIehd" +
            "bYDbAHBS2ORVjUZkBZUNrdrHee3BGTPFnMT7lLRWa65/HZ1P6CpVoMtC0UBstFR9tW9K/tYBow3kRmEFfPUeYiayirfNkAqoSG/I6ke8kd0eNh" +
            "83dVg72gAAAAAhphvzAAGNEIAQAAASUeTwPjANiwIAAAAAAVla",
        ),
    )

    @Test
    fun everyBranchFilterDecodesTheReferenceToolsOutput() {
        for (case in cases) {
            val data = Codecs.XZ.decompress(Base64.decode(case.xz))
            assertEquals(case.size, data.size, case.name)
            assertEquals(case.crc, Crc32.of(data), case.name)
        }
    }
}
