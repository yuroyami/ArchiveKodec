package io.github.yuroyami.archivekodec.consumer;

import io.github.yuroyami.archivekodec.archive.RandomAccessSource;
import io.github.yuroyami.archivekodec.archive.ArchiveReader;
import io.github.yuroyami.archivekodec.archive.ArchiveReaderKt;
import io.github.yuroyami.archivekodec.archive.ArchiveEntry;
import io.github.yuroyami.archivekodec.io.ArchiveKodecIoKt;
import io.github.yuroyami.archivekodec.security.PathGuard;
import io.github.yuroyami.archivekodec.security.ArchiveLimits;
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter;
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod;
import io.github.yuroyami.archivekodec.archive.ar.ArWriter;
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter;
import io.github.yuroyami.archivekodec.archive.cpio.InitramfsReader;
import io.github.yuroyami.archivekodec.archive.cpio.InitramfsSegment;
import io.github.yuroyami.archivekodec.archive.tar.TarWriter;
import io.github.yuroyami.archivekodec.wrapper.Gzip;
import io.github.yuroyami.archivekodec.wrapper.GzipHeader;
import java.util.Collections;
import kotlinx.io.RawSource;

/** Compiles with the IO module as its only direct dependency. */
public final class IoConsumer {
    public byte[] gzipWithHeader(byte[] bytes, String name) throws Exception {
        GzipHeader.Builder header = new GzipHeader.Builder();
        header.setName(name);
        header.setMtimeEpochSeconds(1700000000L);
        header.setOs(3);
        header.setHasHeaderCrc(true);
        return Gzip.INSTANCE.compress(bytes, header.build());
    }

    public GzipHeader inspectGzipHeader(byte[] bytes) throws Exception {
        return Gzip.INSTANCE.readHeader(bytes);
    }

    public long lastInitramfsOffset(RandomAccessSource source) throws Exception {
        ArchiveLimits.Builder limits = new ArchiveLimits.Builder();
        limits.setMaxSegments(100);
        InitramfsReader reader = new InitramfsReader(source, limits.build());
        try (AutoCloseable resource = ArchiveReaderKt.asAutoCloseable(reader)) {
            long end = 0;
            for (InitramfsSegment segment : reader.segments()) {
                end = segment.getEndOffset();
                for (ArchiveEntry entry : segment.getEntries()) {
                    reader.read(entry, reader.getLimits());
                }
            }
            return end;
        }
    }

    public TarWriter.FileSpec tarFile(String name, byte[] data) {
        return new TarWriter.FileSpec(name, data);
    }

    public byte[] writeTar(String name, byte[] data) throws Exception {
        TarWriter.FileSpec.Builder builder = new TarWriter.FileSpec.Builder(name, data);
        builder.setModificationTime(1700000000L);
        builder.setUid(123);
        builder.setGid(456);
        builder.setMode(0755);
        return TarWriter.INSTANCE.write(Collections.singletonList(builder.build()));
    }

    public CpioWriter.FileSpec cpioFile(String name, byte[] data) {
        return new CpioWriter.FileSpec(name, data);
    }

    public byte[] writeCpio(String name, byte[] data) throws Exception {
        CpioWriter.FileSpec.Builder builder = new CpioWriter.FileSpec.Builder(name, data);
        builder.setModificationTime(1700000000L);
        builder.setUid(123L);
        builder.setGid(456L);
        builder.setMode(0100644);
        return CpioWriter.INSTANCE.write(Collections.singletonList(builder.build()), CpioWriter.Dialect.CRC);
    }

    public ArWriter.FileSpec arFile(String name, byte[] data) {
        return new ArWriter.FileSpec(name, data);
    }

    public byte[] writeAr(String name, byte[] data) throws Exception {
        ArWriter.FileSpec.Builder builder = new ArWriter.FileSpec.Builder(name, data);
        builder.setModificationTime(1700000000L);
        builder.setUid(123);
        builder.setGid(456);
        builder.setMode(0100644);
        return ArWriter.INSTANCE.write(Collections.singletonList(builder.build()));
    }

    public ZipWriter.FileSpec file(String name, byte[] data) {
        ZipWriter.FileSpec.Builder builder = new ZipWriter.FileSpec.Builder(name, data);
        builder.setMethod(ZipMethod.STORED);
        return builder.build();
    }

    public ArchiveLimits limits(long outputBytes) {
        ArchiveLimits.Builder builder = new ArchiveLimits.Builder();
        builder.setMaxUncompressedBytes(outputBytes);
        return builder.build();
    }

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
