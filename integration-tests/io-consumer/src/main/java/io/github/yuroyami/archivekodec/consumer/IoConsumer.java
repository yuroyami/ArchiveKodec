package io.github.yuroyami.archivekodec.consumer;

import io.github.yuroyami.archivekodec.archive.RandomAccessSource;
import io.github.yuroyami.archivekodec.archive.ArchiveReader;
import io.github.yuroyami.archivekodec.archive.ArchiveReaderKt;
import io.github.yuroyami.archivekodec.archive.ArchiveEntry;
import io.github.yuroyami.archivekodec.io.ArchiveKodecIoKt;
import io.github.yuroyami.archivekodec.security.PathGuard;
import kotlinx.io.RawSource;

/** Compiles with the IO module as its only direct dependency. */
public final class IoConsumer {
    public byte[] read(RawSource source) throws Exception {
        return ArchiveKodecIoKt.readAllBytes(source);
    }

    public RandomAccessSource wrap(RawSource source) throws Exception {
        return ArchiveKodecIoKt.toRandomAccessSource(source);
    }

    public boolean skipRootDirectory(ArchiveEntry entry) {
        return PathGuard.INSTANCE.isRootDirectory(entry);
    }

    public int entriesInScope(ArchiveReader reader) throws Exception {
        try (AutoCloseable resource = ArchiveReaderKt.asAutoCloseable(reader)) {
            return reader.entries().size();
        }
    }
}
