# Swift

Swift apps link one of two XCFrameworks, built for every Apple target of the library:

- `ArchiveKodecKit` holds the library: `import ArchiveKodecKit`. It is not named
  `ArchiveKodec`, because Swift would then hide the module behind the `ArchiveKodec` type,
  and `ArchiveKodec.Crc32` would not resolve.
- `ArchiveKodecIo` holds the library plus `archivekodec-io` and the kotlinx-io types that its
  functions need: `import ArchiveKodecIo`.

Link one, not both, because each one carries its own Kotlin runtime.

## Build the package

The frameworks are not published yet. Build both on macOS from a clone of the repository:

```bash
./kotlin build
python3 scripts/apple_frameworks.py
python3 integration-tests/swift-package/package.py
```

`./kotlin build` compiles the klibs, and `apple_frameworks.py` links the release frameworks and
XCFrameworks from them. `package.py` zips the XCFrameworks and writes a Swift package to
`build/swiftpm/ArchiveKodec/`. Add that folder to Xcode as a local package, or depend on it
from a `Package.swift`:

```swift
.package(path: "path/to/ArchiveKodec/build/swiftpm/ArchiveKodec"),
// and in the target:
.product(name: "ArchiveKodecKit", package: "ArchiveKodec"),
```

To host the package instead, run `package.py --url https://example.com/downloads/`. Each zip
then becomes a remote binary target pinned by its SHA-256, and `checksums.txt` lists the sums.
Upload both zips to that address.

## Use it

`ArchiveKodec.shared` takes and returns `Data`, so a payload needs no `KotlinByteArray`:

```swift
import Foundation
import ArchiveKodecKit

let kodec = ArchiveKodec.shared
let packed = try kodec.compress(codec: kodec.gzip, data: Data("hello".utf8))
let unpacked = try kodec.decompress(codec: kodec.gzip, data: packed)  // or decompress(data:) to detect the format

let reader = try kodec.open(data: zipData)                           // the reader keeps its own copy
defer { try? reader.close() }
for entry in try reader.entries() where !entry.isDirectory {
    let contents: Data = try kodec.read(reader: reader, entry: entry)  // under the reader's limits
    print(entry.name, contents.count)
}
```

For other calls, `kodec.bytes(data:)` turns `Data` into a `KotlinByteArray` and
`kodec.data(bytes:)` turns one back, with one copy each. Kotlin default arguments do not reach
Swift, so the library has explicit overloads without them, such as `reader.read(entry:)` and
`codec.decompress(data:)`. Those use `ArchiveLimits.DEFAULT` or the reader's limits.

A builder's `build()`, the `FileSpec` constructors and `update(data:offset:length:)` on a
checksum throw, so call them with `try`. Swift names the ZIP builder
`ZipWriter.FileSpecBuilder(name:data:)`.

## Errors

Every Kotlin function that throws is a Swift `throws` function. The Kotlin exception rides on
the `NSError`:

```swift
do {
    let output = try Gzip.shared.decompress(input: payload)
    print(output.size)
} catch {
    let failure = (error as NSError).kotlinException as? ArchiveException
    print(failure?.message ?? String(describing: error))
    print(failure?.entryName as Any, failure?.offset as Any)
}
```

[Reading archives](reading.md#errors) lists the exception classes.

## Test the package

`swift test --package-path integration-tests/swift-package` runs a Swift package with XCTest
cases for the calls above. It depends on the generated package, so run the three build
commands first.
