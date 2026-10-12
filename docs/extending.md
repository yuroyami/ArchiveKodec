# Custom codecs and formats

Two immutable registries decide what ArchiveKodec can decode. A `CodecRegistry` maps codec ids
and ZIP method numbers to codecs, and a `FormatRegistry` maps container formats to readers.
`with` returns a new registry, so `DEFAULT` and every other registry stay unchanged.

## Add or replace a codec

Derive a registry and pass it to the ZIP reader:

```kotlin
fun readWithZstd(zipBytes: ByteArray, zstd: Codec): ByteArray {
    val codecs = CodecRegistry.DEFAULT.with(zstd, zipMethod = 93)
    return ZipReader(ByteArrayRandomAccessSource(zipBytes), codecs).use { reader ->
        reader.read(reader.entries().first())
    }
}
```

A codec with the id of a built-in one takes over all of that id's ZIP methods, so
`DEFAULT.with(myDeflate)` replaces method 8 when `myDeflate.id` equals `CodecId.DEFLATE`. An
explicit `zipMethod` binds a new id to any method number from 0 to 65535. Lookups by id,
name, format and ZIP method all use the registry you pass. The `ArchiveKodec` and `Codecs`
lookups always use `DEFAULT`.

A custom codec must follow the payload framing of its ZIP method, keep its id stable and
apply the limits that it is given. The reader decrypts and authenticates an encrypted entry
before the codec sees it, and checks the output size and CRC-32 afterwards. A registry does
not change the sniffer and does not add a method to `ZipWriter`.

An entry whose ZIP method has no codec reports `CodecId("zip-method-N")`, for example
`zip-method-11`, and its read throws `ArchiveUnsupportedException`. `entry.zipMethod` keeps the
method number, so `codecs.byZipMethod(entry.zipMethod!!)` on a `ZipReader` tells whether a
decoder exists.

## Implement the interfaces

You can call `Codec` and `ArchiveReader` freely, but a later release can add members to them.
A Kotlin implementation must opt in, or the compiler reports an error:

```kotlin
@OptIn(ExperimentalArchiveImplementation::class)
class ZstdCodec : Codec { /* ... */ }
```

Java and Swift do not check Kotlin opt-in markers, but the same caveat applies.
`RandomAccessSource` carries no marker, so implement it freely.

## Add a container format

`ArchiveFormat` is an open value, so `ArchiveFormat("erofs")` names a custom format. Its `id`
is lowercase ASCII. A `FormatSpec` pairs that id with a detector and a reader factory:

```kotlin
val erofs = FormatSpec(
    id = "erofs",
    matches = { source -> // the EROFS superblock magic sits 1024 bytes in
        source.size >= 1028 && source.readFully(1024, 4)
            .contentEquals(byteArrayOf(0xE2.toByte(), 0xE1.toByte(), 0xF5.toByte(), 0xE0.toByte()))
    },
    open = { source, options -> MyErofsReader(source, options) },
)
val formats = FormatRegistry.DEFAULT.with(erofs)
val reader = ArchiveKodec.open(bytes, formats, options = ArchiveOpenOptions { limits = myLimits })
```

`MyErofsReader` is your implementation, and its `format` must use the same id. The rules for
the parts:

- The detector can read at any offset, for example ISO 9660's signature at 32769, so check the
  size before a read. It must not close or keep the source. An exception from it propagates,
  and `false` moves on to the next descriptor.
- The factory gets the limits, the name decoder and the codec registry through
  `ArchiveOpenOptions`, and must apply the limits that fit its format. A `null` name decoder
  means each built-in reader's default.
- Closing the returned reader must close the source. A factory that fails must leave the
  source open for the caller.

The newest descriptor runs first and replaces an older one with the same id. When no
descriptor matches, `open` tries the ZIP footer search. An explicit id skips all detection:

```kotlin
ArchiveKodec.sniff(bytes, formats)
ArchiveKodec.open(bytes, formats, format = "erofs", options = options)
```

The built-in gzip unwrap applies the same registry and options to the archive inside it.

## Build entries

`ArchiveEntry` has no public source position, so a custom reader keeps its own locators, for
example in a list next to its listing. Build an entry with the three-argument constructor and
set the rest through the builder:

```kotlin
ArchiveEntry("dir/file.txt", size = 3, isDirectory = false) {
    lastModifiedEpochSeconds = 1_700_000_000
    crc32 = 0x352441C2
}
```

`entry.copy { name = "renamed" }` gives an independent copy. New metadata arrives as builder
properties, so these signatures stay fixed.
