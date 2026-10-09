// swift-tools-version:5.9
// A Swift-only consumer of the generated package in build/swiftpm/ArchiveKodec. Run it with
// ./gradlew swiftPackageTest, which builds the XCFrameworks and that package first.
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
