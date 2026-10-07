import Foundation
import ArchiveKodec

func bytes(_ values: [UInt8]) -> KotlinByteArray {
    let result = KotlinByteArray(size: Int32(values.count))
    for (i, value) in values.enumerated() { result.set(index: Int32(i), value: Int8(bitPattern: value)) }
    return result
}

var caught = 0
func expectFailure(_ label: String, check: (ArchiveException) -> Bool = { _ in true }, _ operation: () throws -> Void) {
    do {
        try operation()
        fatalError("Accepted invalid input: \(label)")
    } catch {
        guard let failure = (error as NSError).kotlinException as? ArchiveException else {
            fatalError("Unexpected error contract for \(label): \(error)")
        }
        precondition(check(failure), "Wrong error category/context for \(label): \(failure)")
        caught += 1
        print("Caught \(label): \(String(describing: failure).prefix(512))")
    }
}

let limits = ArchiveLimits.companion.DEFAULT
let empty = bytes([])
expectFailure("unsafe path", check: { $0 is ArchivePathException && $0.entryName == "../escape" }) {
    _ = try PathGuard.shared.sanitize(entryName: "../escape")
}
expectFailure("gzip header", check: { $0 is ArchiveFormatException }) { _ = try Gzip.shared.decompress(input: empty) }
expectFailure("zlib header", check: { $0 is ArchiveFormatException }) { _ = try Zlib.shared.decompress(input: empty, limits: limits) }
expectFailure("raw deflate", check: { $0 is InflateException }) { _ = try Inflate.shared.inflate(input: bytes([7])) }
expectFailure("LZ4 header", check: { $0 is ArchiveFormatException }) { _ = try Lz4.shared.frameDecompress(input: empty, limits: limits) }
expectFailure("LZ4 content checksum", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 11 && $0.message?.contains("content checksum mismatch") == true
}) {
    _ = try Lz4.shared.frameDecompress(input: bytes([4, 0x22, 0x4d, 0x18, 0x64, 0x40, 0xa7, 0, 0, 0, 0, 4, 0x5d, 0xcc, 2]), limits: limits)
}
expectFailure("Snappy preamble", check: { $0 is ArchiveFormatException }) { _ = try Snappy.shared.decompress(src: bytes([128]), limits: limits) }
expectFailure("zlib dictionary", check: { $0 is ArchiveUnsupportedException }) {
    _ = try Zlib.shared.decompress(input: bytes([0x78, 0xbb, 0x35, 0x29, 0x06, 0x6b, 0xcb, 0x00, 0xf1, 0x01, 0x06, 0x2c, 0x02, 0x15]))
}
expectFailure("Codec protocol", check: { $0 is ArchiveFormatException }) { _ = try ArchiveKodec.shared.gzip.decompress(data: empty, limits: limits) }
expectFailure("unsupported container", check: { $0 is ArchiveUnsupportedException }) {
    _ = try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: bytes([66, 90, 104, 57])))
}
expectFailure("ZIP entries", check: { $0 is ArchiveFormatException }) {
    let reader: ArchiveReader = ZipReader(source: ByteArrayRandomAccessSource(data: empty))
    _ = try reader.entries()
}
let oversizedName = String(repeating: "x", count: 65536)
expectFailure("ZIP filename", check: { $0 is ArchiveFormatException && $0.entryName == oversizedName }) {
    _ = try ZipWriter.shared.write(files: [ZipWriter.FileSpec(name: oversizedName, data: empty, codec: CodecId.store)])
}
let countedZip = try ZipWriter.shared.write(files: [
    ZipWriter.FileSpec(name: "first", data: empty, codec: CodecId.store),
    ZipWriter.FileSpec(name: "second", data: empty, codec: CodecId.store)
])
for offset in [countedZip.size - 14, countedZip.size - 12] {
    countedZip.set(index: offset, value: 1)
    countedZip.set(index: offset + 1, value: 0)
}
expectFailure("ZIP directory count", check: { $0 is ArchiveFormatException && $0.message?.contains("count") == true }) {
    _ = try ZipReader(source: ByteArrayRandomAccessSource(data: countedZip)).entries()
}
let splitZip = try ZipWriter.shared.write(files: [ZipWriter.FileSpec(name: "a.txt", data: bytes([65, 66, 67]), codec: CodecId.store)])
splitZip.set(index: splitZip.size - 18, value: 1)
expectFailure("ZIP multi-disk metadata", check: {
    $0 is ArchiveUnsupportedException && $0.offset?.int64Value == Int64(splitZip.size - 18) && $0.message?.contains("multi-disk") == true
}) {
    _ = try ZipReader(source: ByteArrayRandomAccessSource(data: splitZip)).entries()
}
let missingZip64 = try ZipWriter.shared.write(files: [ZipWriter.FileSpec(name: "a.txt", data: bytes([65, 66, 67]), codec: CodecId.store)])
let zip64Central = missingZip64.size - 73
for index in (zip64Central + 24)..<(zip64Central + 28) { missingZip64.set(index: index, value: -1) }
expectFailure("ZIP64 entry metadata", check: {
    $0 is ArchiveFormatException && $0.entryName == "a.txt" && $0.offset?.int64Value == Int64(zip64Central + 51)
}) {
    _ = try ZipReader(source: ByteArrayRandomAccessSource(data: missingZip64)).entries()
}
for (offset, value) in [(Int32(8), Int8(8)), (Int32(30), Int8(98)), (Int32(6), Int8(1))] {
    let inconsistent = try ZipWriter.shared.write(files: [ZipWriter.FileSpec(name: "a.txt", data: bytes([65, 66, 67]), codec: CodecId.store)])
    inconsistent.set(index: offset, value: value)
    expectFailure("ZIP local field \(offset)", check: {
        $0 is ArchiveFormatException && $0.entryName == "a.txt" && $0.offset?.int64Value == Int64(offset)
    }) {
        let reader: ArchiveReader = ZipReader(source: ByteArrayRandomAccessSource(data: inconsistent))
        _ = try reader.entries()
    }
}
let encrypted = try ZipWriter.shared.write(files: [ZipWriter.FileSpec(name: "a.txt", data: bytes([65, 66, 67]), codec: CodecId.store)])
encrypted.set(index: 6, value: 1)
encrypted.set(index: 46, value: 1)
let encryptedReader: ArchiveReader = ZipReader(source: ByteArrayRandomAccessSource(data: encrypted))
let encryptedEntry = try encryptedReader.entries()[0]
precondition(encryptedEntry.isEncrypted)
expectFailure("ZIP encryption", check: {
    $0 is ArchiveUnsupportedException && $0.entryName == "a.txt" && $0.offset?.int64Value == 6 && $0.message?.contains("encryption") == true
}) { _ = try encryptedReader.read(entry: encryptedEntry, limits: limits) }
try encryptedReader.close()
let overlapHex = "504b03041400000000000000210021f8f5ad2d0000002d0000000100000061504b03041400000000000000210024e8d09f0e0000000e00000001000000625348415245442d5041594c4f4144504b010214001400000000000000210021f8f5ad2d0000002d00000001000000000000000000000000000000000061504b010214001400000000000000210024e8d09f0e0000000e00000001000000000000000000000000001f00000062504b050600000000020002005e0000004c0000000000"
let overlappingZip = bytes(stride(from: 0, to: overlapHex.count, by: 2).map {
    UInt8(String(overlapHex.dropFirst($0).prefix(2)), radix: 16)!
})
expectFailure("ZIP overlapping extents", check: {
    $0 is ArchiveFormatException && $0.entryName == "a" && $0.offset?.int64Value == 31 && $0.message?.contains("overlap") == true
}) {
    let reader: ArchiveReader = ZipReader(source: ByteArrayRandomAccessSource(data: overlappingZip))
    _ = try reader.entries()
}
expectFailure("ar entries", check: { $0 is ArchiveFormatException }) {
    _ = try ArReader(source: ByteArrayRandomAccessSource(data: empty)).entries()
}
expectFailure("source range", check: { $0 is ArchiveFormatException }) {
    _ = try RandomAccessSourceKt.readFully(ByteArrayRandomAccessSource(data: empty), offset: 0, length: 1)
}
do {
    _ = try ByteArrayRandomAccessSource(data: empty).read(offset: 0, into: empty, intoOffset: -1, length: 0)
    fatalError("Accepted an invalid destination slice")
} catch {
    guard let failure = (error as NSError).kotlinException as? KotlinThrowable else { fatalError("Missing Kotlin slice error") }
    precondition(failure.message?.contains("invalid destination slice") == true)
    caught += 1
}
let zip = try ZipWriter.shared.write(files: [ZipWriter.FileSpec(name: "bad", data: bytes([65]), codec: CodecId.deflate)])
zip.set(index: 33, value: 7)
let zipReader: ArchiveReader = ZipReader(source: ByteArrayRandomAccessSource(data: zip))
let entry = try zipReader.entries()[0]
expectFailure("ArchiveReader payload", check: { $0 is ArchiveFormatException && $0.entryName == "bad" && $0.offset?.int64Value == 34 && $0.cause is InflateException }) {
    _ = try zipReader.read(entry: entry, limits: limits)
}
try zipReader.close()
try zipReader.close()
expectFailure("closed ZIP entries", check: { $0 is ArchiveClosedException }) { _ = try zipReader.entries() }
expectFailure("closed ZIP payload", check: {
    $0 is ArchiveClosedException && $0.entryName == "bad" && $0.offset?.int64Value == entry.dataOffset
}) { _ = try zipReader.read(entry: entry, limits: limits) }

// Reuse a bridged Kotlin failure to exercise source cleanup through an
// Objective-C protocol implementation and back through Swift's NSError bridge.
let closeError: NSError
do {
    _ = try Gzip.shared.decompress(input: empty)
    fatalError("Expected a Kotlin failure for the cleanup probe")
} catch { closeError = error as NSError }
let constructors: [(RandomAccessSource) -> ArchiveReader] = [
    { ZipReader(source: $0) }, { TarReader(source: $0) },
    { ArReader(source: $0) }, { CpioReader(source: $0) }
]
for (index, create) in constructors.enumerated() {
    let source = ClosingSource(failure: closeError)
    let reader = create(source)
    expectFailure("reader \(index) cleanup", check: { $0 is ArchiveFormatException }) { try reader.close() }
    try reader.close()
    precondition(source.closes == 1)
    expectFailure("reader \(index) closed entries", check: { $0 is ArchiveClosedException }) { _ = try reader.entries() }
    expectFailure("reader \(index) closed payload", check: {
        $0 is ArchiveClosedException && $0.entryName == "bad" && $0.offset?.int64Value == entry.dataOffset
    }) { _ = try reader.read(entry: entry, limits: limits) }
}
let concreteSource = ClosingSource(failure: closeError)
let concreteReader = ZipReader(source: concreteSource)
expectFailure("concrete ZIP cleanup", check: { $0 is ArchiveFormatException }) { try concreteReader.close() }
try concreteReader.close()
precondition(concreteSource.closes == 1)
let zero = ArchiveLimits(maxUncompressedBytes: 0, maxCompressionRatio: 10000, maxEntries: 100000, maxNestingDepth: 8)
let compressed = try Gzip.shared.compress(data: bytes([65, 66, 67]))
expectFailure("output budget", check: { $0 is DecompressionBombException }) { _ = try Gzip.shared.decompress(input: compressed, limits: zero) }
expectFailure("limit guard", check: { $0 is DecompressionBombException }) { try zero.checkOutputSize(bytes: 1) }
let valid = try Gzip.shared.decompress(input: compressed, limits: limits)
precondition(valid.size == 3)
let safe = try PathGuard.shared.sanitize(entryName: "safe/file")
precondition(safe == "safe/file")
print("Core Swift consumer passed: \(caught) catchable failures and valid decode/path calls")
