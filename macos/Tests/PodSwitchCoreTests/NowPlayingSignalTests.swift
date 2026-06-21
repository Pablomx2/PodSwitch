import XCTest
@testable import PodSwitchCore

@MainActor
final class NowPlayingSignalTests: XCTestCase {

    private func parse(_ line: String) -> (playing: Bool, hasSession: Bool)? {
        NowPlayingSignal.parse(line: line)
    }

    func testPlayingPayload() {
        let r = parse(#"{"type":"data","diff":false,"payload":{"title":"X","bundleIdentifier":"com.x","playing":true}}"#)
        XCTAssertEqual(r?.playing, true)
        XCTAssertEqual(r?.hasSession, true)
    }

    func testPausedPayload() {
        let r = parse(#"{"type":"data","diff":false,"payload":{"title":"Netflix","bundleIdentifier":"company.thebrowser.Browser","playing":false}}"#)
        XCTAssertEqual(r?.playing, false)
        XCTAssertEqual(r?.hasSession, true, "a paused media app still has a session")
    }

    func testEmptyPayloadIsNoSession() {
        let r = parse(#"{"type":"data","diff":false,"payload":{}}"#)
        XCTAssertEqual(r?.playing, false)
        XCTAssertEqual(r?.hasSession, false)
    }

    func testRealNetflixLine() {
        let line = #"{"type":"data","diff":false,"payload":{"artist":"","playbackRate":0,"title":"Netflix","elapsedTime":1271.7,"duration":2600.8,"playing":false,"bundleIdentifier":"company.thebrowser.Browser","album":"","processIdentifier":28031}}"#
        let r = parse(line)
        XCTAssertEqual(r?.playing, false)
        XCTAssertEqual(r?.hasSession, true)
    }

    func testNonDataLineReturnsNil() {
        XCTAssertNil(parse(#"{"type":"other","payload":{"playing":true}}"#))
    }

    func testGarbageReturnsNil() {
        XCTAssertNil(parse("not json"))
        XCTAssertNil(parse(""))
        XCTAssertNil(parse("   "))
        XCTAssertNil(parse("null"))
    }

    func testMissingPayloadReturnsNil() {
        XCTAssertNil(parse(#"{"type":"data","diff":false}"#))
    }

    func testShellQuote() {
        XCTAssertEqual(NowPlayingSignal.shellQuote("/Applications/PodSwitch.app/x.pl"),
                       "'/Applications/PodSwitch.app/x.pl'")
        XCTAssertEqual(NowPlayingSignal.shellQuote("/a b/c"), "'/a b/c'")
        XCTAssertEqual(NowPlayingSignal.shellQuote("it's"), "'it'\\''s'")
    }
}
