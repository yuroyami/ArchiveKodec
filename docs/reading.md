# Reading archives

`ArchiveKodec.open` sniffs the format and returns an `ArchiveReader`. The reader lists the
entries from the headers or the central directory only, and decodes an entry when you read
it.

```kotlin
ArchiveKodec.open(bytes).use { reader ->
    for (entry in reader.entries()) {
        if (entry.isDirectory) continue
        val content = reader.read(entry)
        println("${entry.name}: ${content.size} bytes")
    }
}
```

Import `io.github.yuroyami.archivekodec.archive.use` for that `use`. Before you join an entry
name to an output folder, read [Entry names and Zip-Slip](entry-names.md).

## What `open` accepts

`open` returns a reader for ZIP, TAR, ar, cpio, 7z, RAR, ISO 9660, CAB, WIM and SquashFS.
[Format support](formats.md#archives) lists what each reader handles. To skip the sniffer,
name the format:

```kotlin
ArchiveKodec.open(source, format = "tar")
```

The byte-array overloads borrow the bytes without a copy, so keep them unchanged until the
reader closes. The `RandomAccessSource` overloads read on demand. The core ships
`ByteArrayRandomAccessSource`, and `archivekodec-io` adds a file source, as
[Files and extraction](files.md) shows.

A gzip layer is removed first, so a `.tar.gz` or a `.zip.gz` opens in one call:

```kotlin
ArchiveKodec.open(tarGzipBytes, limits).use { reader ->
    val entries = reader.entries()
}
```

`open` decodes one gzip layer into its own buffer and then opens the archive inside it. The
policy applies separately to that decode, to the listing and to each read. Other wrappers,
such as xz or zstd, need an explicit `decompress` before `open`.

When the first bytes match no format, `open` searches the last 65,557 bytes for a ZIP end
record. This opens zipapps and other ZIP files with a prefix. An input that matches nothing
throws `ArchiveFormatException`, and a known format with no reader throws
`ArchiveUnsupportedException`.

## Listing and reading

`entries()` reads metadata only and does not decompress anything. It returns the reader's
cached list, so treat that list as read-only and call `toMutableList()` to change it.

A reader reads only the entries that it listed, or an unchanged `copy()` of one. An entry
built with the public constructor, an entry from another reader or a changed copy throws
`ArchiveFormatException` before any payload is read.

`read(entry)` uses the reader's policy. `read(entry, limits)` replaces it for one read, and an
`ExtractionBudget` caps the total of several reads. [Limits](limits.md) describes both.

## Read into a sink

`read(entry, sink, limits)` writes an entry to a `ByteSink` instead of returning an array:

```kotlin
val written: Long = reader.read(entry, ByteSink { chunk, offset, length ->
    out.write(chunk, offset, length)
})
```

Unencrypted ZIP members that are stored, DEFLATE or Deflate64 stream about 1 MiB at a time, so
their size is not bound by a `ByteArray`. Every other entry is read whole and then written.

## Close the reader

A reader owns its source and closes it. The rules:

- Close the reader also after a failed listing or read. A failure leaves the reader open, so
  other entries can still be read.
- When `open` throws, the caller still owns the source and must close it.
- Do not share an owned source between readers, and do not close it while the reader uses it.
- A second `close` does nothing. After `close`, `entries()` and `read()` throw
  `ArchiveClosedException`. Entries that were returned before stay usable.

The Kotlin `use` closes the reader on a normal return, a non-local return or an exception.
When the block and the close both throw, the block's exception is thrown and the close
failure is added to it as suppressed. `reader.asAutoCloseable()` gives a standard
`AutoCloseable`, and Java calls `ArchiveReaders.asAutoCloseable(reader)` for
try-with-resources.

## Threads

A reader is not thread-safe. Serialize every call to one reader, including `close`. A
`RandomAccessSource` does not need to support concurrent calls either, and a thread-safe source
does not make its reader thread-safe.

## Entry metadata

| Property | What it holds |
| --- | --- |
| `name`, `nameBytes` | The decoded name and the stored bytes. See [Entry names](entry-names.md) |
| `size`, `compressedSize` | The sizes in bytes |
| `isDirectory` | Whether the entry is a directory. Do not rely on a trailing slash |
| `lastModifiedEpochSeconds` | UTC seconds, or `null` when the archive stores no time |
| `unixMode` | The Unix `st_mode` with the file-type bits, such as `0o100755`, or `null` |
| `isSymbolicLink`, `isHardLink`, `linkTarget` | Links in TAR, ZIP, cpio, 7z, RAR, ISO 9660 and SquashFS |
| `isEncrypted`, `encryption` | Whether the entry is encrypted, and the scheme |
| `crc32` | The stored CRC-32, when the format has one |
| `comment` | The ZIP entry comment |
| `zipMethod` | The ZIP method number from the central directory, or `null` for other formats |

ZIP prefers the extended-timestamp field and otherwise reads the DOS time as UTC. An invalid
DOS date gives `null`. ZIP reports `unixMode` only for an entry made on Unix. TAR and ar add
the file-type bits that their mode fields leave out.

## Initramfs

`CpioReader` and `ArchiveKodec.open` read one cpio archive and stop at its first
`TRAILER!!!`. A Linux initramfs is several cpio archives in one buffer, and
`InitramfsReader` reads all of them:

```kotlin
InitramfsReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
    for (segment in reader.segments()) {
        println("${segment.startOffset}..${segment.endOffset}, trailer=${segment.hasTrailer}")
        for (entry in segment.entries) {
            val body = reader.read(entry)
        }
    }
    val allEntries = reader.entries() // the same entries in source order
}
```

Each trailer ends a segment and resets hard links. The offsets are absolute, and the end is
exclusive. The last segment can have no trailer (`hasTrailer == false`), and a segment with
only a trailer stays in the list with no entries. A duplicate name stays in source order, so
this reader does not overlay one file on another.

The reader handles uncompressed newc and crc segments only. A compressed segment fails the
whole listing with its source offset. `maxEntries` counts the entries of all segments, and
`maxSegments` counts the segments.

## GNU thin ar

A thin archive (`ar rcT`, `llvm-ar --thin`) stores the member headers and paths but not the
data. `entries()` lists the members with their sizes and a `compressedSize` of zero. The
library never opens files, so a read needs a resolver:

```kotlin
val resolver = ExternalMemberResolver { path -> fileBytesBesideTheArchive(path) }
ArchiveKodec.open(bytes, FormatRegistry.DEFAULT, options = ArchiveOpenOptions { externalMembers = resolver })
```

The `path` comes from the archive, so treat it as untrusted. A read without a resolver throws
`ArchiveUnsupportedException`. A resolver that returns `null` or bytes of the wrong size
causes `ArchiveFormatException`.

## Errors

Every failure that the input data causes is an `ArchiveException`. Its `offset` and
`entryName` locate the problem when they are known, and `cause` keeps the original failure.
An entry read reports offsets in the archive, not in the entry.

| Exception | Cause |
| --- | --- |
| `ArchiveFormatException` | Damaged or inconsistent data, or a failed checksum |
| `ArchiveUnsupportedException` | A valid feature that ArchiveKodec does not read |
| `DecompressionBombException` | A violated [limit](limits.md) |
| `ArchivePathException` | An unsafe entry name |
| `ArchivePasswordException` | A missing or wrong password |
| `ArchiveAuthenticationException` | A WinZip AES entry whose authentication tag does not match |
| `InflateException` | Damaged DEFLATE data |
| `ArchiveSizeException` | A result that cannot fit one `ByteArray` |
| `ArchiveIOException` | A failure of the source |
| `ArchiveClosedException` | A call on a closed reader |

A wrong argument, such as a negative slice, throws `IllegalArgumentException`. A custom
`RandomAccessSource` or `Codec` must report its expected failures as `ArchiveException`
subclasses too.
