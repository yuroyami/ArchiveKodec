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

func expectArgumentFailure(_ label: String, prefix: String = "invalid checksum slice:", _ operation: () throws -> Void) {
    do {
        try operation()
        fatalError("Accepted invalid argument: \(label)")
    } catch {
        guard let failure = (error as NSError).kotlinException as? KotlinIllegalArgumentException else {
            fatalError("Unexpected argument error for \(label): \(error)")
        }
        precondition(failure.message?.hasPrefix(prefix) == true)
        caught += 1
        print("Caught \(label): \(failure)")
    }
}

let checksumInput = bytes(Array("123456789".utf8))
let checksumSuffix = bytes(Array("456789".utf8))
let checksumFactories: [() -> Checksum] = [{ Crc32() }, { Crc32c() }, { Crc64() }, { Adler32() }]
let invalidChecksumSlices: [(Int32, Int32)] = [(-1, 0), (0, -1), (1, Int32.max), (0, 10)]
for (index, create) in checksumFactories.enumerated() {
    let checksum: Checksum = create()
    try checksum.update(data: checksumInput, offset: 0, length: 9)
    let before = checksum.value()
    for (offset, length) in invalidChecksumSlices {
        expectArgumentFailure("checksum protocol \(index) slice \(offset),\(length)") {
            try checksum.update(data: checksumInput, offset: offset, length: length)
        }
        precondition(checksum.value() == before)
    }
    try checksum.update(data: checksumInput, offset: 9, length: 0)
    precondition(checksum.value() == before)
    checksum.reset()
    try checksum.update(data: checksumInput, offset: 3, length: 6)
    let expected = create()
    try expected.update(data: checksumSuffix, offset: 0, length: 6)
    precondition(checksum.value() == expected.value())
}
expectArgumentFailure("concrete CRC32 slice") { try Crc32().update(data: checksumInput, offset: 0, length: -1) }
expectArgumentFailure("concrete CRC32C slice") { try Crc32c().update(data: checksumInput, offset: 0, length: -1) }
expectArgumentFailure("concrete CRC64 slice") { try Crc64().update(data: checksumInput, offset: 0, length: -1) }
expectArgumentFailure("concrete Adler32 slice") { try Adler32().update(data: checksumInput, offset: 0, length: -1) }
for (offset, length) in invalidChecksumSlices {
    expectArgumentFailure("xxHash32 slice \(offset),\(length)") {
        _ = try XxHash32.shared.hash(data: checksumInput, offset: offset, length: length, seed: 0)
    }
}
let suffixHash = try XxHash32.shared.hash(data: checksumInput, offset: 3, length: 6, seed: 0)
let copiedHash = try XxHash32.shared.hash(data: checksumSuffix, offset: 0, length: 6, seed: 0)
precondition(suffixHash == copiedHash)

let limits = ArchiveLimits.companion.DEFAULT

func makeLimits(maxUncompressedBytes: Int64 = 2147483648, maxCompressionRatio: Int32 = 10000,
                maxEntries: Int32 = 100000, maxNestingDepth: Int32 = 8) throws -> ArchiveLimits {
    let builder = ArchiveLimits.Builder()
    builder.maxUncompressedBytes = maxUncompressedBytes
    builder.maxCompressionRatio = maxCompressionRatio
    builder.maxEntries = maxEntries
    builder.maxNestingDepth = maxNestingDepth
    return try builder.build()
}

let policyBuilder = ArchiveLimits.Builder()
policyBuilder.maxEntries = 5
let policySnapshot = try policyBuilder.build()
policyBuilder.maxEntries = 6
let policyRebuilt = try policyBuilder.build()
precondition(policySnapshot.maxEntries == 5 && policyRebuilt.maxEntries == 6)
var retainedPolicyBuilder: ArchiveLimits.Builder?
let policyCopy = try policySnapshot.doCopy {
    retainedPolicyBuilder = $0
    $0.maxUncompressedBytes = 7
}
retainedPolicyBuilder!.maxUncompressedBytes = 9
precondition(policyCopy.maxUncompressedBytes == 7 && policyCopy.maxEntries == 5)
precondition(policySnapshot.maxUncompressedBytes == limits.maxUncompressedBytes)
let equalPolicy = try policySnapshot.doCopy { _ in }
precondition(equalPolicy.isEqual(policySnapshot) && equalPolicy.hash() == policySnapshot.hash())
let invokedPolicy = try ArchiveLimits.companion.invoke { $0.maxEntries = 5 }
precondition(invokedPolicy.isEqual(policySnapshot))
precondition(ArchiveLimits.companion.UNBOUNDED.description().count < 200)
for (name, configure) in [
    ("maxUncompressedBytes", { (builder: ArchiveLimits.Builder) in builder.maxUncompressedBytes = -1 }),
    ("maxCompressionRatio", { (builder: ArchiveLimits.Builder) in builder.maxCompressionRatio = -1 }),
    ("maxEntries", { (builder: ArchiveLimits.Builder) in builder.maxEntries = -1 }),
    ("maxNestingDepth", { (builder: ArchiveLimits.Builder) in builder.maxNestingDepth = -1 })
] {
    let builder = ArchiveLimits.Builder()
    configure(builder)
    expectArgumentFailure("negative policy \(name)", prefix: "archive limits: \(name)") {
        _ = try builder.build()
    }
    expectArgumentFailure("negative copied policy \(name)", prefix: "archive limits: \(name)") {
        _ = try policySnapshot.doCopy(configure: configure)
    }
    expectArgumentFailure("negative invoked policy \(name)", prefix: "archive limits: \(name)") {
        _ = try ArchiveLimits.companion.invoke(configure: configure)
    }
}
for gzip in [true, false] {
    expectArgumentFailure("negative legacy wrapper cap \(gzip)", prefix: "archive limits: maxUncompressedBytes") {
        guard let failure = negativeLegacyLimitError(gzip: gzip) else {
            fatalError("Accepted negative legacy cap")
        }
        throw failure
    }
}

let fileInput = bytes([1, 2, 3])
let fileSnapshot = try ZipWriter.FileSpec(name: "a", data: fileInput, method: ZipMethod.stored)
let defaultFile = try ZipWriter.FileSpec(name: "a", data: bytes([1, 2, 3]))
precondition(defaultFile.method == ZipMethod.deflated)
let fileBuilder = ZipWriter.FileSpecBuilder(name: "a", data: bytes([1, 2, 3]))
fileBuilder.method = ZipMethod.stored
let builtFile = try fileBuilder.build()
precondition(builtFile.isEqual(fileSnapshot) && builtFile.hash() == fileSnapshot.hash())
fileInput.set(index: 0, value: 9)
fileSnapshot.data.set(index: 0, value: 8)
fileBuilder.data.set(index: 0, value: 7)
precondition(fileSnapshot.data.get(index: 0) == 1 && builtFile.data.get(index: 0) == 1)
var retainedFileBuilder: ZipWriter.FileSpecBuilder?
let copiedFile = try fileSnapshot.doCopy {
    retainedFileBuilder = $0
    $0.name = "b"
    $0.method = ZipMethod.deflated
    $0.data.set(index: 1, value: 4)
}
retainedFileBuilder!.data.set(index: 0, value: 6)
precondition(copiedFile.data.get(index: 0) == 1 && copiedFile.data.get(index: 1) == 4)
precondition(fileSnapshot.data.get(index: 1) == 2)
let equalFile = try fileSnapshot.doCopy { _ in }
let invokedFile = try ZipWriter.FileSpec.companion.invoke(name: "a", data: bytes([1, 2, 3]), method: ZipMethod.stored) { _ in }
precondition(equalFile.isEqual(fileSnapshot) && invokedFile.isEqual(fileSnapshot))
precondition(ZipMethod.stored.code == 0 && ZipMethod.deflated.code == 8)
let fileSpecArchive = try ZipWriter.shared.write(files: [fileSnapshot, copiedFile])
let fileSpecReader = ZipReader(source: ByteArrayRandomAccessSource(data: fileSpecArchive))
let fileSpecEntries = try fileSpecReader.entries()
let firstFileSpecPayload = try fileSpecReader.read(entry: fileSpecEntries[0], limits: limits)
let secondFileSpecPayload = try fileSpecReader.read(entry: fileSpecEntries[1], limits: limits)
precondition(fileSpecEntries.count == 2 && firstFileSpecPayload.get(index: 0) == 1 && secondFileSpecPayload.get(index: 1) == 4)
try fileSpecReader.close()
let hugeFileSpec = try ZipWriter.FileSpec(name: String(repeating: "😀", count: 1000),
    data: KotlinByteArray(size: 1_000_000), method: ZipMethod.stored)
precondition(hugeFileSpec.description().count < 200)

// Filename callbacks receive copies; public snapshots cannot change ZIP integrity bytes.
final class LatinNameDecoder: NSObject, ArchiveNameDecoder {
    var calls = 0
    func decode(bytes: KotlinByteArray) throws -> String {
        calls += 1
        var values: [UInt8] = []
        for index in 0..<bytes.size { values.append(UInt8(bitPattern: bytes.get(index: index))) }
        let name = String(bytes: values, encoding: .isoLatin1)!
        for index in 0..<bytes.size { bytes.set(index: index, value: 0) }
        return name
    }
}
final class FailingNameDecoder: NSObject, ArchiveNameDecoder {
    func decode(bytes: KotlinByteArray) throws -> String {
        throw NSError(domain: "NameDecoder", code: 7)
    }
}
let cp437NameZip = bytes(Array(Data(base64Encoded: "UEsDBAoAAAAAAAAAIQDnx9u+AwAAAAMAAAAIAAkAY2Fmgi50eHRVVAUAAQCmzhIABwhQSwMECgAAAAAAAAAhANCtGb8DAAAAAwAAAAgACQCBYmVyLnR4dFVUBQABAKbOEgEHCFBLAwQKAAAAAAAAACEAiRNfvQMAAAADAAAACQAJAOMt5C3sLnR4dFVUBQABAKbOEgIHCFBLAwQKAAAAAAAAACEAvnmdvAMAAAADAAAAiAAJAGFsbC2AgYKDhIWGh4iJiouMjY6PkJGSk5SVlpeYmZqbnJ2en6ChoqOkpaanqKmqq6ytrq+wsbKztLW2t7i5uru8vb6/wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t/g4eLj5OXm5+jp6uvs7e7v8PHy8/T19vf4+fr7/P3+/y5iaW5VVAUAAQCmzhIDBwhQSwECCgAKAAAAAAAAACEA58fbvgMAAAADAAAACAAJAAAAAAAAAAAAAAAAAAAAY2Fmgi50eHRVVAUAAQCmzhJQSwECCgAKAAAAAAAAACEA0K0ZvwMAAAADAAAACAAJAAAAAAAAAAAAAAAyAAAAgWJlci50eHRVVAUAAQCmzhJQSwECCgAKAAAAAAAAACEAiRNfvQMAAAADAAAACQAJAAAAAAAAAAAAAABkAAAA4y3kLewudHh0VVQFAAEAps4SUEsBAgoACgAAAAAAAAAhAL55nbwDAAAAAwAAAIgACQAAAAAAAAAAAAAAlwAAAGFsbC2AgYKDhIWGh4iJiouMjY6PkJGSk5SVlpeYmZqbnJ2en6ChoqOkpaanqKmqq6ytrq+wsbKztLW2t7i5uru8vb6/wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t/g4eLj5OXm5+jp6uvs7e7v8PHy8/T19vf4+fr7/P3+/y5iaW5VVAUAAQCmzhJQSwUGAAAAAAQABAB9AQAASQEAAAAA")!))
let unicodeNameZip = bytes(Array(Data(base64Encoded: "UEsDBAoAAAAAAAAAIQDRBAGLAwAAAAMAAAAMAB4AZmFsbGJhY2sudHh0VVQFAAEAps4SdXARAAFUVXog6ZuqL/CfmoAudHh0KgcIUEsBAgoACgAAAAAAAAAhANEEAYsDAAAAAwAAAAwAHgAAAAAAAAAAAAAAAAAAAGZhbGxiYWNrLnR4dFVUBQABAKbOEnVwEQABVFV6IOmbqi/wn5qALnR4dFBLBQYAAAAAAQABAFgAAABLAAAAAAA=")!))
let cp437NameReader = try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: cp437NameZip))
let cp437NameEntries = try cp437NameReader.entries()
precondition(cp437NameEntries.count == 4 && cp437NameEntries[0].name == "café.txt" && cp437NameEntries[2].name == "π-Σ-∞.txt")
let rawNameSnapshot = cp437NameEntries[0].nameBytes!
precondition(rawNameSnapshot.get(index: 3) == Int8(bitPattern: 0x82))
rawNameSnapshot.set(index: 3, value: 0)
precondition(cp437NameEntries[0].nameBytes!.get(index: 3) == Int8(bitPattern: 0x82))
let copiedNameEntry = try cp437NameEntries[0].doCopy { _ in }
precondition(copiedNameEntry.isEqual(cp437NameEntries[0]))
let renamedEntry = try cp437NameEntries[0].doCopy { $0.name = "renamed" }
precondition(renamedEntry.name == "renamed" && renamedEntry.size == cp437NameEntries[0].size)
let builtEntry = try ArchiveEntry.Builder(name: "built", size: 3, isDirectory: false).build()
precondition(builtEntry.compressedSize == 3 && builtEntry.crc32 == nil)
precondition(copiedNameEntry.nameBytes!.get(index: 3) == Int8(bitPattern: 0x82))
let copiedNamePayload = try cp437NameReader.read(entry: copiedNameEntry, limits: limits)
precondition(copiedNamePayload.size == 3 && copiedNamePayload.get(index: 1) == 7)
try cp437NameReader.close()
let latinNameDecoder = LatinNameDecoder()
let latinNameReader = try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: cp437NameZip), nameDecoder: latinNameDecoder)
let latinNameEntries = try latinNameReader.entries()
precondition(latinNameEntries[1].name == "\u{81}ber.txt" && latinNameDecoder.calls == 4)
let latinNamePayload = try latinNameReader.read(entry: latinNameEntries[0], limits: limits)
precondition(latinNamePayload.size == 3 && latinNameEntries[0].nameBytes!.get(index: 3) == Int8(bitPattern: 0x82))
try latinNameReader.close()
let unicodeNameReader = ZipReader(source: ByteArrayRandomAccessSource(data: unicodeNameZip), nameDecoder: FailingNameDecoder())
let unicodeNameEntries = try unicodeNameReader.entries()
precondition(unicodeNameEntries[0].name == "雪/🚀.txt")
try unicodeNameReader.close()
let invalidUtf8NameZip = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: "ab", data: bytes([42]), method: ZipMethod.stored)])
invalidUtf8NameZip.set(index: 30, value: Int8(bitPattern: 0xc0))
invalidUtf8NameZip.set(index: 31, value: Int8(bitPattern: 0xaf))
invalidUtf8NameZip.set(index: invalidUtf8NameZip.size - 24, value: Int8(bitPattern: 0xc0))
invalidUtf8NameZip.set(index: invalidUtf8NameZip.size - 23, value: Int8(bitPattern: 0xaf))
let invalidUtf8NameReader = ZipReader(source: ByteArrayRandomAccessSource(data: invalidUtf8NameZip))
expectFailure("strict UTF-8 filename decoder", check: { $0 is ArchiveFormatException }) {
    _ = try invalidUtf8NameReader.entries()
}
try invalidUtf8NameReader.close()
let failedNameReader = ZipReader(source: ByteArrayRandomAccessSource(data: cp437NameZip), nameDecoder: FailingNameDecoder())
expectFailure("Swift filename callback", check: { $0 is ArchiveFormatException && $0.offset != nil && $0.cause != nil }) {
    _ = try failedNameReader.entries()
}
try failedNameReader.close()
let empty = bytes([])
expectFailure("unsafe path", check: { $0 is ArchivePathException && $0.entryName == "../escape" }) {
    _ = try PathGuard.shared.sanitize(entryName: "../escape")
}
expectFailure("gzip header", check: { $0 is ArchiveFormatException }) { _ = try Gzip.shared.decompress(input: empty) }
expectFailure("zlib header", check: { $0 is ArchiveFormatException }) { _ = try Zlib.shared.decompress(input: empty, limits: limits) }
expectFailure("raw deflate", check: { $0 is InflateException }) { _ = try Inflate.shared.inflate(input: bytes([7])) }
expectFailure("LZ4 header", check: { $0 is ArchiveFormatException }) { _ = try Lz4.shared.frameDecompress(input: empty, limits: limits) }
for (label, encoded) in [
    ("large", "04224d186840640000000000000023060000005068656c6c6f00000000"),
    ("small", "04224d1868400200000000000000a0060000005068656c6c6f00000000")
] {
    let octets = stride(from: 0, to: encoded.count, by: 2).map { index -> UInt8 in
        let start = encoded.index(encoded.startIndex, offsetBy: index)
        let end = encoded.index(start, offsetBy: 2)
        return UInt8(encoded[start..<end], radix: 16)!
    }
    expectFailure("LZ4 content size \(label)", check: {
        $0 is ArchiveFormatException && $0.offset?.int64Value == 6 && $0.message?.contains("content size mismatch") == true
    }) { _ = try Lz4.shared.frameDecompress(input: bytes(octets), limits: limits) }
}
expectFailure("LZ4 block terminal sequence", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 22 && $0.message?.contains("final literal") == true
}) {
    _ = try Lz4.shared.frameDecompress(input: bytes([
        4, 0x22, 0x4d, 0x18, 0x60, 0x40, 0x82, 11, 0, 0, 0,
        0x80, 97, 98, 99, 100, 101, 102, 103, 104, 8, 0, 0, 0, 0, 0
    ]), limits: limits)
}
expectFailure("LZ4 content checksum", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 11 && $0.message?.contains("content checksum mismatch") == true
}) {
    _ = try Lz4.shared.frameDecompress(input: bytes([4, 0x22, 0x4d, 0x18, 0x64, 0x40, 0xa7, 0, 0, 0, 0, 4, 0x5d, 0xcc, 2]), limits: limits)
}
expectFailure("Snappy preamble", check: { $0 is ArchiveFormatException }) { _ = try Snappy.shared.decompress(src: bytes([128]), limits: limits) }
// Descriptor semantics and optional Dictionary IDs remain distinct from checksum validation.
let invalidLz4Descriptors: [(String, String)] = [
    ("version-0", "BCJNGCRArQYAAIBmaXJzdC0AAAAA6RkwFQ=="),
    ("version-2", "BCJNGKRA8gYAAIBmaXJzdC0AAAAA6RkwFQ=="),
    ("version-3", "BCJNGORAXAYAAIBmaXJzdC0AAAAA6RkwFQ=="),
    ("flg-reserved", "BCJNGGZAdwYAAIBmaXJzdC0AAAAA6RkwFQ=="),
]
for (label, encoded) in invalidLz4Descriptors {
    expectFailure("LZ4 descriptor \(label)", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 4 }) {
        _ = try Lz4.shared.frameDecompress(input: bytes(Array(Data(base64Encoded: encoded)!)), limits: limits)
    }
}
let declaredLz4Dictionary = bytes(Array(Data(base64Encoded: "BCJNGG1AVAAAAAAAAACYutz+4hQAAAAPNgAjQSBhbmRSAAxxAFBhZ2FpbgAAAADSu40A")!))
let undeclaredLz4Dictionary = bytes(Array(Data(base64Encoded: "BCJNGGxAVAAAAAAAAACfFAAAAA82ACNBIGFuZFIADHEAUGFnYWluAAAAANK7jQA=")!))
let lz4Protocol: Codec = ArchiveKodec.shared.codec(id: CodecId.companion.LZ4)!
expectFailure("LZ4 declared dictionary through Codec", check: {
    $0 is ArchiveUnsupportedException && $0.offset?.int64Value == 24 && $0.message?.contains("dictionary ID 4275878552") == true
}) { _ = try lz4Protocol.decompress(data: declaredLz4Dictionary, limits: limits) }
expectFailure("LZ4 undeclared dictionary", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 20 && $0.message?.contains("may be required") == true
}) { _ = try Lz4.shared.frameDecompress(input: undeclaredLz4Dictionary, limits: limits) }
declaredLz4Dictionary.set(index: 24, value: 0)
declaredLz4Dictionary.set(index: 25, value: 0)
expectFailure("LZ4 zero offset with Dictionary ID", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 24 && $0.message?.contains("invalid match offset 0") == true
}) { _ = try Lz4.shared.frameDecompress(input: declaredLz4Dictionary, limits: limits) }
let corruptLz4DictionaryHeader = bytes(Array(Data(base64Encoded: "BCJNGG1AVAAAAAAAAACYutz+4hQAAAAPNgAjQSBhbmRSAAxxAFBhZ2FpbgAAAADSu40A")!))
corruptLz4DictionaryHeader.set(index: 18, value: 0)
expectFailure("LZ4 dictionary header checksum", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 18 && $0.message?.contains("header checksum") == true
}) { _ = try Lz4.shared.frameDecompress(input: corruptLz4DictionaryHeader, limits: limits) }
let selfContainedLz4Dictionary = bytes(Array(Data(base64Encoded: "BCJNGGFAmLrc/tEGAACAZmlyc3QtAAAAAA==")!))
let selfContainedLz4Output = try Lz4.shared.frameDecompress(input: selfContainedLz4Dictionary, limits: limits)
precondition(selfContainedLz4Output.size == 6 && selfContainedLz4Output.get(index: 0) == 102)

// Reference CLI frames and sequence-boundary failures through the exported Codec.
let lz4First: [UInt8] = [4, 0x22, 0x4d, 0x18, 0x64, 0x40, 0xa7, 6, 0, 0, 0x80, 102, 105, 114, 115, 116, 45, 0, 0, 0, 0, 0xe9, 0x19, 0x30, 0x15]
let lz4Second: [UInt8] = [4, 0x22, 0x4d, 0x18, 0x64, 0x40, 0xa7, 6, 0, 0, 0x80, 115, 101, 99, 111, 110, 100, 0, 0, 0, 0, 0xdd, 0x5d, 0xd6, 0xef]
let lz4Skip: [UInt8] = [0x5f, 0x2a, 0x4d, 0x18, 4, 0, 0, 0, 97, 98, 99, 100]
let lz4Sequence = try lz4Protocol.decompress(data: bytes(lz4Skip + lz4First + lz4Skip + lz4Second + lz4Skip), limits: limits)
precondition(lz4Sequence.size == 12)
for (index, value) in Array("first-second".utf8).enumerated() {
    precondition(lz4Sequence.get(index: Int32(index)) == Int8(bitPattern: value))
}
let lz4Skipped = try lz4Protocol.decompress(data: bytes(lz4Skip), limits: limits)
precondition(lz4Skipped.size == 0)
for (label, suffix, offset) in [
    ("trailing byte", [UInt8(0)], 25),
    ("truncated next header", Array(lz4Second.prefix(4)), 29),
    ("truncated skippable payload", [0x50, 0x2a, 0x4d, 0x18, 4, 0, 0, 0, 1], 33)
] {
    expectFailure("LZ4 sequence \(label)", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == Int64(offset) }) {
        _ = try lz4Protocol.decompress(data: bytes(lz4First + suffix), limits: limits)
    }
}
expectFailure("LZ4 unsigned skippable length", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 8 }) {
    _ = try lz4Protocol.decompress(data: bytes([0x50, 0x2a, 0x4d, 0x18, 255, 255, 255, 255]), limits: limits)
}
expectFailure("LZ4 legacy second frame", check: {
    $0 is ArchiveUnsupportedException && $0.offset?.int64Value == 25 && $0.message?.contains("legacy") == true
}) { _ = try lz4Protocol.decompress(data: bytes(lz4First + [2, 0x21, 0x4c, 0x18]), limits: limits) }
var lz4CorruptSecond = lz4Second
lz4CorruptSecond[24] ^= 1
expectFailure("LZ4 second content checksum", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 46 }) {
    _ = try lz4Protocol.decompress(data: bytes(lz4First + lz4CorruptSecond), limits: limits)
}
let lz4CombinedLimit = try makeLimits(maxUncompressedBytes: 11, maxCompressionRatio: 10000, maxEntries: 100000, maxNestingDepth: 8)
expectFailure("LZ4 combined output limit", check: { $0 is DecompressionBombException }) {
    _ = try lz4Protocol.decompress(data: bytes(lz4First + lz4Second), limits: lz4CombinedLimit)
}
let lz4RatioLimit = try makeLimits(maxUncompressedBytes: 10000, maxCompressionRatio: 2, maxEntries: 100000, maxNestingDepth: 8)
let lz4HighRatio = Array(Data(base64Encoded: "BCJNGHxA6AMAAAAAAAA9IQAAANdoZWxsbyB3b3JsZCwgDQAXCgwADxkA////r1BvcmxkClB+pm0AAAAAjnmROQ==")!)
let lz4Padding: [UInt8] = [0x50, 0x2a, 0x4d, 0x18, 0, 0x10, 0, 0] + [UInt8](repeating: 0, count: 4096)
expectFailure("LZ4 skippable padding cannot dilute ratio", check: { $0 is DecompressionBombException }) {
    _ = try lz4Protocol.decompress(data: bytes(lz4HighRatio + lz4Padding), limits: lz4RatioLimit)
}

// Linked blocks read frame-local history through the same exported Codec.
let lz4LinkedOctets = Array(Data(base64Encoded: "BCJNGFxAcBEBAAAAAADjCwEAAB9BAQD/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////51BBQUFBQVlAXzIbAAAADwEA//////////////////////9pUEFBQUFBBy+QJwAAAABIqrJX")!)
let lz4Linked = try lz4Protocol.decompress(data: bytes(lz4LinkedOctets), limits: limits)
precondition(lz4Linked.size == 70000)
for index in 0..<lz4Linked.size { precondition(lz4Linked.get(index: index) == 65) }
let lz4TaggedLinked = try lz4Protocol.decompress(data: bytes(Array(Data(base64Encoded: "BCJNGF1AIwAAAAAAAACYutz+zAMAAIBhYmP/U9EyCgAAAA8DAAhQMTIzNDW7amqLAAAAAHnsvag=")!)), limits: limits)
let lz4TaggedExpected = Array((String(repeating: "abc", count: 10) + "12345").utf8)
precondition(lz4TaggedLinked.size == Int32(lz4TaggedExpected.count))
for (index, value) in lz4TaggedExpected.enumerated() {
    precondition(lz4TaggedLinked.get(index: Int32(index)) == Int8(bitPattern: value))
}
for (label, encoded, offset, message) in [
    ("independent block isolation", "BCJNGGBAggMAAIBhYmMKAAAADwMAAFAxMjM0NQAAAAA=", 19, "available history 0"),
    ("linked history bound", "BCJNGEBAwAMAAIBhYmMKAAAADwQAAFAxMjM0NQAAAAA=", 19, "available history 3"),
    ("linked zero offset", "BCJNGEBAwAMAAIBhYmMKAAAADwAAAFAxMjM0NQAAAAA=", 19, "invalid match offset 0"),
    ("linked short final literals", "BCJNGEBAwAMAAIBhYmMJAAAADwMAAEAAAAAAAAAAAA==", 22, "final literal")
] {
    expectFailure("LZ4 \(label)", check: {
        $0 is ArchiveFormatException && $0.offset?.int64Value == Int64(offset) && $0.message?.contains(message) == true
    }) { _ = try lz4Protocol.decompress(data: bytes(Array(Data(base64Encoded: encoded)!)), limits: limits) }
}
expectFailure("LZ4 linked history resets at frame boundary", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 345 && $0.message?.contains("available history 0") == true
}) {
    _ = try lz4Protocol.decompress(data: bytes(lz4LinkedOctets + Array(Data(base64Encoded: "BCJNGEBAwAoAAAAPAQAAUDEyMzQ1AAAAAA==")!)), limits: limits)
}
expectFailure("LZ4 linked missing dictionary", check: {
    $0 is ArchiveUnsupportedException && $0.offset?.int64Value == 16 && $0.message?.contains("dictionary ID 4275878552") == true
}) { _ = try lz4Protocol.decompress(data: bytes(Array(Data(base64Encoded: "BCJNGEFAmLrc/gYKAAAADwEAAFAxMjM0NQAAAAA=")!)), limits: limits) }
let lz4HistoryOutputLimit = try makeLimits(maxUncompressedBytes: 107, maxCompressionRatio: 10000, maxEntries: 100000, maxNestingDepth: 8)
expectFailure("LZ4 linked output limit", check: { $0 is DecompressionBombException }) {
    _ = try lz4Protocol.decompress(data: bytes(Array(Data(base64Encoded: "BCJNGEBAwAMAAIBhYmMKAAAADwMAUVAxMjM0NQAAAAA=")!)), limits: lz4HistoryOutputLimit)
}
expectFailure("LZ4 linked ratio limit", check: { $0 is DecompressionBombException }) {
    _ = try lz4Protocol.decompress(data: bytes(Array(Data(base64Encoded: "BCJNGEBAwAMAAIBhYmMZAAAADwMA////////////////////nFAxMjM0NQAAAAA=")!)), limits: lz4RatioLimit)
}

expectFailure("zlib dictionary", check: { $0 is ArchiveUnsupportedException }) {
    _ = try Zlib.shared.decompress(input: bytes([0x78, 0xbb, 0x35, 0x29, 0x06, 0x6b, 0xcb, 0x00, 0xf1, 0x01, 0x06, 0x2c, 0x02, 0x15]))
}
expectFailure("Codec protocol", check: { $0 is ArchiveFormatException }) { _ = try ArchiveKodec.shared.gzip.decompress(data: empty, limits: limits) }
expectFailure("unsupported container", check: { $0 is ArchiveUnsupportedException }) {
    _ = try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: bytes([66, 90, 104, 57, 0x17, 0x72, 0x45, 0x38, 0x50, 0x90])))
}
for octets in [Array("x = 1\n".utf8), Array("BZh is a name".utf8), [0x50, 0x4b, 0x05, 0xff]] {
    let source = ByteArrayRandomAccessSource(data: bytes(octets))
    precondition(ArchiveKodec.shared.sniff(header: bytes(octets)) == ArchiveFormat.companion.UNKNOWN)
    expectFailure("unrecognized format", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 0 }) {
        _ = try ArchiveKodec.shared.open(source: source)
    }
    try source.close()
}
let dictionaryPrefix = bytes([0x78, 0x20, 0x2e, 0x59, 0x05, 0xce, 0x03, 0, 0, 0, 0, 1])
precondition(ArchiveKodec.shared.sniff(header: dictionaryPrefix) == ArchiveFormat.companion.ZLIB)
precondition(ArchiveKodec.shared.sniff(header: bytes([0x78, 0x9c])) == ArchiveFormat.companion.ZLIB)
// Frozen first v7 record from an independently verified TAR archive.
let v7Header = Array(Data(base64Encoded: "Zi50eHQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAwMDA2NDQAMDAwMDAwMAAwMDAwMDAwADAwMDAwMDAwMDAxADAwMDAwMDAwMDAwADAwNTUwMwAgMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!)
let v7Reader = try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: bytes(v7Header + [97] + Array(repeating: 0, count: 1535))))
precondition(v7Reader.format == ArchiveFormat.companion.TAR)
let v7Entry = try v7Reader.entries()[0]
precondition(v7Entry.name == "f.txt")
let v7Payload = try v7Reader.read(entry: v7Entry, limits: limits)
precondition(v7Payload.size == 1 && v7Payload.get(index: 0) == 97)
try v7Reader.close()
let emptyTarReader = try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: KotlinByteArray(size: 512)))
let emptyTarEntries = try emptyTarReader.entries()
precondition(emptyTarReader.format == ArchiveFormat.companion.TAR && emptyTarEntries.isEmpty)
try emptyTarReader.close()

// Frozen reference headers verify pathname layout through the Swift consumer.
let layoutPayload: [UInt8] = Array("Archive member payload\0".utf8) + [0xff]
let layoutHeaders: [(String, String)] = [
    ("Zm9vLnR4dAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAwMDA2NDQAMDAwMDAwMAAwMDAwMDAwADAwMDAwMDAwMDMwADE0NTI0NzcwNDAwADAxMjM2NQAgMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB1c3RhciAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMTQwMDAwMDAwMDAAMTQwMDAwMDAwMDEAMDAwMDAwMDAxMjMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "foo.txt"),
    ("c2VnbWVudC9zZWdtZW50L3NlZ21lbnQvc2VnbWVudC9zZWdtZW50L3NlZ21lbnQvc2VnbWVudC9zZWdtZW50L3NlZ21lbnQvc2VnbWVudC9sb25nLWZpbGUtbmFtZS50eHQAADAwMDA2NDQAMDAwMDAwMAAwMDAwMDAwADAwMDAwMDAwMDMwADE0NTI0NzcwNDAwADA0MjMxNAAgMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB1c3RhcgAwMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAc2VnbWVudC9zZWdtZW50L3NlZ21lbnQvc2VnbWVudC9zZWdtZW50L3NlZ21lbnQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", String(repeating: "segment/", count: 16) + "long-file-name.txt")
]
for (encoded, expectedName) in layoutHeaders {
    let header = Array(Data(base64Encoded: encoded)!)
    let source = ByteArrayRandomAccessSource(data: bytes(header + layoutPayload + Array(repeating: 0, count: 1512)))
    let reader = try ArchiveKodec.shared.open(source: source)
    let entries = try reader.entries()
    precondition(reader.format == ArchiveFormat.companion.TAR && entries.count == 1 && entries[0].name == expectedName)
    let payload = try reader.read(entry: entries[0], limits: limits)
    precondition(payload.size == Int32(layoutPayload.count))
    for (index, value) in layoutPayload.enumerated() { precondition(payload.get(index: Int32(index)) == Int8(bitPattern: value)) }
    try reader.close()
}

// Directory spellings are preserved independently of their directory flag.
let rawDirectoryHeaders = Array(Data(base64Encoded: "ZS8vAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAwMDA3NTUAMDAwMDAwMAAwMDAwMDAwADAwMDAwMDAwMDAwADE0NTI0NzcwNDAwADAwNjMxMgAgNQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB1c3RhcgAwMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAuLwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMDAwMDc1NQAwMDAwMDAwADAwMDAwMDAAMDAwMDAwMDAwMDAAMTQ1MjQ3NzA0MDAAMDA2MTQ0ACA1AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHVzdGFyADAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==")!)
let rawDirectorySource = ShortReadSource(data: bytes(rawDirectoryHeaders + Array(repeating: 0, count: 1024)), chunk: 3)
let rawDirectoryReader = try ArchiveKodec.shared.open(source: rawDirectorySource)
let rawDirectoryEntries = try rawDirectoryReader.entries()
precondition(rawDirectoryEntries.map { $0.name } == ["e//", "./"])
for entry in rawDirectoryEntries {
    precondition(entry.isDirectory && entry.size == 0)
    let payload = try rawDirectoryReader.read(entry: entry, limits: limits)
    precondition(payload.size == 0)
}
try rawDirectoryReader.close()
precondition(rawDirectorySource.closes == 1)

// Root directory entries are skipped explicitly; strict file destinations still fail.
func pathEntry(_ name: String, directory: Bool) -> ArchiveEntry {
    try! ArchiveEntry(name: name, size: 0, isDirectory: directory)
}
for name in [".", "./", "./.", ".\\.\\"] {
    precondition(PathGuard.shared.isRootDirectory(entry: pathEntry(name, directory: true)))
    precondition(!PathGuard.shared.isRootDirectory(entry: pathEntry(name, directory: false)))
    precondition(!PathGuard.shared.isSafe(entryName: name))
    expectFailure("root file path \(name)", check: { $0 is ArchivePathException && $0.entryName == name }) {
        _ = try PathGuard.shared.sanitize(entryName: name)
    }
}
for name in ["", "/", "../", "./C:/", ".\\..\\.", ".\u{0}/"] {
    precondition(!PathGuard.shared.isRootDirectory(entry: pathEntry(name, directory: true)))
    expectFailure("invalid directory path \(name.debugDescription)", check: { $0 is ArchivePathException && $0.entryName == name }) {
        _ = try PathGuard.shared.sanitize(entryName: name)
    }
}
precondition(!PathGuard.shared.isRootDirectory(entry: pathEntry("./sub/", directory: true)))

// Frozen BSD tar/cpio archives exercise the documented loop through Objective-C short reads.
let rootArchives: [String] = [
    // dot.tar
    "Li8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAwMDc1NSAAMDAwNzY1IAAwMDAwMDAgADAwMDAwMDAwMDAwIDE1MDcxNjc0MDAwIDAx" +
    "MTc2NAAgNQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB1c3RhcgAwMG1hY2Jvb2sAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAd2hl" +
    "ZWwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwMDAwMDAgADAwMDAwMCAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAuL3N1Yi8AAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAMDAwNzU1IAAwMDA3NjUgADAwMDAwMCAAMDAwMDAwMDAwMDAgMTUwNzE2NzQwMDAgMDEyNTU1ACA1AAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAHVzdGFyADAwbWFjYm9vawAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB3aGVlbAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAADAwMDAwMCAAMDAwMDAwIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC4vYS50eHQAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAw" +
    "MDA2NDQgADAwMDc2NSAAMDAwMDAwIAAwMDAwMDAwMDAwNiAxNTA3MTY3NDAwMCAwMTI3NDEAIDAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAdXN0YXIAMDBtYWNib29rAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHdoZWVsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAMDAwMDAwIAAwMDAwMDAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAaGVsbG8KAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAuL3N1Yi9iLnR4dAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMDAwNjQ0IAAwMDA3NjUgADAwMDAwMCAAMDAw" +
    "MDAwMDAwMDIgMTUwNzE2NzQwMDAgMDEzNTI3ACAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHVzdGFyADAwbWFjYm9vawAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAB3aGVlbAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAwMDAwMCAAMDAwMDAwIAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAHgKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==",
    // dot.newc
    "MDcwNzAxMDkwYTY0NTIwMDAwNDFlZDAwMDAwMWY1MDAwMDAwMDAwMDAwMDAwNDY4ZTc3ODAwMDAwMDAwMDAwMDAwMDAwMTAwMDAw" +
    "MDBkMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAyMDAwMDAwMDAuADA3MDcwMTA5MGE2NDUzMDAwMDQxZWQwMDAwMDFmNTAwMDAwMDAw" +
    "MDAwMDAwMDM2OGU3NzgwMDAwMDAwMDAwMDAwMDAwMDEwMDAwMDAwZDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwNjAwMDAwMDAwLi9z" +
    "dWIAMDcwNzAxMDkwYTY0NTUwMDAwODFhNDAwMDAwMWY1MDAwMDAwMDAwMDAwMDAwMTY4ZTc3ODAwMDAwMDAwMDIwMDAwMDAwMTAw" +
    "MDAwMDBkMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDBjMDAwMDAwMDAuL3N1Yi9iLnR4dAAAAHgKAAAwNzA3MDEwOTBhNjQ1NDAwMDA4" +
    "MWE0MDAwMDAxZjUwMDAwMDAwMDAwMDAwMDAxNjhlNzc4MDAwMDAwMDAwNjAwMDAwMDAxMDAwMDAwMGQwMDAwMDAwMDAwMDAwMDAw" +
    "MDAwMDAwMDgwMDAwMDAwMC4vYS50eHQAAABoZWxsbwoAADA3MDcwMTAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw" +
    "MDAwMDEwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwYjAwMDAwMDAwVFJBSUxF" +
    "UiEhIQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==",
    // dot.odc
    "MDcwNzA3Nzc3Nzc3MDAwMDAxMDQwNzU1MDAwNzY1MDAwMDAwMDAwMDA0MDAwMDAwMTUwNzE2NzQwMDAwMDAwMDIwMDAwMDAwMDAw" +
    "MC4AMDcwNzA3Nzc3Nzc3MDAwMDAyMDQwNzU1MDAwNzY1MDAwMDAwMDAwMDAzMDAwMDAwMTUwNzE2NzQwMDAwMDAwMDYwMDAwMDAw" +
    "MDAwMC4vc3ViADA3MDcwNzc3Nzc3NzAwMDAwMzEwMDY0NDAwMDc2NTAwMDAwMDAwMDAwMTAwMDAwMDE1MDcxNjc0MDAwMDAwMDE0" +
    "MDAwMDAwMDAwMDIuL3N1Yi9iLnR4dAB4CjA3MDcwNzc3Nzc3NzAwMDAwNDEwMDY0NDAwMDc2NTAwMDAwMDAwMDAwMTAwMDAwMDE1" +
    "MDcxNjc0MDAwMDAwMDEwMDAwMDAwMDAwMDYuL2EudHh0AGhlbGxvCjA3MDcwNzAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw" +
    "MDAwMDAwMTAwMDAwMDAwMDAwMDAwMDAwMDAwMDEzMDAwMDAwMDAwMDBUUkFJTEVSISEhAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
]
for encoded in rootArchives {
    let source = ShortReadSource(data: bytes(Array(Data(base64Encoded: encoded)!)), chunk: 3)
    let reader = try ArchiveKodec.shared.open(source: source)
    var names = Set<String>()
    var skipped = 0
    for entry in try reader.entries() {
        if PathGuard.shared.isRootDirectory(entry: entry) { skipped += 1; continue }
        let name = try PathGuard.shared.sanitize(entryName: entry.name)
        names.insert(name)
        let payload = try reader.read(entry: entry, limits: limits)
        let expected: [UInt8]
        switch name {
        case "sub": expected = []
        case "a.txt": expected = Array("hello\n".utf8)
        case "sub/b.txt": expected = Array("x\n".utf8)
        default: fatalError("Unexpected sanitized entry: \(name)")
        }
        precondition(payload.size == Int32(expected.count))
        for (index, value) in expected.enumerated() {
            precondition(payload.get(index: Int32(index)) == Int8(bitPattern: value))
        }
    }
    precondition(skipped == 1 && names == Set(["sub", "a.txt", "sub/b.txt"]))
    try reader.close()
    precondition(source.closes == 1)
}

// Frozen LLVM GNU64 archive: the internal symbol index is not a user entry.
let gnu64Archive = Array(Data(base64Encoded: "ITxhcmNoPgovU1lNNjQvICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICAzMCAgICAgICAgYAoAAAAAAAAAAQAAAAAAAAC6YXJjaGl2ZV9wcm9iZQAvLyAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAyOCAgICAgICAgYApmaXJzdC1sb25nLW1lbWJlci1uYW1lLm9iai8KLzAgICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDY0NCAgICAgNjEwICAgICAgIGAKZIYFAJnXxWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAAAAAAAAAAAAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVidWckUwAAAAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQALgBAAAAwwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lvbiAyMS4wLjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAKIrlMkBAAAAAAAuZGF0YQAAAAAAAAACAAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAAAAAEAAAAAwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD//wAAAwAAAAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFwcm9iZS5jAAAAAAAAAAAAAAAgAAAALmxsdm1fYWRkcnNpZwBhcmNoaXZlX3Byb2JlAA==")!)
let gnu64Object = Array(Data(base64Encoded: "ZIYFAJnXxWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAAAAAAAAAAAAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVidWckUwAAAAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQALgBAAAAwwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lvbiAyMS4wLjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAKIrlMkBAAAAAAAuZGF0YQAAAAAAAAACAAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAAAAAEAAAAAwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD//wAAAwAAAAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFwcm9iZS5jAAAAAAAAAAAAAAAgAAAALmxsdm1fYWRkcnNpZwBhcmNoaXZlX3Byb2JlAA==")!)
let gnu64Source = ShortReadSource(data: bytes(gnu64Archive), chunk: 3)
let gnu64Reader = try ArchiveKodec.shared.open(source: gnu64Source)
let gnu64Entries = try gnu64Reader.entries()
precondition(gnu64Reader.format == ArchiveFormat.companion.AR && gnu64Entries.count == 1)
let gnu64Entry = gnu64Entries[0]
precondition(gnu64Entry.name == "first-long-member-name.obj" && !gnu64Entry.isDirectory)
precondition(gnu64Entry.size == Int64(gnu64Object.count))
let gnu64Payload = try gnu64Reader.read(entry: gnu64Entry, limits: limits)
precondition(gnu64Payload.size == Int32(gnu64Object.count))
for (index, value) in gnu64Object.enumerated() {
    precondition(gnu64Payload.get(index: Int32(index)) == Int8(bitPattern: value))
}
try gnu64Reader.close()
precondition(gnu64Source.closes == 1)
let truncatedGnu64Source = ShortReadSource(data: bytes(Array(gnu64Archive.prefix(76))), chunk: 3)
let truncatedGnu64Reader = try ArchiveKodec.shared.open(source: truncatedGnu64Source)
expectFailure("truncated GNU64 index", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 68 }) {
    _ = try truncatedGnu64Reader.entries()
}
try truncatedGnu64Reader.close()
precondition(truncatedGnu64Source.closes == 1)

// Frozen LLVM COFF library: NUL-terminated names remain separate from their neighbors.
let coffArchive = Array(Data(base64Encoded: "ITxhcmNoPgovICAgICAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICAzOCAgICAgICAgYAoAAAACAAABSAAAA+RmaXJzdF9wcm9iZQBzZWNvbmRfcHJvYmUAAC8gICAgICAgICAgICAgICAwICAgICAgICAgICAwICAgICAwICAgICAwICAgICAgIDQ2ICAgICAgICBgCgIAAABIAQAA5AMAAAIAAAABAAIAZmlyc3RfcHJvYmUAc2Vjb25kX3Byb2JlAAAvLyAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICA1NiAgICAgICAgYApmaXJzdC1sb25nLW1lbWJlci1uYW1lLm9iagBzZWNvbmQtbG9uZy1tZW1iZXItbmFtZS5vYmoACi8wICAgICAgICAgICAgICAwICAgICAgICAgICAwICAgICAwICAgICA2NDQgICAgIDYwOCAgICAgICBgCmSGBQDv2sVqRgEAAA4AAAAAAAAALnRleHQAAAAAAAAAAAAAAAYAAADcAAAAAAAAAAAAAAAAAAAAIABQYC5kYXRhAAAAAAAAAAAAAAAAAAAA4gAAAAAAAAAAAAAAAAAAAEAAMMAuYnNzAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAADDALmRlYnVnJFMAAAAAAAAAAGQAAADiAAAAAAAAAAAAAAAAAAAAQAAwQi80AAAAAAAAAAAAAAAAAAAAAAAARgEAAAAAAAAAAAAAAAAAAAAIEAC4AQAAAMMEAAAA8QAAAFgAAAAKAAERAAAAAAAAAABKADwRAAAAANAAFQAAAAAAAAD//wAAAAAAAEFwcGxlIGNsYW5nIHZlcnNpb24gMjEuMC4wIChjbGFuZy0yMTAwLjEuMS4xMDEpAAAALnRleHQAAAAAAAAAAQAAAAMBBgAAAAAAAACiK5TJAQAAAAAALmRhdGEAAAAAAAAAAgAAAAMBAAAAAAAAAAAAAAAAAgAAAAAALmJzcwAAAAAAAAAAAwAAAAMBAAAAAAAAAAAAAAAAAwAAAAAALmRlYnVnJFMAAAAABAAAAAMBZAAAAAAAAABtee9lBAAAAAAAAAAAAAQAAAAAAAAABQAAAAMBAAAAAAAAAAAAAAAABQAAAAAAQGZlYXQuMDAAAAAA//8AAAMAAAAAABIAAAAAAAAAAQAgAAIALmZpbGUAAAAAAAAA/v8AAGcBZmlyc3QuYwAAAAAAAAAAAAAAHgAAAC5sbHZtX2FkZHJzaWcAZmlyc3RfcHJvYmUALzI3ICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDY0NCAgICAgNjA5ICAgICAgIGAKZIYFAO/axWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAAAAAAAAAAAAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVidWckUwAAAAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQALgCAAAAwwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lvbiAyMS4wLjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAHJRNI4BAAAAAAAuZGF0YQAAAAAAAAACAAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAAAAAEAAAAAwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD//wAAAwAAAAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFzZWNvbmQuYwAAAAAAAAAAAAAfAAAALmxsdm1fYWRkcnNpZwBzZWNvbmRfcHJvYmUACg==")!)
let coffObjects: [[UInt8]] = [
    Array(Data(base64Encoded: "ZIYFAO/axWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAAAAAAAAAAAAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVidWckUwAAAAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQALgBAAAAwwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lvbiAyMS4wLjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAKIrlMkBAAAAAAAuZGF0YQAAAAAAAAACAAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAAAAAEAAAAAwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD//wAAAwAAAAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFmaXJzdC5jAAAAAAAAAAAAAAAeAAAALmxsdm1fYWRkcnNpZwBmaXJzdF9wcm9iZQA=")!),
    Array(Data(base64Encoded: "ZIYFAO/axWpGAQAADgAAAAAAAAAudGV4dAAAAAAAAAAAAAAABgAAANwAAAAAAAAAAAAAAAAAAAAgAFBgLmRhdGEAAAAAAAAAAAAAAAAAAADiAAAAAAAAAAAAAAAAAAAAQAAwwC5ic3MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAMMAuZGVidWckUwAAAAAAAAAAZAAAAOIAAAAAAAAAAAAAAAAAAABAADBCLzQAAAAAAAAAAAAAAAAAAAAAAABGAQAAAAAAAAAAAAAAAAAAAAgQALgCAAAAwwQAAADxAAAAWAAAAAoAAREAAAAAAAAAAEoAPBEAAAAA0AAVAAAAAAAAAP//AAAAAAAAQXBwbGUgY2xhbmcgdmVyc2lvbiAyMS4wLjAgKGNsYW5nLTIxMDAuMS4xLjEwMSkAAAAudGV4dAAAAAAAAAABAAAAAwEGAAAAAAAAAHJRNI4BAAAAAAAuZGF0YQAAAAAAAAACAAAAAwEAAAAAAAAAAAAAAAACAAAAAAAuYnNzAAAAAAAAAAADAAAAAwEAAAAAAAAAAAAAAAADAAAAAAAuZGVidWckUwAAAAAEAAAAAwFkAAAAAAAAAG1572UEAAAAAAAAAAAABAAAAAAAAAAFAAAAAwEAAAAAAAAAAAAAAAAFAAAAAABAZmVhdC4wMAAAAAD//wAAAwAAAAAAEgAAAAAAAAABACAAAgAuZmlsZQAAAAAAAAD+/wAAZwFzZWNvbmQuYwAAAAAAAAAAAAAfAAAALmxsdm1fYWRkcnNpZwBzZWNvbmRfcHJvYmUA")!),
]
let coffSource = ShortReadSource(data: bytes(coffArchive), chunk: 3)
let coffReader = try ArchiveKodec.shared.open(source: coffSource)
let coffEntries = try coffReader.entries()
precondition(coffReader.format == ArchiveFormat.companion.AR)
precondition(coffEntries.map { $0.name } == ["first-long-member-name.obj", "second-long-member-name.obj"])
for (index, entry) in coffEntries.enumerated() {
    let expected = coffObjects[index]
    precondition(!entry.isDirectory && entry.size == Int64(expected.count))
    let payload = try coffReader.read(entry: entry, limits: limits)
    precondition(payload.size == Int32(expected.count))
    for (offset, value) in expected.enumerated() {
        precondition(payload.get(index: Int32(offset)) == Int8(bitPattern: value))
    }
}
try coffReader.close()
precondition(coffSource.closes == 1)
var unterminatedCoff = coffArchive
for offset in 272..<328 where unterminatedCoff[offset] == 0 || unterminatedCoff[offset] == 10 {
    unterminatedCoff[offset] = 88
}
let unterminatedCoffSource = ShortReadSource(data: bytes(unterminatedCoff), chunk: 3)
let unterminatedCoffReader = try ArchiveKodec.shared.open(source: unterminatedCoffSource)
expectFailure("unterminated COFF long name", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 328 && $0.entryName == "/0" && $0.message?.contains("unterminated") == true
}) { _ = try unterminatedCoffReader.entries() }
try unterminatedCoffReader.close()
precondition(unterminatedCoffSource.closes == 1)
var outsideCoff = coffArchive
outsideCoff.replaceSubrange(328..<344, with: Array("/56             ".utf8))
let outsideCoffSource = ShortReadSource(data: bytes(outsideCoff), chunk: 3)
let outsideCoffReader = try ArchiveKodec.shared.open(source: outsideCoffSource)
expectFailure("outside COFF long-name offset", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 328 && $0.entryName == "/56"
}) { _ = try outsideCoffReader.entries() }
try outsideCoffReader.close()
precondition(outsideCoffSource.closes == 1)

// A COFF NUL delimiter preserves an embedded LF for strict path validation.
let coffControlArchive = Array(Data(base64Encoded: "ITxhcmNoPgovICAgICAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMTAwNjQ0ICA0ICAgICAgICAgYAoAAAAALyAgICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDEwMDY0NCAgOCAgICAgICAgIGAKAAAAAAAAAAAvLyAgICAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMTAwNjQ0ICA1MSAgICAgICAgYApmaXJzdApsb25nLW1lbWJlci1uYW1lLm8Ac2Vjb25kLWxvbmctbWVtYmVyLW5hbWUubwAKLzAgICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDEwMDY0NCAgMSAgICAgICAgIGAKeAo=")!)
let coffControlSource = ShortReadSource(data: bytes(coffControlArchive), chunk: 3)
let coffControlReader = try ArchiveKodec.shared.open(source: coffControlSource)
let coffControlEntries = try coffControlReader.entries()
precondition(coffControlEntries.count == 1 && coffControlEntries[0].name == "first\nlong-member-name.o")
let coffControlPayload = try coffControlReader.read(entry: coffControlEntries[0], limits: limits)
precondition(coffControlPayload.size == 1 && coffControlPayload.get(index: 0) == 120)
precondition(!PathGuard.shared.isSafe(entryName: coffControlEntries[0].name))
expectFailure("COFF control path", check: { $0 is ArchivePathException && $0.entryName == "first\nlong-member-name.o" }) {
    _ = try PathGuard.shared.sanitize(entryName: coffControlEntries[0].name)
}
try coffControlReader.close()
precondition(coffControlSource.closes == 1)

// Real Apple/LLVM BSD archives use byte-counted inline names and leading indices.
let bsdObjects: [[UInt8]] = [
    Array(Data(base64Encoded: "z/rt/gwAAAEAAAAAAQAAAAQAAABoAQAAACAAAAAAAAAZAAAA6AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACgAAAAAAAAAiAEAAAAAAAAoAAAAAAAAAAcAAAAHAAAAAgAAAAAAAABfX3RleHQAAAAAAAAAAAAAX19URVhUAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAACIAQAAAgAAAAAAAAAAAAAAAAQAgAAAAAAAAAAAAAAAAF9fY29tcGFjdF91bndpbmRfX0xEAAAAAAAAAAAAAAAACAAAAAAAAAAgAAAAAAAAAJABAAADAAAAsAEAAAEAAAAAAAACAAAAAAAAAAAAAAAAMgAAABgAAAABAAAAAAAaAAAFGgAAAAAAAgAAABgAAAC4AQAAAwAAAOgBAAAgAAAACwAAAFAAAAAAAAAAAgAAAAIAAAABAAAAAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAIBSwANf1gAAAAAAAAAACAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAYUAAAADgEAAAAAAAAAAAAADgAAAA4CAAAIAAAAAAAAAAEAAAAPAQAAAAAAAAAAAAAAX2ZpcnN0X3Byb2JlAGx0bXAxAGx0bXAwAAAAAAAAAA==")!),
    Array(Data(base64Encoded: "z/rt/gwAAAEAAAAAAQAAAAQAAABoAQAAACAAAAAAAAAZAAAA6AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACgAAAAAAAAAiAEAAAAAAAAoAAAAAAAAAAcAAAAHAAAAAgAAAAAAAABfX3RleHQAAAAAAAAAAAAAX19URVhUAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAACIAQAAAgAAAAAAAAAAAAAAAAQAgAAAAAAAAAAAAAAAAF9fY29tcGFjdF91bndpbmRfX0xEAAAAAAAAAAAAAAAACAAAAAAAAAAgAAAAAAAAAJABAAADAAAAsAEAAAEAAAAAAAACAAAAAAAAAAAAAAAAMgAAABgAAAABAAAAAAAaAAAFGgAAAAAAAgAAABgAAAC4AQAAAwAAAOgBAAAgAAAACwAAAFAAAAAAAAAAAgAAAAIAAAABAAAAAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAIBSwANf1gAAAAAAAAAACAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAYVAAAADgEAAAAAAAAAAAAADwAAAA4CAAAIAAAAAAAAAAEAAAAPAQAAAAAAAAAAAAAAX3NlY29uZF9wcm9iZQBsdG1wMQBsdG1wMAAAAAAAAA==")!),
]
let bsdArchives: [(String, [Int64])] = [
    ("ITxhcmNoPgojMS8yMCAgICAgICAgICAgMTc5MTM1MzQxMyAgNTAxICAgMjAgICAgMTAwNjQ0ICA3NiAgICAgICAgYApfXy5TWU1ERUYgU09SVEVEAAAAABAAAAAAAAAAkAAAAA0AAADwAgAAIAAAAF9maXJzdF9wcm9iZQBfc2Vjb25kX3Byb2JlAAAAAAAAIzEvMjggICAgICAgICAgIDE3OTEzNTM0MDcgIDUwMSAgIDAgICAgIDY0NCAgICAgNTQ4ICAgICAgIGAKZmlyc3QtbG9uZy1tZW1iZXItbmFtZS5vAAAAAM/67f4MAAABAAAAAAEAAAAEAAAAaAEAAAAgAAAAAAAAGQAAAOgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoAAAAAAAAAIgBAAAAAAAAKAAAAAAAAAAHAAAABwAAAAIAAAAAAAAAX190ZXh0AAAAAAAAAAAAAF9fVEVYVAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAiAEAAAIAAAAAAAAAAAAAAAAEAIAAAAAAAAAAAAAAAABfX2NvbXBhY3RfdW53aW5kX19MRAAAAAAAAAAAAAAAAAgAAAAAAAAAIAAAAAAAAACQAQAAAwAAALABAAABAAAAAAAAAgAAAAAAAAAAAAAAADIAAAAYAAAAAQAAAAAAGgAABRoAAAAAAAIAAAAYAAAAuAEAAAMAAADoAQAAIAAAAAsAAABQAAAAAAAAAAIAAAACAAAAAQAAAAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIACAUsADX9YAAAAAAAAAAAgAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAGFAAAAA4BAAAAAAAAAAAAAA4AAAAOAgAACAAAAAAAAAABAAAADwEAAAAAAAAAAAAAAF9maXJzdF9wcm9iZQBsdG1wMQBsdG1wMAAAAAAAAAAjMS8zNiAgICAgICAgICAgMTc5MTM1MzQwNyAgNTAxICAgMCAgICAgNjQ0ICAgICA1NTYgICAgICAgYApzZWNvbmQtbG9uZy1tZW1iZXItbmFtZS5vAAAAAAAAAAAAAADP+u3+DAAAAQAAAAABAAAABAAAAGgBAAAAIAAAAAAAABkAAADoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKAAAAAAAAACIAQAAAAAAACgAAAAAAAAABwAAAAcAAAACAAAAAAAAAF9fdGV4dAAAAAAAAAAAAABfX1RFWFQAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAIgBAAACAAAAAAAAAAAAAAAABACAAAAAAAAAAAAAAAAAX19jb21wYWN0X3Vud2luZF9fTEQAAAAAAAAAAAAAAAAIAAAAAAAAACAAAAAAAAAAkAEAAAMAAACwAQAAAQAAAAAAAAIAAAAAAAAAAAAAAAAyAAAAGAAAAAEAAAAAABoAAAUaAAAAAAACAAAAGAAAALgBAAADAAAA6AEAACAAAAALAAAAUAAAAAAAAAACAAAAAgAAAAEAAAADAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAgFLAA1/WAAAAAAAAAAAIAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAQAABhUAAAAOAQAAAAAAAAAAAAAPAAAADgIAAAgAAAAAAAAAAQAAAA8BAAAAAAAAAAAAAABfc2Vjb25kX3Byb2JlAGx0bXAxAGx0bXAwAAAAAAAA", [232, 848]),
    ("ITxhcmNoPgojMS8xMiAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICA2OCAgICAgICAgYApfXy5TWU1ERUYAAAAQAAAAAAAAAIgAAAANAAAA6AIAABsAAABfZmlyc3RfcHJvYmUAX3NlY29uZF9wcm9iZQAAAAAAACMxLzI4ICAgICAgICAgICAwICAgICAgICAgICAwICAgICAwICAgICA2NDQgICAgIDU0OCAgICAgICBgCmZpcnN0LWxvbmctbWVtYmVyLW5hbWUubwAAAADP+u3+DAAAAQAAAAABAAAABAAAAGgBAAAAIAAAAAAAABkAAADoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKAAAAAAAAACIAQAAAAAAACgAAAAAAAAABwAAAAcAAAACAAAAAAAAAF9fdGV4dAAAAAAAAAAAAABfX1RFWFQAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAIgBAAACAAAAAAAAAAAAAAAABACAAAAAAAAAAAAAAAAAX19jb21wYWN0X3Vud2luZF9fTEQAAAAAAAAAAAAAAAAIAAAAAAAAACAAAAAAAAAAkAEAAAMAAACwAQAAAQAAAAAAAAIAAAAAAAAAAAAAAAAyAAAAGAAAAAEAAAAAABoAAAUaAAAAAAACAAAAGAAAALgBAAADAAAA6AEAACAAAAALAAAAUAAAAAAAAAACAAAAAgAAAAEAAAADAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAgFLAA1/WAAAAAAAAAAAIAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAQAABhQAAAAOAQAAAAAAAAAAAAAOAAAADgIAAAgAAAAAAAAAAQAAAA8BAAAAAAAAAAAAAABfZmlyc3RfcHJvYmUAbHRtcDEAbHRtcDAAAAAAAAAAIzEvMjggICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDY0NCAgICAgNTQ4ICAgICAgIGAKc2Vjb25kLWxvbmctbWVtYmVyLW5hbWUubwAAAM/67f4MAAABAAAAAAEAAAAEAAAAaAEAAAAgAAAAAAAAGQAAAOgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoAAAAAAAAAIgBAAAAAAAAKAAAAAAAAAAHAAAABwAAAAIAAAAAAAAAX190ZXh0AAAAAAAAAAAAAF9fVEVYVAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAiAEAAAIAAAAAAAAAAAAAAAAEAIAAAAAAAAAAAAAAAABfX2NvbXBhY3RfdW53aW5kX19MRAAAAAAAAAAAAAAAAAgAAAAAAAAAIAAAAAAAAACQAQAAAwAAALABAAABAAAAAAAAAgAAAAAAAAAAAAAAADIAAAAYAAAAAQAAAAAAGgAABRoAAAAAAAIAAAAYAAAAuAEAAAMAAADoAQAAIAAAAAsAAABQAAAAAAAAAAIAAAACAAAAAQAAAAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQACAUsADX9YAAAAAAAAAAAgAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAGFQAAAA4BAAAAAAAAAAAAAA8AAAAOAgAACAAAAAAAAAABAAAADwEAAAAAAAAAAAAAAF9zZWNvbmRfcHJvYmUAbHRtcDEAbHRtcDAAAAAAAAA=", [224, 832]),
    ("ITxhcmNoPgojMS8xMiAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICA5MiAgICAgICAgYApfXy5TWU1ERUZfNjQgAAAAAAAAAAAAAAAAAAAAoAAAAAAAAAANAAAAAAAAAAADAAAAAAAAGwAAAAAAAABfZmlyc3RfcHJvYmUAX3NlY29uZF9wcm9iZQAAAAAAACMxLzI4ICAgICAgICAgICAwICAgICAgICAgICAwICAgICAwICAgICA2NDQgICAgIDU0OCAgICAgICBgCmZpcnN0LWxvbmctbWVtYmVyLW5hbWUubwAAAADP+u3+DAAAAQAAAAABAAAABAAAAGgBAAAAIAAAAAAAABkAAADoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKAAAAAAAAACIAQAAAAAAACgAAAAAAAAABwAAAAcAAAACAAAAAAAAAF9fdGV4dAAAAAAAAAAAAABfX1RFWFQAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAIgBAAACAAAAAAAAAAAAAAAABACAAAAAAAAAAAAAAAAAX19jb21wYWN0X3Vud2luZF9fTEQAAAAAAAAAAAAAAAAIAAAAAAAAACAAAAAAAAAAkAEAAAMAAACwAQAAAQAAAAAAAAIAAAAAAAAAAAAAAAAyAAAAGAAAAAEAAAAAABoAAAUaAAAAAAACAAAAGAAAALgBAAADAAAA6AEAACAAAAALAAAAUAAAAAAAAAACAAAAAgAAAAEAAAADAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAgFLAA1/WAAAAAAAAAAAIAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAQAABhQAAAAOAQAAAAAAAAAAAAAOAAAADgIAAAgAAAAAAAAAAQAAAA8BAAAAAAAAAAAAAABfZmlyc3RfcHJvYmUAbHRtcDEAbHRtcDAAAAAAAAAAIzEvMjggICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDY0NCAgICAgNTQ4ICAgICAgIGAKc2Vjb25kLWxvbmctbWVtYmVyLW5hbWUubwAAAM/67f4MAAABAAAAAAEAAAAEAAAAaAEAAAAgAAAAAAAAGQAAAOgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoAAAAAAAAAIgBAAAAAAAAKAAAAAAAAAAHAAAABwAAAAIAAAAAAAAAX190ZXh0AAAAAAAAAAAAAF9fVEVYVAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAiAEAAAIAAAAAAAAAAAAAAAAEAIAAAAAAAAAAAAAAAABfX2NvbXBhY3RfdW53aW5kX19MRAAAAAAAAAAAAAAAAAgAAAAAAAAAIAAAAAAAAACQAQAAAwAAALABAAABAAAAAAAAAgAAAAAAAAAAAAAAADIAAAAYAAAAAQAAAAAAGgAABRoAAAAAAAIAAAAYAAAAuAEAAAMAAADoAQAAIAAAAAsAAABQAAAAAAAAAAIAAAACAAAAAQAAAAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQACAUsADX9YAAAAAAAAAAAgAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAGFQAAAA4BAAAAAAAAAAAAAA8AAAAOAgAACAAAAAAAAAABAAAADwEAAAAAAAAAAAAAAF9zZWNvbmRfcHJvYmUAbHRtcDEAbHRtcDAAAAAAAAA=", [248, 856])
]
for (encoded, _) in bsdArchives {
    let source = ShortReadSource(data: bytes(Array(Data(base64Encoded: encoded)!)), chunk: 3)
    let reader = try ArchiveKodec.shared.open(source: source)
    let entries = try reader.entries()
    precondition(entries.map { $0.name } == ["first-long-member-name.o", "second-long-member-name.o"])
    for (index, entry) in entries.enumerated() {
        let expected = bsdObjects[index]
        precondition(!entry.isDirectory && entry.size == Int64(expected.count))
        let payload = try reader.read(entry: entry, limits: limits)
        precondition(payload.size == Int32(expected.count))
        for (offset, value) in expected.enumerated() {
            precondition(payload.get(index: Int32(offset)) == Int8(bitPattern: value))
        }
    }
    try reader.close()
    precondition(source.closes == 1)
}
let bsdEdgesArchive = Array(Data(base64Encoded: "ITxhcmNoPgojMS8yNCAgICAgICAgICAgMTc5MTM1MzU5OSAgNTAxICAgMCAgICAgMTAwNjQ0ICAyNSAgICAgICAgYApvZGQtbG9uZy1tZW1iZXItbmFtZS5vAAB4CiMxLzE2ICAgICAgICAgICAxNzkxMzUzNTk5ICA1MDEgICAwICAgICAxMDA2NDQgIDE4ICAgICAgICBgCsOpIGxvbmcgbWVtYmVyLm95eiMxLzI0ICAgICAgICAgICAxNzkxMzUzNTk5ICA1MDEgICAwICAgICAxMDA2NDQgIDI3ICAgICAgICBgCumbqi1sb25nLW5hbWUtbWVtYmVyLm8AAGFiYwojMS8xNiAgICAgICAgICAgMTc5MTM1MzU5OSAgNTAxICAgMCAgICAgMTAwNjQ0ICAxNyAgICAgICAgYAp0cmFpbGluZyBuYW1lIAAAcQojMS8yNCAgICAgICAgICAgMTc5MTM1MzU5OSAgNTAxICAgMCAgICAgMTAwNjQ0ICAyNCAgICAgICAgYAplbXB0eS1sb25nLW1lbWJlci1uYW1lLm8=")!)
let bsdEdgesSource = ShortReadSource(data: bytes(bsdEdgesArchive), chunk: 3)
let bsdEdgesReader = try ArchiveKodec.shared.open(source: bsdEdgesSource)
let bsdEdgesEntries = try bsdEdgesReader.entries()
precondition(bsdEdgesEntries.map { $0.name } == ["odd-long-member-name.o", "é long member.o", "雪-long-name-member.o", "trailing name ", "empty-long-member-name.o"])
for (entry, text) in Swift.zip(bsdEdgesEntries, ["x", "yz", "abc", "q", ""]) {
    let expected = Array(text.utf8)
    precondition(entry.size == Int64(expected.count))
    let payload = try bsdEdgesReader.read(entry: entry, limits: limits)
    precondition(payload.size == Int32(expected.count))
    for (offset, value) in expected.enumerated() {
        precondition(payload.get(index: Int32(offset)) == Int8(bitPattern: value))
    }
}
try bsdEdgesReader.close()
precondition(bsdEdgesSource.closes == 1)
for (marker, label) in [("#1/+20", "invalid BSD length"), ("#1/77", "outside BSD length")] {
    var archive = Array(Data(base64Encoded: bsdArchives[0].0)!)
    archive.replaceSubrange(8..<24, with: Array(marker.padding(toLength: 16, withPad: " ", startingAt: 0).utf8))
    let source = ShortReadSource(data: bytes(archive), chunk: 3)
    let reader = try ArchiveKodec.shared.open(source: source)
    expectFailure(label, check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 8 && $0.entryName == marker }) {
        _ = try reader.entries()
    }
    try reader.close()
    precondition(source.closes == 1)
}
var bsdControlArchive = bsdEdgesArchive
var bsdControlNameBytes = Array("odd-long-member-name.o".utf8)
bsdControlNameBytes[3] = 10
bsdControlNameBytes[4] = 0
bsdControlArchive[71] = 10
bsdControlArchive[72] = 0
let bsdControlName = String(decoding: bsdControlNameBytes, as: UTF8.self)
let bsdControlSource = ShortReadSource(data: bytes(bsdControlArchive), chunk: 3)
let bsdControlReader = try ArchiveKodec.shared.open(source: bsdControlSource)
let bsdControlEntries = try bsdControlReader.entries()
precondition(bsdControlEntries[0].name == bsdControlName)
let bsdControlPayload = try bsdControlReader.read(entry: bsdControlEntries[0], limits: limits)
precondition(bsdControlPayload.size == 1 && bsdControlPayload.get(index: 0) == 120)
precondition(!PathGuard.shared.isSafe(entryName: bsdControlName))
expectFailure("BSD control path", check: { $0 is ArchivePathException && $0.entryName == bsdControlName }) {
    _ = try PathGuard.shared.sanitize(entryName: bsdControlName)
}
try bsdControlReader.close()
precondition(bsdControlSource.closes == 1)

// Legal short reads cross the Objective-C source and Kotlin reader before
// returning complete metadata and payloads to Swift.
let shortEmptyZip = ShortReadSource(data: bytes([0x50, 0x4b, 0x05, 0x06] + Array(repeating: 0, count: 18)), chunk: 1)
let shortEmptyZipReader = try ArchiveKodec.shared.open(source: shortEmptyZip)
precondition(shortEmptyZipReader.format == ArchiveFormat.companion.ZIP && shortEmptyZip.calls == 22)
let shortEmptyZipEntries = try shortEmptyZipReader.entries()
precondition(shortEmptyZipEntries.isEmpty && shortEmptyZip.closes == 0)
try shortEmptyZipReader.close()
try shortEmptyZipReader.close()
precondition(shortEmptyZip.closes == 1)
for chunk in [Int32(1), Int32(3)] {
    let source = ShortReadSource(data: bytes(v7Header + [97] + Array(repeating: 0, count: 1535)), chunk: chunk)
    let reader = try ArchiveKodec.shared.open(source: source)
    precondition(reader.format == ArchiveFormat.companion.TAR && source.calls == (512 + chunk - 1) / chunk)
    let entries = try reader.entries()
    precondition(entries.count == 1)
    let entry = entries[0]
    precondition(entry.name == "f.txt" && entry.size == 1)
    let payload = try reader.read(entry: entry, limits: limits)
    precondition(payload.size == 1 && payload.get(index: 0) == 97 && source.closes == 0)
    try reader.close()
    precondition(source.closes == 1)
}
let shortEmptyTar = ShortReadSource(data: KotlinByteArray(size: 512), chunk: 1)
let shortEmptyTarReader = try ArchiveKodec.shared.open(source: shortEmptyTar)
precondition(shortEmptyTarReader.format == ArchiveFormat.companion.TAR && shortEmptyTar.calls == 512)
let shortEmptyTarEntries = try shortEmptyTarReader.entries()
precondition(shortEmptyTarEntries.isEmpty && shortEmptyTar.closes == 0)
try shortEmptyTarReader.close()
precondition(shortEmptyTar.closes == 1)

let nonprogressSource = ShortReadSource(data: KotlinByteArray(size: 512), chunk: 0)
expectFailure("nonprogress source read", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 0 }) {
    _ = try ArchiveKodec.shared.open(source: nonprogressSource)
}
precondition(nonprogressSource.calls == 1 && nonprogressSource.closes == 0)
try nonprogressSource.close()
precondition(nonprogressSource.closes == 1)
let negativeSizeSource = ShortReadSource(data: KotlinByteArray(size: 512), chunk: 1)
negativeSizeSource.reportedSize = -1
expectFailure("negative source size", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 0 }) {
    _ = try ArchiveKodec.shared.sniff(source: negativeSizeSource)
}
precondition(negativeSizeSource.calls == 0 && negativeSizeSource.closes == 0)
try negativeSizeSource.close()
precondition(negativeSizeSource.closes == 1)
let shortZeroSource = ShortReadSource(data: KotlinByteArray(size: 511), chunk: 1)
shortZeroSource.reportedSize = 512
expectFailure("premature source EOF", check: { $0 is ArchiveFormatException && $0.offset?.int64Value == 511 }) {
    _ = try ArchiveKodec.shared.open(source: shortZeroSource)
}
precondition(shortZeroSource.calls == 512 && shortZeroSource.closes == 0)
try shortZeroSource.close()
precondition(shortZeroSource.closes == 1)

expectFailure("ZIP entries", check: { $0 is ArchiveFormatException }) {
    let reader: ArchiveReader = ZipReader(source: ByteArrayRandomAccessSource(data: empty))
    _ = try reader.entries()
}
let badTar = KotlinByteArray(size: 512)
for index in 0..<Int32(512) { badTar.set(index: index, value: 65) }
badTar.set(index: 156, value: 48)
expectFailure("TAR header checksum", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 0 && $0.entryName == nil && $0.message?.contains("checksum") == true
}) { _ = try TarReader(source: ByteArrayRandomAccessSource(data: badTar)).entries() }
expectFailure("TAR partial header", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == 0 && $0.message?.contains("truncated header") == true
}) { _ = try TarReader(source: ByteArrayRandomAccessSource(data: bytes([65, 66]))).entries() }
let oversizedName = String(repeating: "x", count: 65536)
expectFailure("ZIP filename", check: { $0 is ArchiveFormatException && $0.entryName == oversizedName }) {
    _ = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: oversizedName, data: empty, method: ZipMethod.stored)])
}
let invalidWriterNames = ["a\\b.txt", "/abs.txt", "C:relative.txt", "./C:/file.txt", "RAM:file.txt", "dir/../up.txt", "a\u{0}b", "a\u{7f}b"]
for method in [ZipMethod.stored, ZipMethod.deflated] {
    for name in invalidWriterNames {
        expectFailure("ZIP writer path \(name)", check: { $0 is ArchiveFormatException && $0.entryName == name }) {
            _ = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: name, data: empty, method: method)])
        }
    }
    for name in ["", "./", "a//./b", "é/文件😀", "dir/report:today.txt"] {
        let archive = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: name, data: bytes([65, 66, 67]), method: method)])
        let reader = ZipReader(source: ByteArrayRandomAccessSource(data: archive))
        let entries = try reader.entries()
        precondition(entries.count == 1 && entries[0].name == name)
        let restored = try reader.read(entry: entries[0], limits: limits)
        precondition(restored.size == 3 && restored.get(index: 1) == 66)
        try reader.close()
    }
}
let countedZip = try ZipWriter.shared.write(files: [
    try ZipWriter.FileSpec(name: "first", data: empty, method: ZipMethod.stored),
    try ZipWriter.FileSpec(name: "second", data: empty, method: ZipMethod.stored)
])
for offset in [countedZip.size - 14, countedZip.size - 12] {
    countedZip.set(index: offset, value: 1)
    countedZip.set(index: offset + 1, value: 0)
}
expectFailure("ZIP directory count", check: { $0 is ArchiveFormatException && $0.message?.contains("count") == true }) {
    _ = try ZipReader(source: ByteArrayRandomAccessSource(data: countedZip)).entries()
}
let splitZip = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: "a.txt", data: bytes([65, 66, 67]), method: ZipMethod.stored)])
splitZip.set(index: splitZip.size - 18, value: 1)
expectFailure("ZIP multi-disk metadata", check: {
    $0 is ArchiveUnsupportedException && $0.offset?.int64Value == Int64(splitZip.size - 18) && $0.message?.contains("multi-disk") == true
}) {
    _ = try ZipReader(source: ByteArrayRandomAccessSource(data: splitZip)).entries()
}
let missingZip64 = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: "a.txt", data: bytes([65, 66, 67]), method: ZipMethod.stored)])
let zip64Central = missingZip64.size - 73
for index in (zip64Central + 24)..<(zip64Central + 28) { missingZip64.set(index: index, value: -1) }
expectFailure("ZIP64 entry metadata", check: {
    $0 is ArchiveFormatException && $0.entryName == "a.txt" && $0.offset?.int64Value == Int64(zip64Central + 51)
}) {
    _ = try ZipReader(source: ByteArrayRandomAccessSource(data: missingZip64)).entries()
}
for (offset, value) in [(Int32(8), Int8(8)), (Int32(30), Int8(98)), (Int32(6), Int8(1))] {
    let inconsistent = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: "a.txt", data: bytes([65, 66, 67]), method: ZipMethod.stored)])
    inconsistent.set(index: offset, value: value)
    expectFailure("ZIP local field \(offset)", check: {
        $0 is ArchiveFormatException && $0.entryName == "a.txt" && $0.offset?.int64Value == Int64(offset)
    }) {
        let reader: ArchiveReader = ZipReader(source: ByteArrayRandomAccessSource(data: inconsistent))
        _ = try reader.entries()
    }
}
let encrypted = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: "a.txt", data: bytes([65, 66, 67]), method: ZipMethod.stored)])
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
let zip = try ZipWriter.shared.write(files: [try ZipWriter.FileSpec(name: "bad", data: bytes([65]), method: ZipMethod.deflated)])
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
    $0 is ArchiveClosedException && $0.entryName == "bad" && $0.offset?.int64Value == 0
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
        $0 is ArchiveClosedException && $0.entryName == "bad" && $0.offset?.int64Value == 0
    }) { _ = try reader.read(entry: entry, limits: limits) }
}
let concreteSource = ClosingSource(failure: closeError)
let concreteReader = ZipReader(source: concreteSource)
expectFailure("concrete ZIP cleanup", check: { $0 is ArchiveFormatException }) { try concreteReader.close() }
try concreteReader.close()
precondition(concreteSource.closes == 1)
let zero = try makeLimits(maxUncompressedBytes: 0, maxCompressionRatio: 10000, maxEntries: 100000, maxNestingDepth: 8)
let compressed = try Gzip.shared.compress(data: bytes([65, 66, 67]))
expectFailure("output budget", check: { $0 is DecompressionBombException }) { _ = try Gzip.shared.decompress(input: compressed, limits: zero) }
expectFailure("limit guard", check: { $0 is DecompressionBombException }) { try zero.checkOutputSize(bytes: 1) }
let valid = try Gzip.shared.decompress(input: compressed, limits: limits)
precondition(valid.size == 3)
let safe = try PathGuard.shared.sanitize(entryName: "safe/file")
precondition(safe == "safe/file")
// Real bsdcpio hard links expose the same logical bytes through each name.
let cpioLinkArchives = [
    "MDcwNzAxMDkxMjBmNDIwMDAwODFhNDAwMDAwMWY1MDAwMDAwMDAwMDAwMDAwMjZhYzVmNzYyMDAwMDAwMDAwMDAwMDAwMTAwMDAwMDBkMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA4MDAwMDAwMDBvbmUudHh0AAAAMDcwNzAxMDkxMjBmNDIwMDAwODFhNDAwMDAwMWY1MDAwMDAwMDAwMDAwMDAwMjZhYzVmNzYyMDAwMDAwMDgwMDAwMDAwMTAwMDAwMDBkMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA4MDAwMDAwMDB0d28udHh0AAAAREFUQS0xMjMwNzA3MDEwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAxMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMGIwMDAwMDAwMFRSQUlMRVIhISEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "MDcwNzA3Nzc3Nzc3MDAwMDAxMTAwNjQ0MDAwNzY1MDAwMDAwMDAwMDAyMDAwMDAwMTUyNjEzNzM1NDIwMDAwMTAwMDAwMDAwMDAxMG9uZS50eHQAREFUQS0xMjMwNzA3MDc3Nzc3NzcwMDAwMDExMDA2NDQwMDA3NjUwMDAwMDAwMDAwMDIwMDAwMDAxNTI2MTM3MzU0MjAwMDAxMDAwMDAwMDAwMDEwdHdvLnR4dABEQVRBLTEyMzA3MDcwNzAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMTAwMDAwMDAwMDAwMDAwMDAwMDAwMDEzMDAwMDAwMDAwMDBUUkFJTEVSISEhAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "MDcwNzAyMDkxMjBmNDIwMDAwODFhNDAwMDAwMWY1MDAwMDAwMDAwMDAwMDAwMjZhYzVmNzYyMDAwMDAwMDAwMDAwMDAwMTAwMDAwMDBkMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA4MDAwMDAwMDBvbmUudHh0AAAAMDcwNzAyMDkxMjBmNDIwMDAwODFhNDAwMDAwMWY1MDAwMDAwMDAwMDAwMDAwMjZhYzVmNzYyMDAwMDAwMDgwMDAwMDAwMTAwMDAwMDBkMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA4MDAwMDAxZGR0d28udHh0AAAAREFUQS0xMjMwNzA3MDIwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAxMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMGIwMDAwMDAwMFRSQUlMRVIhISEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
]
for encoded in cpioLinkArchives {
    let data = bytes(Array(Data(base64Encoded: encoded)!))
    let reader: ArchiveReader = CpioReader(source: ByteArrayRandomAccessSource(data: data))
    let entries = try reader.entries()
    precondition(entries.map { $0.name } == ["one.txt", "two.txt"])
    for entry in entries {
        precondition(entry.size == 8 && entry.compressedSize == 8 && entry.crc32 == nil)
        let result = try reader.read(entry: entry, limits: limits)
        precondition(result.size == 8)
        for (index, value) in Array("DATA-123".utf8).enumerated() {
            precondition(result.get(index: Int32(index)) == Int8(bitPattern: value))
        }
    }
    try reader.close()
}
let crcLinkBytes = bytes(Array(Data(base64Encoded: cpioLinkArchives[2])!))
let crcLinkReader: ArchiveReader = CpioReader(source: ByteArrayRandomAccessSource(data: crcLinkBytes))
let crcLinkEntries = try crcLinkReader.entries()
let cpioLimit = try makeLimits(maxUncompressedBytes: 7, maxCompressionRatio: 10000, maxEntries: 100000, maxNestingDepth: 8)
for entry in crcLinkEntries {
    expectFailure("cpio logical alias limit", check: { $0 is DecompressionBombException && $0.entryName == entry.name }) {
        _ = try crcLinkReader.read(entry: entry, limits: cpioLimit)
    }
}
// A successful earlier read cannot cache integrity for a changed source.
_ = try crcLinkReader.read(entry: crcLinkEntries[0], limits: limits)
crcLinkBytes.set(index: 240, value: 0)
for entry in crcLinkEntries {
    expectFailure("cpio changed carrier checksum", check: {
        $0 is ArchiveFormatException && $0.entryName == "two.txt" && $0.offset?.int64Value == 222
    }) { _ = try crcLinkReader.read(entry: entry, limits: limits) }
}
try crcLinkReader.close()
for (offset, name) in [(Int32(102), "one.txt"), (Int32(350), "TRAILER!!!")] {
    let invalid = bytes(Array(Data(base64Encoded: cpioLinkArchives[2])!))
    invalid.set(index: offset + 7, value: 49)
    expectFailure("cpio empty checksum \(name)", check: {
        $0 is ArchiveFormatException && $0.entryName == name && $0.offset?.int64Value == Int64(offset)
    }) { _ = try CpioReader(source: ByteArrayRandomAccessSource(data: invalid)).entries() }
}

// Python-produced prefixed ZIPs cover both relative and already absolute offsets.
let prefixedZipArchives = [
    "IyEvdXNyL2Jpbi9lbnYgcHl0aG9uMwpQSwMEFAAAAAAAAAAhAEgDg6MDAAAAAwAAAAUAAABhLnR4dEFCQ1BLAQIUAxQAAAAAAAAAIQBIA4OjAwAAAAMAAAAFAAAAAAAAAAAAAACAAQAAAABhLnR4dFBLBQYAAAAAAQABADMAAAAmAAAAAAA=",
    "IyEvdXNyL2Jpbi9lbnYgcHl0aG9uMwpQSwMEFAAAAAAAAAAhAEgDg6MDAAAAAwAAAAUAAABhLnR4dEFCQ1BLAQIUAxQAAAAAAAAAIQBIA4OjAwAAAAMAAAAFAAAAAAAAAAAAAACAARcAAABhLnR4dFBLBQYAAAAAAQABADMAAAA9AAAAAAA=",
    "IyEvdXNyL2Jpbi9lbnYgcHl0aG9uMwpQSwMELQAAAAAAAAAhAEgDg6P//////////wUAFABhLnR4dAEAEAADAAAAAAAAAAMAAAAAAAAAQUJDUEsBAi0DLQAAAAAAAAAhAEgDg6P//////////wUAFAAAAAAAAAAAAIABAAAAAGEudHh0AQAQAAMAAAAAAAAAAwAAAAAAAABQSwYGLAAAAAAAAAAtAC0AAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAARwAAAAAAAAA6AAAAAAAAAFBLBgcAAAAAgQAAAAAAAAABAAAAUEsFBgAAAAABAAEARwAAADoAAAAAAA==",
    "IyEvdXNyL2Jpbi9lbnYgcHl0aG9uMwpQSwMELQAAAAAAAAAhAEgDg6P//////////wUAFABhLnR4dAEAEAADAAAAAAAAAAMAAAAAAAAAQUJDUEsBAi0DLQAAAAAAAAAhAEgDg6P//////////wUAHAAAAAAAAAAAAIAB/////2EudHh0AQAYAAMAAAAAAAAAAwAAAAAAAAAXAAAAAAAAAFBLBgYsAAAAAAAAAC0ALQAAAAAAAAAAAAEAAAAAAAAAAQAAAAAAAABPAAAAAAAAAFEAAAAAAAAAUEsGBwAAAACgAAAAAAAAAAEAAABQSwUGAAAAAAEAAQBPAAAAUQAAAAAA",
]
func verifyPrefixedZip(_ data: KotlinByteArray) throws {
    let readers: [ArchiveReader] = [
        ZipReader(source: ByteArrayRandomAccessSource(data: data)),
        try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: data)),
        try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: data), nameDecoder: ArchiveNameDecoderCompanion.shared.UTF8)
    ]
    for reader in readers {
        let entry = try reader.entries()[0]
        precondition(entry.name == "a.txt")
        let output = try reader.read(entry: entry, limits: limits)
        precondition(output.size == 3 && output.get(index: 0) == 65 && output.get(index: 1) == 66 && output.get(index: 2) == 67)
        try reader.close()
    }
}
for encoded in prefixedZipArchives { try verifyPrefixedZip(bytes(Array(Data(base64Encoded: encoded)!))) }
let prefixedZip64Raw = Array(Data(base64Encoded: prefixedZipArchives[2])!)
let prefixedZip64Record = prefixedZip64Raw.count - 98
var extensibleHeader = Array(prefixedZip64Raw[prefixedZip64Record..<(prefixedZip64Record + 56)])
let extensibleSector: [UInt8] = [255, 255, 0, 0, 1, 0] + Array(repeating: 65, count: 65536)
let extensibleRecordSize = UInt64(44 + extensibleSector.count)
for index in 0..<8 { extensibleHeader[4 + index] = UInt8(truncatingIfNeeded: extensibleRecordSize >> (8 * index)) }
let extensibleZip64 = Array(prefixedZip64Raw[..<prefixedZip64Record]) + extensibleHeader + extensibleSector + Array(prefixedZip64Raw[(prefixedZip64Record + 56)...])
try verifyPrefixedZip(bytes(extensibleZip64))
let invalidPrefixLocal = bytes(Array(Data(base64Encoded: prefixedZipArchives[0])!))
invalidPrefixLocal.set(index: 31, value: 8)
expectFailure("prefixed ZIP local method", check: {
    $0 is ArchiveFormatException && $0.entryName == "a.txt" && $0.offset?.int64Value == 31
}) { _ = try ZipReader(source: ByteArrayRandomAccessSource(data: invalidPrefixLocal)).entries() }
let invalidPrefixCrc = bytes(Array(Data(base64Encoded: prefixedZipArchives[0])!))
invalidPrefixCrc.set(index: 58, value: 0)
let prefixCrcReader: ArchiveReader = try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: invalidPrefixCrc))
let prefixCrcEntry = try prefixCrcReader.entries()[0]
expectFailure("prefixed ZIP CRC", check: {
    $0 is ArchiveFormatException && $0.entryName == "a.txt" && $0.offset?.int64Value == 58
}) { _ = try prefixCrcReader.read(entry: prefixCrcEntry, limits: limits) }
try prefixCrcReader.close()
let invalidPrefixLocator = bytes(prefixedZip64Raw)
let prefixLocatorField = invalidPrefixLocator.size - 34
for index in 0..<Int32(8) { invalidPrefixLocator.set(index: prefixLocatorField + index, value: -1) }
expectFailure("prefixed ZIP64 locator offset", check: {
    $0 is ArchiveFormatException && $0.offset?.int64Value == Int64(prefixLocatorField)
}) { _ = try ZipReader(source: ByteArrayRandomAccessSource(data: invalidPrefixLocator)).entries() }
let boundedPrefixReader: ArchiveReader = try ArchiveKodec.shared.open(source: ByteArrayRandomAccessSource(data: bytes(Array(Data(base64Encoded: prefixedZipArchives[0])!))))
let boundedPrefixEntry = try boundedPrefixReader.entries()[0]
let prefixBudget = try makeLimits(maxUncompressedBytes: 2, maxCompressionRatio: 10000, maxEntries: 100000, maxNestingDepth: 8)
expectFailure("prefixed ZIP output limit", check: { $0 is DecompressionBombException && $0.entryName == "a.txt" }) {
    _ = try boundedPrefixReader.read(entry: boundedPrefixEntry, limits: prefixBudget)
}
try boundedPrefixReader.close()

print("Core Swift consumer passed: \(caught) catchable failures and valid decode/path calls")
