import XCTest
@testable import PodSwitchCore

@available(macOS 14.0, *)
@MainActor
final class ProcessPlaybackSignalTests: XCTestCase {

    private func makeSignal() -> (ProcessPlaybackSignal, () -> [Bool]) {
        let signal = ProcessPlaybackSignal()
        var emitted: [Bool] = []
        signal.onPlayingChanged = { emitted.append($0) }
        return (signal, { emitted })
    }

    func testApplyEmitsOnlyOnChange() {
        let (signal, emitted) = makeSignal()
        signal.apply(aggregate: false)
        XCTAssertEqual(emitted(), [])
        signal.apply(aggregate: true)
        XCTAssertEqual(emitted(), [true])
        signal.apply(aggregate: true)
        XCTAssertEqual(emitted(), [true])
        signal.apply(aggregate: false)
        XCTAssertEqual(emitted(), [true, false])
    }

    func testApplyCollapsesRepeats() {
        let (signal, emitted) = makeSignal()
        signal.apply(aggregate: true)
        signal.apply(aggregate: true)
        signal.apply(aggregate: true)
        XCTAssertEqual(emitted(), [true], "a steady-true aggregate emits exactly one edge")
    }
}
