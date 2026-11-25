import XCTest
@testable import NypublicradioCapacitorRemoteStreamer

class PlayerSettingsTests: XCTestCase {
    func testRendersUserAgentTemplate() {
        let userAgent = PlayerSettings.renderUserAgent(
            template: "WNYC-App/iOS {appVersion}; {deviceModel}; iOS {osVersion}",
            tokens: ["appVersion": "3.2.1", "deviceModel": "iPhone", "osVersion": "18.0"]
        )
        XCTAssertEqual(userAgent, "WNYC-App/iOS 3.2.1; iPhone; iOS 18.0")
    }

    func testTreatsUnsetConfigValuesAsMissing() {
        XCTAssertNil(PlayerSettings.nonEmpty(nil))
        XCTAssertNil(PlayerSettings.nonEmpty(""))
        XCTAssertNil(PlayerSettings.nonEmpty("  "))
        XCTAssertNil(PlayerSettings.nonEmpty("undefined"))
        XCTAssertEqual(PlayerSettings.nonEmpty("WNYC"), "WNYC")
    }
}

class PlayableItemTests: XCTestCase {
    func testReportsCanonicalUrlToJs() {
        let item = PlayableItem(mediaId: "live:wnyc-fm939", url: "https://example.org/s.aac?listenerid=1",
                                canonicalUrl: "https://example.org/s.aac", title: "WNYC 93.9 FM", isLive: true)
        XCTAssertEqual(item.reportedUrl, "https://example.org/s.aac")
        XCTAssertEqual(item.eventPayload["streamUrl"] as? String, "https://example.org/s.aac")
        XCTAssertEqual(item.eventPayload["mediaId"] as? String, "live:wnyc-fm939")
        XCTAssertEqual(item.eventPayload["isLive"] as? Bool, true)
        XCTAssertNil(item.eventPayload["duration"])
    }

    func testFallsBackToPlaybackUrl() {
        let item = PlayableItem(url: "https://example.org/e.mp3", isLive: false, durationSeconds: 1800)
        XCTAssertEqual(item.reportedUrl, "https://example.org/e.mp3")
        XCTAssertEqual(item.eventPayload["duration"] as? Double, 1800)
    }
}
