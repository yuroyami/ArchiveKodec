package io.github.yuroyami.archivekodec;

import io.github.yuroyami.archivekodec.archive.*;
import io.github.yuroyami.archivekodec.archive.ar.ArWriter;
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter;
import io.github.yuroyami.archivekodec.archive.tar.TarWriter;
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter;
import io.github.yuroyami.archivekodec.checksum.*;
import io.github.yuroyami.archivekodec.codec.*;
import io.github.yuroyami.archivekodec.codec.deflate.Deflate;
import io.github.yuroyami.archivekodec.codec.deflate.Inflate;
import io.github.yuroyami.archivekodec.codec.lz4.Lz4;
import io.github.yuroyami.archivekodec.codec.snappy.Snappy;
import io.github.yuroyami.archivekodec.security.*;
import io.github.yuroyami.archivekodec.wrapper.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.GZIPInputStream;

/** Real Java calls, executed by JavaInteropTest rather than merely compiled. */
public final class JavaInteropSmoke {
    private static final byte[] DATA = "123456789".getBytes(StandardCharsets.US_ASCII);

    public static void checksums() {
        equal(0xcbf43926L, Crc32.of(DATA));
        equal(0xe3069283L, Crc32c.of(DATA));
        equal(0x995dc9bbdf1939faL, Crc64.of(DATA));
        equal(0x091e01deL, Adler32.of(DATA));
        Checksum[] checksums = {new Crc32(), new Crc32c(), new Crc64(), new Adler32()};
        long[] expected = {0xcbf43926L, 0xe3069283L, 0x995dc9bbdf1939faL, 0x091e01deL};
        for (int i = 0; i < checksums.length; i++) {
            Checksum checksum = checksums[i];
            checksum.update(DATA);
            equal(expected[i], checksum.value());
            checksum.reset();
            checksum.update(DATA, 0, 4);
            checksum.update(DATA, 4);
            equal(expected[i], checksum.value());
        }
        equal(0x02cc5d05L, XxHash32.hash(new byte[0]));
        byte[] padded = "!abc?".getBytes(StandardCharsets.US_ASCII);
        equal(0x32d153ffL, XxHash32.hash(padded, 1, 3));
        equal(0x32d153ffL, XxHash32.hash(Arrays.copyOf(padded, 4), 1));
        equal(0x36b78ae7L, XxHash32.hash(new byte[0], 0, 0, 0x9e3779b1));
    }

    public static void codecs() throws Exception {
        bytes(DATA, Inflate.inflate(Deflate.encode(DATA)));
        bytes(DATA, Zlib.decompress(Zlib.compress(DATA)));
        bytes(DATA, Lz4.frameDecompress(Lz4.frameCompress(DATA)));
        bytes(DATA, Snappy.decompress(Snappy.compress(DATA)));
        bytes(DATA, Snappy.frameDecompress(Snappy.frameCompress(DATA)));
        byte[] gzip = Gzip.compress(DATA);
        bytes(DATA, Gzip.decompress(gzip));
        bytes(DATA, ArchiveKodec.decompress(gzip));
        bytes(DATA, ArchiveKodec.decompress(gzip, ArchiveLimits.DEFAULT));
        check(ArchiveKodec.codec("lz4") == ArchiveKodec.getLz4(), "codec name lookup");
        check(ArchiveKodec.codec(ArchiveFormat.SNAPPY) == ArchiveKodec.getSnappyFramed(), "framed format lookup");
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            bytes(DATA, output.toByteArray());
        }
        Codec[] codecs = {Codecs.STORE, Codecs.DEFLATE, Codecs.GZIP, Codecs.ZLIB,
            Codecs.LZ4, Codecs.SNAPPY, Codecs.SNAPPY_FRAMED};
        for (Codec codec : codecs) {
            check(Codecs.byId(codec.getId()) == codec, "static registry lookup");
            bytes(DATA, codec.decompress(codec.compress(DATA)));
        }
        check(ArchiveKodec.getGzip() == Codecs.GZIP, "facade codec getter");
        check(ArchiveKodec.getCodecs().equals(Codecs.getImplemented()), "facade codec list");
        final ArchiveLimits[] supplied = new ArchiveLimits[1];
        Codec custom = new Codec() {
            @Override public CodecId getId() { return CodecId.STORE; }
            @Override public byte[] compress(byte[] data) { return data; }
            @Override public byte[] decompress(byte[] data, ArchiveLimits limits) {
                supplied[0] = limits;
                return data;
            }
        };
        bytes(DATA, custom.decompress(DATA));
        check(supplied[0] == ArchiveLimits.DEFAULT, "Java implementation inherits default policy");
        try {
            Gzip.decompress(new byte[0]);
            throw new AssertionError("malformed gzip accepted");
        } catch (ArchiveException expected) {
            check(expected instanceof ArchiveFormatException, "catchable checked format failure");
        }
    }

    public static void readers() throws Exception {
        byte[] zip = ZipWriter.write(Collections.singletonList(new ZipWriter.FileSpec("file.txt", DATA)));
        ArchiveLimits.Builder builder = new ArchiveLimits.Builder();
        builder.setMaxUncompressedBytes(3);
        TrackingSource source = new TrackingSource(zip);
        byte[] prefix = new byte[4];
        equal(4, source.read(0, prefix));
        bytes(Arrays.copyOf(zip, 4), prefix);
        bytes(prefix, RandomAccessSources.readFully(source, 0, 4));
        check(Magic.sniff(source) == ArchiveFormat.ZIP, "static source detection");
        check(ArchiveKodec.sniff(zip) == ArchiveFormat.ZIP, "static buffer detection");
        ArchiveReader reader = ArchiveKodec.open(source, ArchiveNameDecoder.CP437, builder.build());
        try (AutoCloseable resource = ArchiveReaders.asAutoCloseable(reader)) {
            ArchiveEntry entry = reader.entries().get(0);
            try {
                reader.read(entry);
                throw new AssertionError("reader policy ignored by short overload");
            } catch (DecompressionBombException expected) {
                check(expected.getMessage() != null, "checked limit failure");
            }
            bytes(DATA, reader.read(entry, ArchiveLimits.UNBOUNDED));
            check(PathGuard.isSafe(entry.getName()), "static path check");
            check("file.txt".equals(PathGuard.sanitize("./file.txt")), "static path sanitizer");
            check(!PathGuard.isRootDirectory(entry), "static directory check");
        }
        check(source.closed, "try-with-resources closes owned source");
        try {
            reader.entries();
            throw new AssertionError("closed reader accepted");
        } catch (ArchiveClosedException expected) {
            check(expected.getMessage() != null, "catchable lifecycle failure");
        }
    }

    public static void buildersAndWriters() throws Exception {
        check(ArchiveLimits.invoke().equals(ArchiveLimits.DEFAULT), "static limits factory");
        check(ArchiveLimits.DEFAULT.copy().equals(ArchiveLimits.DEFAULT), "limits copy overload");
        check(GzipHeader.invoke().equals(GzipHeader.DEFAULT), "static header factory");
        GzipHeader.Builder header = new GzipHeader.Builder();
        header.setName("file.txt");
        header.setHasHeaderCrc(true);
        check("file.txt".equals(Gzip.readHeader(Gzip.compress(DATA, header.build().copy())).getName()),
            "Java header builder round trip");
        ZipWriter.FileSpec zip = ZipWriter.FileSpec.invoke("file.txt", DATA).copy();
        TarWriter.FileSpec tar = new TarWriter.FileSpec("file.txt", DATA).copy();
        ArWriter.FileSpec ar = new ArWriter.FileSpec("file.txt", DATA).copy();
        CpioWriter.FileSpec cpio = new CpioWriter.FileSpec("file.txt", DATA).copy();
        byte[][] archives = {ZipWriter.write(Collections.singletonList(zip)),
            TarWriter.write(Collections.singletonList(tar)), ArWriter.write(Collections.singletonList(ar)),
            CpioWriter.write(Collections.singletonList(cpio)),
            CpioWriter.write(Collections.singletonList(cpio), CpioWriter.Dialect.CRC)};
        for (byte[] archive : archives) {
            ArchiveReader reader = ArchiveKodec.open(Gzip.compress(archive), ArchiveLimits.DEFAULT);
            try (AutoCloseable resource = ArchiveReaders.asAutoCloseable(reader)) {
                bytes(DATA, reader.read(reader.entries().get(0)));
            }
        }
        Throwable cause = new IllegalStateException("source failure");
        check(new ArchiveException("failure").getCause() == null, "short base exception constructor");
        check(new ArchiveIOException("failure", cause).getCause() == cause, "short IO exception constructor");
        check(new ArchiveUnsupportedException("failure").getOffset() == null, "short unsupported constructor");
        check(new ArchiveFormatException("failure", 7L).getOffset() == 7L, "short format constructor");
        check(new ArchiveClosedException("failure").getEntryName() == null, "short closed constructor");
    }

    private static final class TrackingSource implements RandomAccessSource {
        private final byte[] data;
        private boolean closed;
        TrackingSource(byte[] data) { this.data = data; }
        @Override public long getSize() { return data.length; }
        @Override public int read(long offset, byte[] into, int intoOffset, int length) {
            if (length == 0) return 0;
            if (offset >= data.length) return -1;
            int count = Math.min(length, data.length - (int) offset);
            System.arraycopy(data, (int) offset, into, intoOffset, count);
            return count;
        }
        @Override public void close() { closed = true; }
    }

    private static void bytes(byte[] expected, byte[] actual) {
        check(Arrays.equals(expected, actual), "byte content differs");
    }

    private static void equal(long expected, long actual) {
        check(expected == actual, "expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
