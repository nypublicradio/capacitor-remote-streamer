// swift-tools-version: 5.9
import PackageDescription

// Package, product and module are all named after the CocoaPods pod so that
// `import NypublicradioCapacitorRemoteStreamer` works with either package manager,
// and so the Capacitor CLI's generated SPM manifest can find the product.
let package = Package(
    name: "NypublicradioCapacitorRemoteStreamer",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "NypublicradioCapacitorRemoteStreamer",
            targets: ["NypublicradioCapacitorRemoteStreamer"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "NypublicradioCapacitorRemoteStreamer",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/RemoteStreamerPlugin"),
        .testTarget(
            name: "NypublicradioCapacitorRemoteStreamerTests",
            dependencies: ["NypublicradioCapacitorRemoteStreamer"],
            path: "ios/Tests/RemoteStreamerPluginTests")
    ]
)
