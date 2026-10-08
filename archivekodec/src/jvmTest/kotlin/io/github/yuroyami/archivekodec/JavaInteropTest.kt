package io.github.yuroyami.archivekodec

import kotlin.test.Test

class JavaInteropTest {
    @Test fun checksums() = JavaInteropSmoke.checksums()
    @Test fun codecs() = JavaInteropSmoke.codecs()
    @Test fun readers() = JavaInteropSmoke.readers()
    @Test fun buildersAndWriters() = JavaInteropSmoke.buildersAndWriters()
}
