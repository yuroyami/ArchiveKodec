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

One compression layer is removed first, so a `.tar.gz`, `.tar.xz`, `.tar.zst` or `.zip.gz`
opens in one call:

```kotlin
ArchiveKodec.open(tarGzipBytes, limits).use { reader ->
    val entries = reader.entries()
}
```

`open` decodes one layer of gzip, bzip2, xz, lzip, Unix compress, lzop, zstd, LZ4 frames or framed Snappy into its own
buffer and then opens the archive inside it. The policy applies separately to that decode, to
the listing and to each read. A second layer, zlib, raw DEFLATE, LZMA and brotli need an
explicit `decompress` before `open`.

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

Unencrypted ZIP members that are stored, DEFLATE, Deflate64, bzip2, zstd or xz stream: their
output is written as it is produced and their compressed data is read as the decoder needs it, so neither side is bound by memory or a
`ByteArray`. Every other entry is read whole and then written.

## Read the first bytes

`readPrefix(entry, maxBytes)` returns the start of an entry, for example to read an image
header inside a comic book:

```kotlin
val header = reader.readPrefix(entry, 64)
```

ZIP stops decoding stored, DEFLATE and Deflate64 entries once it has the bytes, so a header of
a large entry costs little. Other entries and readers decode the entry and keep its start.
When the prefix covers the whole entry, the read checks it as `read` does. Otherwise it checks
no CRC-32, since it does not see the whole entry.

## Find an entry by name

`entry(name)` returns the entry with that exact name, or null. Names compare as decoded, with
no case folding and no path normalization. When two entries share a name, the last one wins,
as Info-ZIP's `unzip` extracts it, and `entries()` still lists both. ZIP looks names up in a
map built once. Tools disagree on which copy of a repeated name they read or verify, so where
that matters, refuse such archives:

```kotlin
val strict = ArchiveLimits { rejectDuplicateNames = true }
```

Every reader then refuses a listing in which a name repeats, unless every entry with that name
is a directory, with `ArchivePathException`. The stream readers refuse when they reach the
repeat.

## A wrong CRC-32

A ZIP read throws `ArchiveFormatException` when an entry's bytes do not match its CRC-32. A
reader that would rather show part of a damaged document can take the bytes anyway:

```kotlin
val options = ArchiveOpenOptions {
    crcPolicy = CrcPolicy.LENIENT
    crcMismatchListener = CrcMismatchListener { entry, expected, actual -> log("bad CRC: ${entry.name}") }
}
```

ZipCrypto entries stay strict, because there a mismatch is how a wrong password shows.
`ZipReader.verify(entry)` checks an entry's CRC-32 without keeping its bytes, and returns false
on a mismatch whatever the policy.

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
| `type` | `FILE`, `DIRECTORY`, `SYMBOLIC_LINK`, `HARD_LINK`, `CHARACTER_DEVICE`, `BLOCK_DEVICE`, `FIFO` or `SOCKET` |
| `isDirectory` | Whether the entry is a directory. Do not rely on a trailing slash |
| `lastModified` | An `ArchiveTime`: seconds and nanoseconds since the epoch, UTC, at the stored precision, or `null` |
| `lastModifiedEpochSeconds` | `lastModified` in whole seconds |
| `lastAccessed`, `created`, `statusChanged` | The other times, where the format stores them |
| `uid`, `gid`, `ownerName`, `groupName` | The owner, where the format stores it |
| `deviceMajor`, `deviceMinor` | The device number of a character or block device |
| `unixMode` | The Unix `st_mode` with the file-type bits, such as `0o100755`, or `null` |
| `windowsAttributes` | Windows `FILE_ATTRIBUTE_*` bits, for archives made on Windows |
| `xattrs` | Extended attributes by name, with POSIX ACLs as their `system.posix_acl_*` attributes |
| `isSymbolicLink`, `isHardLink`, `linkTarget` | Links in TAR, ZIP, cpio, 7z, RAR, ISO 9660 and SquashFS |
| `isEncrypted`, `encryption` | Whether the entry is encrypted, and the scheme |
| `crc32` | The stored CRC-32, when the format has one |
| `comment` | The ZIP entry comment |
| `zipMethod` | The ZIP method number from the central directory, or `null` for other formats |

What each format stores:

| Format | Times | Owner | Other |
| --- | --- | --- | --- |
| TAR | Modified, to the nanosecond in pax; accessed and changed from pax or old GNU headers | uid, gid, user and group names | Device numbers, `SCHILY.xattr.*` |
| cpio | Modified | uid, gid | Device numbers |
| ZIP | Modified, accessed and created from the NTFS field at 100 ns; otherwise the extended timestamp or the DOS time | uid and gid from Info-ZIP's Unix field | Windows attributes from MS-DOS, NTFS and VFAT hosts |
| 7z | Modified, accessed and created at 100 ns | | Windows attributes |
| RAR | RAR5: modified, created and accessed, Unix or FILETIME. RAR4: modified | | Windows attributes from Windows hosts |
| WIM | Modified, accessed and created at 100 ns | | Windows attributes |
| CAB | Modified | | Read-only, hidden, system and archive |
| SquashFS | Modified | uid and gid | Device numbers |
| ISO 9660 | Rock Ridge: created, modified, accessed and changed | Rock Ridge uid and gid | Rock Ridge device numbers |

Fields a format stores beyond the name, size, mode and modification time are informational: a
malformed one reads as `null` instead of failing the listing. `extract` applies none of them,
and skips devices, FIFOs and sockets. SquashFS extended attributes are not read yet.

ZIP reads the DOS time as UTC when nothing better is stored, and an invalid DOS date gives
`null`. ZIP reports `unixMode` only for an entry made on Unix. TAR and ar add the file-type bits
that their mode fields leave out.

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
