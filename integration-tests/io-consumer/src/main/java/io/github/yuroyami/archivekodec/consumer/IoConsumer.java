package io.github.yuroyami.archivekodec.consumer;

import io.github.yuroyami.archivekodec.archive.RandomAccessSource;
import io.github.yuroyami.archivekodec.io.ArchiveKodecIoKt;
import kotlinx.io.RawSource;

/** Compiles with the IO module as its only direct dependency. */
public final class IoConsumer {
    public byte[] read(RawSource source) throws Exception {
        return ArchiveKodecIoKt.readAllBytes(source);
    }

    public RandomAccessSource wrap(RawSource source) throws Exception {
        return ArchiveKodecIoKt.toRandomAccessSource(source);
    }
}
