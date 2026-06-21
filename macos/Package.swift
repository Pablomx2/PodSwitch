// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PodSwitch",
    platforms: [
        .macOS(.v13)
    ],
    targets: [
        .target(
            name: "PodSwitchCore"
        ),
        .executableTarget(
            name: "PodSwitch",
            dependencies: ["PodSwitchCore"]
        ),
        .testTarget(
            name: "PodSwitchCoreTests",
            dependencies: ["PodSwitchCore"]
        )
    ]
)
