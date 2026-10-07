import Foundation
import ArchiveKodecIo

let limits = ArchiveLimits.companion.DEFAULT
let invalid = Buffer()
invalid.writeByte(byte: 7)
do {
    _ = try ArchiveKodecIoKt.decompress(ArchiveKodec.shared.gzip, source: invalid, limits: limits)
    fatalError("Accepted invalid gzip in the IO adapter")
} catch {
    precondition((error as NSError).kotlinException is ArchiveFormatException)
    print("Caught IO adapter format error")
}
let source = Buffer()
source.writeByte(byte: 65)
let data = try ArchiveKodecIoKt.readAllBytes(source)
precondition(data.size == 1)
let compressedSource = Buffer()
compressedSource.writeByte(byte: 66)
let compressed = try ArchiveKodecIoKt.compress(ArchiveKodec.shared.gzip, source: compressedSource)
let output = try ArchiveKodec.shared.gzip.decompress(data: compressed, limits: limits)
precondition(output.size == 1)
let seekableSource = Buffer()
seekableSource.writeByte(byte: 67)
let seekable = try ArchiveKodecIoKt.toRandomAccessSource(seekableSource)
precondition(seekable.size == 1)
try seekable.close()
print("IO Swift consumer passed: catchable decode error and all four adapter entry points")
