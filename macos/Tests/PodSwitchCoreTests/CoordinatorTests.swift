import XCTest
@testable import PodSwitchCore

@MainActor
final class CoordinatorTests: XCTestCase {

    // MARK: - Fakes

    final class FakeMonitor: AudioMonitoring {
        weak var delegate: AudioMonitorDelegate?
        private(set) var started = false
        private(set) var stopped = false

        func start() { started = true }
        func stop() { stopped = true }

        /// Drive an event as if CoreAudio reported it.
        func emit(_ event: SwitchEvent) {
            delegate?.audioMonitor(self, didEmit: event)
        }
    }

    /// Records connect attempts without performing a real connection.
    final class FakeBluetooth: BluetoothConnecting, @unchecked Sendable {
        var paired = true
        var active = false
        private(set) var connectCount = 0

        func isActiveOutput(deviceId: String) -> Bool { active }
        func isPaired(deviceId: String) -> Bool { paired }
        func connect(deviceId: String) {
            connectCount += 1
        }
    }

    final class FakeNotifier: Notifying {
        private(set) var promptCount = 0

        func showSwitchPrompt() { promptCount += 1 }
    }

    final class FakeSettings: SettingsStore {
        var config: Config
        init(_ config: Config) { self.config = config }
    }

    final class FakePresence: PresencePort, @unchecked Sendable {
        var peerActive = false
        var localActive: Bool?
        var onPeerChanged: (@Sendable () -> Void)?
        func peerActiveOnTarget() -> Bool { peerActive }
        func setLocalActive(_ active: Bool) { localActive = active }
    }

    private func makeConfig(
        enabled: Bool = true,
        mode: Mode = .steal,
        target: String? = "aa-bb-cc-dd-ee-ff",
        yield: Bool = false
    ) -> Config {
        Config(
            enabled: enabled,
            mode: mode,
            enabledCategories: [.media],
            targetDeviceId: target,
            yieldToOtherSource: yield
        )
    }

    private func makeCoordinator(
        config: Config,
        bluetooth: FakeBluetooth
    ) -> (Coordinator, FakeMonitor, FakeNotifier, FakeSettings) {
        let monitor = FakeMonitor()
        let notifier = FakeNotifier()
        let settings = FakeSettings(config)
        let coordinator = Coordinator(
            monitor: monitor,
            bluetooth: bluetooth,
            notifier: notifier,
            settings: settings
        )
        return (coordinator, monitor, notifier, settings)
    }

    // MARK: - Lifecycle

    func testStartAndStopForwardToMonitor() {
        let (coordinator, monitor, _, _) = makeCoordinator(
            config: makeConfig(),
            bluetooth: FakeBluetooth()
        )
        coordinator.start()
        coordinator.stop()
        XCTAssertTrue(monitor.started)
        XCTAssertTrue(monitor.stopped)
    }

    func testInitWiresDelegate() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            XCTAssertNotNil(monitor.delegate)
        }
    }

    // MARK: - Connect dispatch

    func testStealAudioStartedConnects() {
        let bt = FakeBluetooth()
        bt.paired = true
        bt.active = false
        let (coordinator, monitor, notifier, _) = makeCoordinator(config: makeConfig(mode: .steal), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 1)
        XCTAssertEqual(notifier.promptCount, 0)
    }

    func testAlreadyActiveDoesNotConnect() {
        let bt = FakeBluetooth()
        bt.active = true
        let (coordinator, monitor, notifier, _) = makeCoordinator(config: makeConfig(mode: .steal), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 0)
        XCTAssertEqual(notifier.promptCount, 0)
    }

    func testNotPairedDoesNotConnectOnAudioStarted() {
        let bt = FakeBluetooth()
        bt.paired = false
        let (coordinator, monitor, notifier, _) = makeCoordinator(config: makeConfig(mode: .steal), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 0)
    }

    // MARK: - Notify dispatch & pending stacking

    func testAskAudioStartedNotifiesAndSetsPending() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, notifier, _) = makeCoordinator(config: makeConfig(mode: .ask), bluetooth: bt)
        monitor.emit(.audioStarted(.media))
        XCTAssertEqual(notifier.promptCount, 1)
        XCTAssertTrue(coordinator.notificationPending)
    }

    func testAskSecondAudioStartedDoesNotStackNotifications() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, notifier, _) = makeCoordinator(config: makeConfig(mode: .ask), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.audioStarted(.media))
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(notifier.promptCount, 1)
    }

    func testSuccessfulConnectClearsPending() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .ask), bluetooth: bt)
        monitor.emit(.audioStarted(.media))
        XCTAssertTrue(coordinator.notificationPending)
        coordinator.handle(.userAcceptedSwitch)
        XCTAssertEqual(bt.connectCount, 1)
        XCTAssertFalse(coordinator.notificationPending)
    }

    func testNotifyAgainAfterPendingCleared() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, notifier, _) = makeCoordinator(config: makeConfig(mode: .ask), bluetooth: bt)
        monitor.emit(.audioStarted(.media))
        coordinator.handle(.userAcceptedSwitch)
        monitor.emit(.audioStarted(.media))
        XCTAssertEqual(notifier.promptCount, 2)
    }

    // MARK: - UserAcceptedSwitch

    func testUserAcceptedNotPairedDoesNotConnect() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .ask), bluetooth: bt)
        monitor.emit(.audioStarted(.media))
        XCTAssertTrue(coordinator.notificationPending)
        bt.paired = false
        coordinator.handle(.userAcceptedSwitch)
        XCTAssertEqual(bt.connectCount, 0)
        XCTAssertTrue(coordinator.notificationPending)
    }

    func testUserAcceptedAlreadyActiveDoesNothing() {
        let bt = FakeBluetooth()
        bt.active = true
        let coordinator = Coordinator(
            monitor: FakeMonitor(),
            bluetooth: bt,
            notifier: FakeNotifier(),
            settings: FakeSettings(makeConfig(mode: .ask))
        )
        coordinator.handle(.userAcceptedSwitch)
        XCTAssertEqual(bt.connectCount, 0)
    }

    func testUserAcceptedPairedNotActiveConnectsAndClearsPending() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .ask), bluetooth: bt)
        monitor.emit(.audioStarted(.media))
        XCTAssertTrue(coordinator.notificationPending)
        coordinator.handle(.userAcceptedSwitch)
        XCTAssertEqual(bt.connectCount, 1)
        XCTAssertFalse(coordinator.notificationPending)
    }

    // MARK: - None dispatch

    func testDisabledEmitsNothing() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, notifier, _) = makeCoordinator(
            config: makeConfig(enabled: false, mode: .steal),
            bluetooth: bt
        )
        withExtendedLifetime(coordinator) {
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 0)
        XCTAssertEqual(notifier.promptCount, 0)
    }

    func testNilTargetEmitsNothing() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, notifier, _) = makeCoordinator(
            config: makeConfig(mode: .steal, target: nil),
            bluetooth: bt
        )
        withExtendedLifetime(coordinator) {
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 0)
        XCTAssertEqual(notifier.promptCount, 0)
    }

    func testAudioStoppedEmitsNothing() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, notifier, _) = makeCoordinator(config: makeConfig(mode: .ask), bluetooth: bt)
        monitor.emit(.audioStarted(.media))
        let pendingBefore = coordinator.notificationPending
        monitor.emit(.audioStopped)
        XCTAssertEqual(bt.connectCount, 0)
        XCTAssertEqual(coordinator.notificationPending, pendingBefore)
    }

    // MARK: - yieldToOtherSource: don't grab back after another source takes the target

    func testYieldAfterTargetLostSuppressesSteal() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .steal, yield: true), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.targetConnectionChanged(true))
            monitor.emit(.targetConnectionChanged(false)) // taken by another source
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 0)
    }

    func testYieldAfterTargetReturnsResumesSteal() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .steal, yield: true), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.targetConnectionChanged(true))
            monitor.emit(.targetConnectionChanged(false))
            monitor.emit(.targetConnectionChanged(true)) // freed back to us
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 1)
    }

    func testYieldDisconnectWithoutPriorConnectDoesNotYield() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .steal, yield: true), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.targetConnectionChanged(false)) // merely idle, not taken from us
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 1)
    }

    func testYieldOffStillStealsAfterTargetLost() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .steal, yield: false), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.targetConnectionChanged(true))
            monitor.emit(.targetConnectionChanged(false))
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 1)
    }

    func testYieldSurvivesStealInducedPause() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .steal, yield: true), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.targetConnectionChanged(true))
            monitor.emit(.targetConnectionChanged(false)) // stolen
            monitor.emit(.audioStopped)                   // auto-pause from the steal — NOT a fresh session
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 0)
    }

    func testYieldClearedByStopWhileHolding() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .steal, yield: true), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.targetConnectionChanged(true)) // we hold it
            monitor.emit(.audioStopped)                  // genuine session end
            monitor.emit(.audioStarted(.media))
        }
        XCTAssertEqual(bt.connectCount, 1)
    }

    func testYieldUserAcceptOverridesGuard() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, _, _) = makeCoordinator(config: makeConfig(mode: .steal, yield: true), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.targetConnectionChanged(true))
            monitor.emit(.targetConnectionChanged(false))
            coordinator.handle(.userAcceptedSwitch)
            XCTAssertEqual(bt.connectCount, 1)
            // Yield cleared, so a later AudioStarted also connects.
            monitor.emit(.audioStarted(.media))
            XCTAssertEqual(bt.connectCount, 2)
        }
    }

    func testTargetConnectionChangedDoesNotConnect() {
        let bt = FakeBluetooth()
        let (coordinator, monitor, notifier, _) = makeCoordinator(config: makeConfig(mode: .steal, yield: true), bluetooth: bt)
        withExtendedLifetime(coordinator) {
            monitor.emit(.targetConnectionChanged(true))
            monitor.emit(.targetConnectionChanged(false))
        }
        XCTAssertEqual(bt.connectCount, 0)
        XCTAssertEqual(notifier.promptCount, 0)
    }

    // MARK: - coordination: protect the active device via peer presence

    func testCoordinationPeerActiveSuppressesSteal() {
        let bt = FakeBluetooth()
        let presence = FakePresence()
        presence.peerActive = true
        let coordinator = Coordinator(
            monitor: FakeMonitor(), bluetooth: bt, notifier: FakeNotifier(),
            settings: FakeSettings(makeConfig(mode: .steal, yield: true)), presence: presence
        )
        coordinator.handle(.audioStarted(.media))
        XCTAssertEqual(bt.connectCount, 0)
    }

    func testCoordinationPeerActiveIgnoredWhenToggleOff() {
        let bt = FakeBluetooth()
        let presence = FakePresence()
        presence.peerActive = true
        let coordinator = Coordinator(
            monitor: FakeMonitor(), bluetooth: bt, notifier: FakeNotifier(),
            settings: FakeSettings(makeConfig(mode: .steal, yield: false)), presence: presence
        )
        coordinator.handle(.audioStarted(.media))
        XCTAssertEqual(bt.connectCount, 1)
    }

    func testCoordinationBroadcastsLocalActiveOnlyWhenHoldingAndPlaying() {
        let bt = FakeBluetooth()
        let presence = FakePresence()
        let coordinator = Coordinator(
            monitor: FakeMonitor(), bluetooth: bt, notifier: FakeNotifier(),
            settings: FakeSettings(makeConfig(mode: .steal, yield: true)), presence: presence
        )
        withExtendedLifetime(coordinator) {
            coordinator.handle(.audioStarted(.media))            // playing but not holding
            XCTAssertEqual(presence.localActive, false)
            coordinator.handle(.targetConnectionChanged(true))   // now holding
            XCTAssertEqual(presence.localActive, true)
            coordinator.handle(.audioStopped)                    // stopped
            XCTAssertEqual(presence.localActive, false)
        }
    }

    // MARK: - coordination: reclaim after the peer goes idle (regression: was permanently stuck)

    /// A peer takes the target (targetYielded=true), then reports it's no longer active (via a
    /// presence packet, e.g. a debounced RELEASE). We were already playing, so the coordinator
    /// should reclaim immediately instead of staying yielded forever just because the earbuds
    /// never reconnected to us at the OS/Bluetooth level.
    func testPeerGoingInactiveClearsStaleYieldAndReclaimsIfPlaying() async {
        let bt = FakeBluetooth()
        let presence = FakePresence()
        presence.peerActive = true
        let coordinator = Coordinator(
            monitor: FakeMonitor(), bluetooth: bt, notifier: FakeNotifier(),
            settings: FakeSettings(makeConfig(mode: .steal, yield: true)), presence: presence
        )
        withExtendedLifetime(coordinator) {
            coordinator.handle(.targetConnectionChanged(true))
            coordinator.handle(.targetConnectionChanged(false)) // taken by the peer
            coordinator.handle(.audioStarted(.media))            // suppressed: peer is active
            XCTAssertEqual(bt.connectCount, 0)

            presence.peerActive = false
            presence.onPeerChanged?()                            // peer confirmed it stopped
        }
        // Coordinator's real onPeerChanged wiring hops via Task { @MainActor in ... }; yield so
        // that enqueued job runs before we assert.
        await Task.yield()

        XCTAssertEqual(bt.connectCount, 1)
    }

    /// Same as above, but we weren't already playing when the peer went idle — the yield flag
    /// must still be cleared so the *next* audioStarted succeeds immediately rather than staying
    /// stuck on the stale Bluetooth-based guard.
    func testPeerGoingInactiveClearsStaleYieldForALaterPlay() async {
        let bt = FakeBluetooth()
        let presence = FakePresence()
        presence.peerActive = true
        let coordinator = Coordinator(
            monitor: FakeMonitor(), bluetooth: bt, notifier: FakeNotifier(),
            settings: FakeSettings(makeConfig(mode: .steal, yield: true)), presence: presence
        )
        withExtendedLifetime(coordinator) {
            coordinator.handle(.targetConnectionChanged(true))
            coordinator.handle(.targetConnectionChanged(false)) // taken by the peer

            presence.peerActive = false
            presence.onPeerChanged?()                            // peer confirmed it stopped; not playing yet
        }
        await Task.yield()

        withExtendedLifetime(coordinator) {
            coordinator.handle(.audioStarted(.media))            // later play attempt
        }
        XCTAssertEqual(bt.connectCount, 1)
    }
}
