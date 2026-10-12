# Entry names and Zip-Slip

An entry name comes from the archive, so treat it as untrusted. Joining a name such as
`../../etc/passwd` to an output folder is the Zip-Slip vulnerability: the entry escapes the
folder that you meant to extract into and overwrites a file somewhere else on disk.

## The default check

Every built-in reader refuses a listing that contains a name `PathGuard.sanitize` would
reject. `entries()` then throws `ArchivePathException` for the first such entry. That covers
`../../etc/passwd`, `/etc/passwd`, `C:/x`, empty names and control characters. A directory
entry that names the root, such as `./` or `.`, is allowed.

Names that pass keep their stored spelling. A directory keeps every stored trailing slash, as
in `e//` and `./`, and `./a` is not rewritten. Use `isDirectory` to find directories, because a
TAR directory does not need a trailing slash.

To list hostile names as stored, for inspection or for your own policy, turn the check off:

```kotlin
ArchiveKodec.open(source, ArchiveLimits { rejectUnsafeNames = false })
```

`ArchiveLimits.UNBOUNDED` also turns it off.

## Build the output path

The default check stops traversal names, but it does not normalize the names that pass. Join
the result of `sanitize` to the output folder, not the raw name, also when the check is off:

```kotlin
val safe = PathGuard.sanitize(entry.name)     // throws ArchivePathException
if (PathGuard.isSafe(entry.name)) { /* ... */ }
```

`sanitize` rejects empty names, control characters and NUL, absolute paths, Windows drive
letters and any `..` segment. It turns backslashes into forward slashes, removes `.`
segments and returns a relative path.

An archive made from the current folder can hold a directory entry named `.` or `./`. Skip it
with `PathGuard.isRootDirectory(entry)` before you call `sanitize`, because `sanitize` rejects
a name that resolves to the root:

```kotlin
for (entry in reader.entries()) {
    if (PathGuard.isRootDirectory(entry)) continue
    val target = PathGuard.sanitize(entry.name)
}
```

`isRootDirectory` returns `false` for files and unsafe names, so it is not a safety check.
Sanitize every other name.

## Links

`sanitize` checks names, not links. A symbolic link (`entry.isSymbolicLink`) with a clean name
can still point outside the output folder, and a later file written through it lands there
too. Skip links, as `extract` does by default, or check each target before you create the link:

```kotlin
val resolved = PathGuard.resolveLink(entry.name, entry.linkTarget!!) // throws if it leaves the root
```

`resolveLink` resolves the target against the link's folder and refuses a target that leaves
the root. Create links only after every file is written, so no file is written through one.

## Name encodings

ZIP decodes a name as strict UTF-8 when bit 11 is set. Otherwise a valid Unicode Path extra
field (`0x7075`) whose CRC-32 matches the stored name wins over CP437. Two valid Unicode names
that disagree throw `ArchiveFormatException`. A stale CRC, an unknown version or a damaged
UTF-8 payload falls back to the stored name. TAR, ar and cpio use strict UTF-8, and invalid
UTF-8 throws `ArchiveFormatException`.

For another legacy encoding, pass an `ArchiveNameDecoder` to `open` or to a reader. On the
JVM, for example:

```kotlin
val charset = Charset.forName("GBK")
val decoder = ArchiveNameDecoder { bytes -> String(bytes, charset) }
ArchiveKodec.open(source, decoder).use { reader -> /* list and read entries */ }
```

ZIP's UTF-8 flag and a valid Unicode Path field still win over the decoder. There is no
automatic guess of the charset. `ArchiveNameDecoder.UTF8` and `ArchiveNameDecoder.CP437` work
on every target. The 7z and WIM readers store their names as UTF-16 and take no decoder.

`entry.nameBytes` returns a copy of the stored name bytes without padding or terminators. A
TAR prefix and base name are joined with the stored slash. ZIP keeps the header bytes also
when a Unicode Path field supplies the displayed name.
