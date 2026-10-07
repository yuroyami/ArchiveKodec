package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.Store
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.test.Test
import kotlin.test.assertTrue

class JvmErrorContractTest {
    @Test
    fun interfacesAndConcreteMethodsDeclareTheSameArchiveContract() {
        for (type in listOf(Codec::class.java, Store::class.java)) {
            val method = type.getMethod("decompress", ByteArray::class.java, ArchiveLimits::class.java)
            assertTrue(ArchiveException::class.java in method.exceptionTypes, method.toString())
        }
        for (type in listOf(ArchiveReader::class.java, ZipReader::class.java)) {
            val method = type.getMethod("entries")
            assertTrue(ArchiveException::class.java in method.exceptionTypes, method.toString())
        }
        val method = Gzip::class.java.getMethod("decompress", ByteArray::class.java)
        assertTrue(ArchiveException::class.java in method.exceptionTypes)
    }
}
