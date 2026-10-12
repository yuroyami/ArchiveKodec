# JavaScript and TypeScript

The Kotlin/JS build is also an ES module for plain JavaScript and TypeScript, with a generated
`.d.mts`. It exports a small surface of types that JavaScript has: bytes are `Int8Array`
(Kotlin's `ByteArray`), sizes are numbers, and a failure throws an `Error` with a message. The
Kotlin API is not exported as it is, because Kotlin collections, `Long` and interface default
arguments have no JavaScript form.

## Build the module

The module is not on npm yet. Build it from a clone of the repository:

```bash
python3 scripts/js_package.py
```

The module lands in `archivekodec/build/dist/js/productionLibrary/`, with a `package.json`, so
you can install it from that folder.

## Use it

```js
import { gzip, gunzip, open, zip, ZipMember } from "./ArchiveKodec-KMP-archivekodec.mjs";

// A Uint8Array goes in as an Int8Array view over the same memory, without a copy.
const u8 = new TextEncoder().encode("hello");
const bytes = new Int8Array(u8.buffer, u8.byteOffset, u8.length);

const packed = gzip(bytes);              // level 6; gzip(bytes, 9) for smaller output
const unpacked = gunzip(packed);         // at most 2 GiB; gunzip(packed, maxBytes) for less

const archive = open(zip([new ZipMember("notes/a.txt", bytes)]));
for (const entry of archive.entries) {
    console.log(entry.name, entry.size, entry.isDirectory);
}
const data = archive.read("notes/a.txt"); // or archive.readAt(entry.index)
archive.close();

// And back to a Uint8Array:
const out = new Uint8Array(data.buffer, data.byteOffset, data.length);
```

## Exports

| Export | What it does |
| --- | --- |
| `gzip`/`gunzip`, `deflate`/`inflate`, `zlib`/`unzlib`, `lz4`/`unlz4` | One codec each. A compressor takes an optional `level` (0 to 9 for the DEFLATE family), and a decompressor takes an optional `maxBytes` |
| `compress(codec, data, level?)`, `decompress(codec, data, maxBytes?)` | Any codec by id. `codecs()` lists the ids |
| `decompressAny(data, maxBytes?)` | Detects the compressed format, like `ArchiveKodec.decompress` |
| `sniff(data)` | The format id that the bytes start with, such as `"zip"`, or `"unknown"` |
| `open(data, format?, password?, maxBytes?)` | An `Archive` over any readable format. `format` skips detection |
| `Archive` | `format`, `entries` (an array of `Entry`), `read(name)`, `readAt(index)` and `close()` |
| `Entry` | `index`, `name`, `size`, `compressedSize`, `isDirectory`, `isEncrypted`, `isSymbolicLink`, `isHardLink`, `linkTarget`, `lastModified` (milliseconds, for `new Date`), `crc32`, `unixMode`, `codec` and `comment` |
| `zip(members)`, `ZipMember(name, data)` | Writes a ZIP with DEFLATE |
| `version()` | The library version |

Every decoding call uses the default [limits](limits.md), with the output cap set by
`maxBytes`. Entry names are stored names and not safe paths, so check them as
[Entry names and Zip-Slip](entry-names.md) describes before you write files.

The `wasmJs` build exports nothing. Kotlin/Wasm can export only top-level functions over
numbers, so JavaScript callers use the JS build.

`python3 integration-tests/js-consumer/run.py` runs a plain JavaScript file and a `--strict`
TypeScript file against the built module.
