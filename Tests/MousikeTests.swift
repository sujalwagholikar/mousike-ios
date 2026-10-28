import XCTest
@testable import Mousike

final class MousikeTests: XCTestCase {
    func testDurationNeverPrintsNaNOrNegative() {
        XCTAssertEqual(TimeLabel.string(.nan), "0:00")
        XCTAssertEqual(TimeLabel.string(-100), "0:00")
        XCTAssertEqual(TimeLabel.string(221), "3:41")
    }
    func testTrackRoundTrip() throws {
        let track = Track(id: "abcdefghijk", title: "Music", artist: "Artist", cover: "", duration: 100)
        XCTAssertEqual(try JSONDecoder().decode(Track.self, from: JSONEncoder().encode(track)), track)
    }
    func testStateSeparatesBusyFromPlaying() {
        XCTAssertTrue(PlaybackPhase.connecting.busy)
        XCTAssertTrue(PlaybackPhase.buffering.busy)
        XCTAssertFalse(PlaybackPhase.playing.busy)
        XCTAssertFalse(PlaybackPhase.failed("Offline").busy)
    }
    func testThreeHonestOnlineQualityChoices() {
        XCTAssertEqual(Quality.allCases.count, 3)
        XCTAssertFalse(Quality.allCases.contains { $0.rawValue.lowercased().contains("lossless") })
    }
}
