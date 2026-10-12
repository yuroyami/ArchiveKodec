# Writing archives

ArchiveKodec writes ZIP, TAR, GNU ar and cpio. Each writer takes a list of `FileSpec` values
and returns the whole archive as one `ByteArray`. A writer is not reachable from the
`ArchiveKodec` object, so import it directly.

Every `FileSpec` keeps its own copy of the data, so a later change to your array or builder
cannot change it. Configure one with `FileSpec(name, data) { ... }` or `spec.copy { ... }`,
and with `FileSpec.Builder(name, data).build()` from Java.

No writer makes a name safe for a file system. Each one keeps names, duplicates and member
order as given, and leaves the mapping from file system names to you.

## ZIP

```kotlin
val bytes = ZipWriter.write(
    listOf(
        ZipWriter.FileSpec("readme.txt", text, ZipMethod.STORED),
        ZipWriter.FileSpec("data.bin", payload, ZipMethod.DEFLATED),
    ),
)
```

A `FileSpec` is stored or deflated, and the two-argument constructor deflates. Its
`deflateOptions` set the level and the other [encoder options](compression.md#encoder-options).
A spec can also record a time, a Unix mode and a comment:

```kotlin
val script = ZipWriter.FileSpec("run.sh", bytes, ZipMethod.STORED) {
    lastModifiedEpochSeconds = 1_700_000_000
    unixMode = 0b1000000111101101   // 0o100755: a regular, executable file
    comment = "build script"
}
val zip = ZipWriter.write(listOf(script), "archive comment")
```

- A time is written as a UTC DOS time, clamped to 1980 to 2107 with odd seconds rounded down.
  It is also written as an Info-ZIP extended timestamp when it fits a signed 32-bit value, and
  readers prefer that field, so the exact second survives.
- Without a time, both headers carry the reproducible 1980-01-01 00:00:00.
- A mode marks the entry as made on Unix and stores `st_mode` in the high 16 bits of the
  external attributes, as Info-ZIP does.
- Comments are UTF-8 and must fit 65,535 bytes.

Names are strict UTF-8 with bit 11 set in both headers. The writer checks every name before
it compresses anything, and throws `ArchiveFormatException` for a backslash, a leading slash,
a drive or device prefix, a `..` segment, an ASCII control character, malformed Unicode or a
name longer than 65,535 bytes. A colon is a prefix only in the first segment, so later
segments can hold one. The writer keeps `./a`, repeated slashes and trailing slashes as given.

The writer uses ZIP64 end records when there are more than 65,535 entries. Sizes and offsets
always use the classic 32-bit fields, because a `ByteArray` cannot reach 4 GiB.

To encrypt the files, pass a `ZipEncryption`, as [Encrypted archives](encryption.md#write)
shows.

## TAR

```kotlin
val tar = TarWriter.write(
    listOf(
        TarWriter.FileSpec("dir/", byteArrayOf()) {
            isDirectory = true
            mode = 0x1ED // octal 0755
        },
        TarWriter.FileSpec("dir/file.txt", bytes),
    ),
)
val tarGzip = ArchiveKodec.gzip.compress(tar)
```

`TarWriter` writes regular files and directories with ustar headers. A name above 100 UTF-8
bytes goes into the ustar prefix field when a split fits, and into a GNU long-name record
otherwise, so no name is cut. An empty archive is two zero blocks.

| Setting | Default | Rule |
| --- | --- | --- |
| `isDirectory` | `false` | A directory has an empty body. A name that ends in a slash must be a directory |
| `mode` | octal `0644` | Permission bits only, from `0000` to `7777` |
| `uid`, `gid` | 0 | Seven octal digits |
| `modificationTime` | 0 | Unix seconds, zero or more, eleven octal digits |

Empty names, NUL and malformed Unicode are rejected. Links, devices, pax records, sparse files
and binary number fields are not written. Compression is a separate codec call, as the example
shows.

## GNU ar

```kotlin
val archive = ArWriter.write(
    listOf(
        ArWriter.FileSpec("debian-binary", "2.0\n".encodeToByteArray()),
        ArWriter.FileSpec("control.tar.gz", controlTarGzip),
        ArWriter.FileSpec("data.tar.gz", dataTarGzip),
    ),
)
```

`ArWriter` writes GNU headers, even-byte padding and a `//` table for long names. It keeps
UTF-8 base names, spaces and duplicates. It rejects empty and dot names, slashes,
backslashes, colons, ASCII control characters, malformed Unicode and the reserved names of
the symbol index. The settings are `modificationTime`, `uid`, `gid` and `mode`, and the
defaults are zero and octal `0100644`.

No symbol index is written, so run `ranlib` when a linker needs one. BSD names and thin
archives are not written. For a Debian package, the order and contents of the members are your
job.

## cpio

```kotlin
val archive = CpioWriter.write(
    listOf(
        CpioWriter.FileSpec("etc", byteArrayOf()) { mode = 0x41ED }, // directory, 0755
        CpioWriter.FileSpec("etc/config", configBytes),
        CpioWriter.FileSpec("config", "etc/config".encodeToByteArray()) {
            mode = 0xA1FF // symbolic link, 0777; the body is the target
        },
    ),
)
```

The default dialect is newc (`070701`). Pass `CpioWriter.Dialect.CRC` as the second argument
for `070702`, whose checksum is the unsigned sum of the body bytes modulo 2^32 and not a
CRC-32. Both dialects end with a `TRAILER!!!` record. odc, old binary and compressed output
are not written.

The settings are `mode`, `inode`, `uid`, `gid`, `linkCount`, `modificationTime`,
`deviceMajor`, `deviceMinor`, `rdevMajor` and `rdevMinor`. Each must fit an unsigned 32-bit
field, and `mode` holds a Unix file type with its permissions in 16 bits. The default is a
regular file with mode octal `0100644`, one link and zero for the rest.

- Directories and special files have empty bodies.
- A symbolic link's body is its target, which must not be empty or hold NUL.
- Hard links share a device and inode number with `linkCount` above 1. You choose which member
  carries the data, and a group with mixed file types is rejected.
- Empty names, NUL, malformed Unicode and the name `TRAILER!!!` are rejected.
