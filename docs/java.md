# Java

Java uses the JVM artifact directly. The library exposes static utilities such as
`Gzip.compress(bytes)` and `ArchiveKodec.open(source)`, overloads in place of Kotlin's default
arguments, and fields such as `ArchiveLimits.DEFAULT`. Every method that throws declares its
checked archive exceptions, so Java callers must catch or declare them.

```java
import io.github.yuroyami.archivekodec.ArchiveKodec;
import io.github.yuroyami.archivekodec.archive.ArchiveEntry;
import io.github.yuroyami.archivekodec.archive.ArchiveReader;
import io.github.yuroyami.archivekodec.archive.ArchiveReaders;
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter;
import io.github.yuroyami.archivekodec.checksum.Crc32;
import io.github.yuroyami.archivekodec.security.ArchiveLimits;
import io.github.yuroyami.archivekodec.wrapper.Gzip;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class Example {
    public static void main(String[] args) throws Exception {
        byte[] original = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] restored = Gzip.decompress(Gzip.compress(original));
        long crc = Crc32.of(restored);

        ArchiveLimits.Builder settings = new ArchiveLimits.Builder();
        settings.setMaxUncompressedBytes(1024 * 1024);
        settings.setMaxEntries(100);
        byte[] zip = ZipWriter.write(Collections.singletonList(
            new ZipWriter.FileSpec("hello.txt", restored)));
        ArchiveReader reader = ArchiveKodec.open(zip, settings.build());
        try (AutoCloseable resource = ArchiveReaders.asAutoCloseable(reader)) {
            ArchiveEntry entry = reader.entries().get(0);
            byte[] content = reader.read(entry); // uses this reader's limits
            System.out.println(new String(content, StandardCharsets.UTF_8));
        }
    }
}
```

## Where things are

- Codec instances are fields such as `Codecs.GZIP`, or getters such as
  `ArchiveKodec.getGzip()`. `codec.decompress(bytes)` uses `ArchiveLimits.DEFAULT`.
- A checksum updates with `checksum.update(bytes)` or `checksum.update(bytes, offset)`.
- Builders replace Kotlin's lambda configuration: `ArchiveLimits.Builder`,
  `GzipHeader.Builder` and `ZipWriter.FileSpec.Builder(name, data)`, each with `build()`.
- The top-level functions live in `RandomAccessSources` (such as
  `RandomAccessSources.readFully(source, offset, length)`) and `ArchiveReaders` (such as
  `ArchiveReaders.asAutoCloseable(reader)`).
- In `archivekodec-io`, the adapters live in `ArchiveKodecIo`, such as
  `ArchiveKodecIo.readAllBytes(source)` and `ArchiveKodecIo.decompress(codec, source)`.

## Modules and bytecode

The jars hold Java 8 class files (major version 52), compiled against the Java 8 API.
`archivekodec` and `archivekodec-io` declare the JPMS module names
`io.github.yuroyami.archivekodec` and `io.github.yuroyami.archivekodec.io` in a
`module-info.class`. `archivekodec-crypto` declares none, so Java takes
`archivekodec.crypto.jvm` from the file name.

A Maven project depends on the platform artifact, as [Install](index.md#install) shows.
