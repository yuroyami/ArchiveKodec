// swift-tools-version:5.9
// A Swift-only consumer of the generated package in build/swiftpm/ArchiveKodec. Build the release
// XCFrameworks with scripts/apple_frameworks.py, then run package.py, then `swift test` in this folder.
import PackageDescription

let package = Package(
    name: "ArchiveKodecSwiftCheck",
    platforms: [.macOS(.v12)],
    dependencies: [
        .package(path: "../../build/swiftpm/ArchiveKodec"),
    ],
    targets: [
        .testTarget(
            name: "DataTests",
            dependencies: [.product(name: "ArchiveKodecKit", package: "ArchiveKodec")]
        ),
    ]
)
