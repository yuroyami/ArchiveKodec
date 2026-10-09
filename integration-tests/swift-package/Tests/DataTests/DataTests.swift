import Foundation
import XCTest
import ArchiveKodecKit

/// The checks a Swift-only app needs: Data in and out, no KotlinByteArray built by hand,
/// no limits argument, and Kotlin failures caught as Swift errors.
final class DataTests: XCTestCase {
    private let kodec = ArchiveKodec.shared
    private let hello = Data("hello".utf8)
    // A ZIP holding a.txt ("hello", DEFLATE) and the directory dir/.
    private let zip = Data(base64Encoded: "UEsDBBQAAAAIAIMYIliGphA2BwAAAAUAAAAFAAAAYS50eHTLSM3JyQcAUEsDBBQAAAAAAIMYIlgAAAAAAAAAAAAAAAAEAAAAZGlyL1BLAQIUAxQAAAAIAIMYIliGphA2BwAAAAUAAAAFAAAAAAAAAAAAAACAAQAAAABhLnR4dFBLAQIUAxQAAAAAAIMYIlgAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAACAASoAAABkaXIvUEsFBgAAAAACAAIAZQAAAEwAAAAAAA==")!

    func testGzipRoundTrip() throws {
        let packed = try kodec.compress(codec: kodec.gzip, data: hello)
        XCTAssertEqual(ArchiveKodec.shared.sniff(header: try kodec.bytes(data: packed)), ArchiveFormat.companion.GZIP)
        XCTAssertEqual(try kodec.decompress(codec: kodec.gzip, data: packed), hello)
        XCTAssertEqual(try kodec.decompress(data: packed), hello)
    }

    func testZipListsAndReads() throws {
        let reader = try kodec.open(data: zip)
        defer { try? reader.close() }
        let entries = try reader.entries()
        XCTAssertEqual(entries.map { $0.name }, ["a.txt", "dir/"])
        XCTAssertEqual(entries[0].size, 5)
        XCTAssertTrue(entries[1].isDirectory)
        XCTAssertEqual(kodec.data(bytes: try reader.read(entry: entries[0])), hello)
        XCTAssertEqual(try kodec.read(reader: reader, entry: entries[0]), hello)
    }

    func testFailuresThrow() throws {
        XCTAssertThrowsError(try kodec.open(data: Data(count: 64))) { error in
            XCTAssertTrue((error as NSError).kotlinException is ArchiveException)
        }
        let small = try ArchiveLimits.companion.DEFAULT.doCopy { $0.maxUncompressedBytes = 100 }
        let zeros = try kodec.compress(codec: kodec.zlib, data: Data(count: 5000))
        XCTAssertThrowsError(try kodec.decompress(codec: kodec.zlib, data: zeros, limits: small))
    }

    func testEmptyData() throws {
        XCTAssertEqual(kodec.data(bytes: try kodec.bytes(data: Data())), Data())
    }
}
