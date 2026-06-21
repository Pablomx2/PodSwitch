import XCTest
@testable import PodSwitchCore

@MainActor
final class PlaybackEdgeDetectorTests: XCTestCase {

    // MARK: - Manual clock

    /// Deterministic `DebounceScheduler` that runs its pending action only on `fire()`.
    final class ManualDebounceScheduler: DebounceScheduler {
        private var pending: (@MainActor () -> Void)?
        var hasPending: Bool { pending != nil }

        func schedule(after interval: TimeInterval, _ action: @escaping @MainActor () -> Void) {
            pending = action
        }

        func cancel() {
            pending = nil
        }

        /// Simulate the interval elapsing.
        func fire() {
            let action = pending
            pending = nil
            action?()
        }
    }

    // MARK: - Fixture

    private var stopScheduler: ManualDebounceScheduler!
    private var sustainScheduler: ManualDebounceScheduler!
    private var detector: PlaybackEdgeDetector!
    private var started = 0
    private var stopped = 0

    override func setUp() {
        super.setUp()
        stopScheduler = ManualDebounceScheduler()
        sustainScheduler = ManualDebounceScheduler()
        detector = PlaybackEdgeDetector(
            debounceInterval: 0.75,
            sustainInterval: 1.0,
            stopScheduler: stopScheduler,
            sustainScheduler: sustainScheduler
        )
        started = 0
        stopped = 0
        detector.onStarted = { [weak self] in self?.started += 1 }
        detector.onStopped = { [weak self] in self?.stopped += 1 }
    }

    override func tearDown() {
        detector = nil
        stopScheduler = nil
        sustainScheduler = nil
        super.tearDown()
    }

    // MARK: - runningChanged: start edge (no routing change)

    func testRunningTrueFromIdleEmitsStartInstantly() {
        detector.runningChanged(true)
        XCTAssertEqual(started, 1)
        XCTAssertEqual(stopped, 0)
        XCTAssertTrue(detector.isPlaying)
        XCTAssertFalse(stopScheduler.hasPending)
        XCTAssertFalse(sustainScheduler.hasPending)
    }

    func testRunningTrueWhileAlreadyPlayingIsNoOp() {
        detector.runningChanged(true)
        detector.runningChanged(true)
        XCTAssertEqual(started, 1)
        XCTAssertTrue(detector.isPlaying)
        XCTAssertFalse(sustainScheduler.hasPending)
    }

    // MARK: - runningChanged: stop edge + debounce

    func testRunningFalseWhilePlayingArmsDebouncedStopWithoutEmitting() {
        detector.runningChanged(true)
        detector.runningChanged(false)
        XCTAssertEqual(stopped, 0, "stop must wait for the quiet window")
        XCTAssertTrue(detector.isPlaying)
        XCTAssertTrue(stopScheduler.hasPending)
    }

    func testRunningFalseWhileIdleIsNoOp() {
        detector.runningChanged(false)
        XCTAssertEqual(started, 0)
        XCTAssertEqual(stopped, 0)
        XCTAssertFalse(detector.isPlaying)
        XCTAssertFalse(stopScheduler.hasPending)
    }

    func testDebouncedStopCommitsOnFire() {
        detector.runningChanged(true)
        detector.runningChanged(false)
        stopScheduler.fire()
        XCTAssertEqual(stopped, 1)
        XCTAssertFalse(detector.isPlaying)
    }

    func testFlutterTrueFalseTrueCollapsesToSingleSession() {
        detector.runningChanged(true)
        detector.runningChanged(false)
        detector.runningChanged(true)
        XCTAssertEqual(started, 1, "flutter must not re-emit a start")
        XCTAssertEqual(stopped, 0)
        XCTAssertTrue(detector.isPlaying)
        XCTAssertFalse(stopScheduler.hasPending)
        XCTAssertFalse(sustainScheduler.hasPending, "no routing change -> no sustain confirmation")
    }

    func testStopAfterFlutterStillFires() {
        detector.runningChanged(true)
        detector.runningChanged(false)
        detector.runningChanged(true)
        detector.runningChanged(false)
        stopScheduler.fire()
        XCTAssertEqual(started, 1)
        XCTAssertEqual(stopped, 1)
        XCTAssertFalse(detector.isPlaying)
    }

    // MARK: - #3 — keep watching on the Mac after the headphones are taken

    /// Headphones taken with a gap, Mac keeps playing on the speaker: sustained playback re-steals.
    func testHeadphonesTakenButMacKeepsPlayingReSteals() {
        detector.runningChanged(true)
        XCTAssertEqual(started, 1)
        detector.deviceChanged(runningNow: false)
        XCTAssertTrue(stopScheduler.hasPending)
        detector.runningChanged(true)
        XCTAssertEqual(started, 1, "must confirm sustained before re-emitting")
        XCTAssertTrue(sustainScheduler.hasPending)
        sustainScheduler.fire()
        XCTAssertEqual(started, 2, "sustained speaker playback re-steals")
        XCTAssertTrue(detector.isPlaying)
    }

    /// Gapless reroute: speaker already running at the device flip, still confirmed before re-emit.
    func testHeadphonesTakenGaplessButMacKeepsPlayingReSteals() {
        detector.runningChanged(true)
        detector.deviceChanged(runningNow: true)
        XCTAssertEqual(started, 1, "not instant — confirmation pending")
        XCTAssertTrue(sustainScheduler.hasPending)
        sustainScheduler.fire()
        XCTAssertEqual(started, 2)
    }

    // MARK: - BUG #2 — the phone takes the headphones, Mac auto-pauses

    /// Headphones taken then the Mac auto-pauses: the confirmation is cancelled, no re-steal.
    func testHeadphonesTakenAndMacAutoPausesDoesNotReSteal() {
        detector.runningChanged(true)
        XCTAssertEqual(started, 1)
        detector.deviceChanged(runningNow: false)
        detector.runningChanged(true)
        XCTAssertTrue(sustainScheduler.hasPending)
        detector.runningChanged(false)
        XCTAssertFalse(sustainScheduler.hasPending, "the confirmation was cancelled")
        sustainScheduler.fire()
        XCTAssertEqual(started, 1, "no steal-back")
        stopScheduler.fire()
        XCTAssertEqual(stopped, 1)
        XCTAssertFalse(detector.isPlaying)
    }

    /// Fast auto-pause: the speaker never runs after the flip, just stops.
    func testHeadphonesTakenAndMacGoesSilentDoesNotReSteal() {
        detector.runningChanged(true)
        detector.deviceChanged(runningNow: false)
        XCTAssertTrue(stopScheduler.hasPending)
        XCTAssertFalse(sustainScheduler.hasPending)
        stopScheduler.fire()
        XCTAssertEqual(started, 1, "no steal-back")
        XCTAssertEqual(stopped, 1)
        XCTAssertFalse(detector.isPlaying)
    }

    // MARK: - BUG #1 — headphones drop on a sustained pause, then resume

    /// Headphones drop while idle after a committed stop, then resume re-steals instantly.
    func testHeadphonesDropWhileIdleThenResumeReStealsInstantly() {
        detector.runningChanged(true)
        XCTAssertEqual(started, 1)
        detector.runningChanged(false)
        stopScheduler.fire()
        XCTAssertEqual(stopped, 1)
        XCTAssertFalse(detector.isPlaying)
        detector.deviceChanged(runningNow: false)
        detector.runningChanged(true)
        XCTAssertEqual(started, 2, "fresh play after a real stop re-steals instantly")
        XCTAssertFalse(sustainScheduler.hasPending, "no confirmation needed when we were idle")
        XCTAssertTrue(detector.isPlaying)
    }

    // MARK: - Device change while idle

    /// A device change while idle onto a running device fires instantly, no confirmation.
    func testDeviceChangeWhileIdleToRunningIsInstant() {
        detector.deviceChanged(runningNow: true)
        XCTAssertEqual(started, 1)
        XCTAssertTrue(detector.isPlaying)
        XCTAssertFalse(sustainScheduler.hasPending)
    }

    // MARK: - prime / reset

    func testPrimeRunningSetsPlayingWithoutEmitting() {
        detector.prime(running: true)
        XCTAssertEqual(started, 0)
        XCTAssertTrue(detector.isPlaying)
        XCTAssertFalse(stopScheduler.hasPending)
        XCTAssertFalse(sustainScheduler.hasPending)
    }

    func testPrimeIdleLeavesDetectorIdle() {
        detector.prime(running: false)
        XCTAssertEqual(started, 0)
        XCTAssertFalse(detector.isPlaying)
    }

    func testPrimeCancelsPendingWork() {
        detector.runningChanged(true)
        detector.runningChanged(false)
        detector.deviceChanged(runningNow: true)
        XCTAssertTrue(sustainScheduler.hasPending)
        detector.prime(running: true)
        XCTAssertFalse(stopScheduler.hasPending)
        XCTAssertFalse(sustainScheduler.hasPending)
    }

    func testResetReturnsToIdleAndDropsPendingWork() {
        detector.runningChanged(true)
        detector.runningChanged(false)
        detector.reset()
        XCTAssertFalse(detector.isPlaying)
        XCTAssertFalse(stopScheduler.hasPending)
        stopScheduler.fire()
        XCTAssertEqual(stopped, 0, "a reset stop must not fire afterwards")
    }
}
