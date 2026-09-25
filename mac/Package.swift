// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "PhoneDrop",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "PhoneDrop", path: "Sources/PhoneDrop")
    ]
)
