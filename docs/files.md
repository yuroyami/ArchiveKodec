# Files and extraction

The core never touches the file system, which lets it build for every Kotlin target.
`archivekodec-io` adds a file source, a suspending `extract` call and adapters for
kotlinx-io. It depends on kotlinx-io and kotlinx-coroutines, and exports both to your
project.

```kotlin
dependencies {
    implementation("io.github.yuroyami:archivekodec-io:0.2.0")
}
```

## Open a file

`FileRandomAccessSource` opens a file read-only and reads it on demand, so a large archive is
listed and read without loading it:

```kotlin
ArchiveKodec.open(FileRandomAccessSource("/data/backup.zip")).use { reader ->
    val entries = reader.entries()          // reads only the central directory
    val one = reader.read(entries.first())  // reads only that member
}
```

The reader owns the source and closes it. Small reads go through a cache of four 64 KiB
pages, and larger reads go straight to the file. The size is read once at opening, so do not
change the file while it is open.

| Platform | File API |
| --- | --- |
| JVM and Android | `RandomAccessFile` |
| Apple, Linux and Android native | POSIX `pread` |
| Windows | `ReadFile` |
| Kotlin/JS on Node | Node's `fs` |
| Kotlin/Wasm on Node 20.16 or later | Node's `fs` |
| Browsers and WASI | None. The constructor throws `ArchiveUnsupportedException` |

An Android content URI and any other source without a path needs your own
`RandomAccessSource`.

## Extract entries

`extract` writes a chosen set of entries to sinks. It reports progress and stops when its
coroutine is cancelled:

```kotlin
val passwords = PromptedPasswords { name -> askUser("Password for $name") } // a suspending prompt
val options = ArchiveOpenOptions { password = passwords }
ArchiveKodec.open(FileRandomAccessSource(path), format = null, options = options).use { reader ->
    val wanted = reader.entries().filter { it.name.startsWith("docs/") }
    reader.extract(wanted, progress = { done, total -> bar.update(done, total) }, passwords = passwords) { entry ->
        SystemFileSystem.sink(Path(outDir, PathGuard.sanitize(entry.name)))
    }
}
```

- Entries are extracted in listing order, whatever order you pass them in. A solid block (a
  7z folder, a cabinet folder or a solid RAR5 stream) is then decoded once for all its
  entries.
- Directories are skipped, and each sink is closed after its entry.
- Cancellation is checked between entries and before each write of about 1 MiB of an entry
  that streams, so a large ZIP member stops promptly. Other entries are read whole and then
  written.
- `extract` creates no files by itself. Join names with `PathGuard.sanitize`, as
  [Entry names and Zip-Slip](entry-names.md) explains.

`PromptedPasswords` asks for a password before each encrypted entry. Pass the same object to
the reader and to `extract`. A `null` answer leaves the entry without a password, so its read
fails with `ArchivePasswordException`. The prompt runs once for each encrypted entry, so
remember an answer inside it to reuse one password for a whole archive.

### Links

Symbolic links are skipped by default, because a link to `../..` followed by a file inside it
is the classic way out of an extraction folder. Pass `onLink` to get each link with its
target, and check the target with `PathGuard.resolveLink(entry.name, target)` before you create
anything. Hard links are written as ordinary files with the data of the member they name.

## kotlinx-io adapters

```kotlin
fun RawSource.readAllBytes(): ByteArray
fun RawSource.toRandomAccessSource(): RandomAccessSource
fun Codec.compress(source: RawSource): ByteArray
fun Codec.compress(source: RawSource, sink: RawSink): Long
fun Codec.decompress(source: RawSource, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray
fun Codec.decompress(source: RawSource, sink: RawSink, limits: ArchiveLimits = ArchiveLimits.DEFAULT): Long
```

Each adapter reads the whole source from its current position, and the compressed input is
always held in memory. A decode into a `RawSink` streams the output for DEFLATE, zlib and gzip,
about 1 MiB at a time. Compression is not streamed.

The caller owns the `RawSource` and the `RawSink` and must close them. An adapter flushes a
sink but does not close it, and it does not synchronize access to the source.
`toRandomAccessSource()` drains the source into a `ByteArray`, and closing its result does not
close the original source. For a file, use `FileRandomAccessSource` instead.
