package io.github.yuroyami.archivekodec.codec

import kotlin.test.Test
import kotlin.test.assertEquals

class CodecRegistryTest {
    @Test fun declaredSupportMatchesTheRegisteredImplementations() {
        val registered = Codecs.implemented
        assertEquals(registered.size, registered.toSet().size, "duplicate registry IDs")
        assertEquals(CodecId.entries.filter { it.implemented }.toSet(), registered.toSet())
        for (id in CodecId.entries) {
            assertEquals(id.implemented, Codecs.byId(id) != null, id.name)
        }
    }
}
