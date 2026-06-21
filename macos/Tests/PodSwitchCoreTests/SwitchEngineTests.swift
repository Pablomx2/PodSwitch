import XCTest
@testable import PodSwitchCore

// XCTest pulls in ObjC's `Category`, which collides with `PodSwitchCore.Category`.
private typealias Category = PodSwitchCore.Category

final class SwitchEngineTests: XCTestCase {

    // MARK: - Helpers

    private func config(
        enabled: Bool = true,
        mode: Mode = .steal,
        categories: Set<Category> = [.media],
        target: String? = "aa-bb-cc-dd-ee-ff"
    ) -> Config {
        Config(
            enabled: enabled,
            mode: mode,
            enabledCategories: categories,
            targetDeviceId: target
        )
    }

    private func status(
        paired: Bool = true,
        active: Bool = false,
        pending: Bool = false
    ) -> DeviceStatus {
        DeviceStatus(
            targetPaired: paired,
            targetActiveOutput: active,
            notificationPending: pending
        )
    }

    // MARK: - Global guards

    func testDisabledReturnsNoneForEveryEvent() {
        let cfg = config(enabled: false)
        let st = status()
        XCTAssertEqual(SwitchEngine.decide(event: .audioStarted(.media), config: cfg, status: st), .none)
        XCTAssertEqual(SwitchEngine.decide(event: .userAcceptedSwitch, config: cfg, status: st), .none)
        XCTAssertEqual(SwitchEngine.decide(event: .audioStopped, config: cfg, status: st), .none)
    }

    func testNilTargetReturnsNoneForEveryEvent() {
        let cfg = config(target: nil)
        let st = status()
        XCTAssertEqual(SwitchEngine.decide(event: .audioStarted(.media), config: cfg, status: st), .none)
        XCTAssertEqual(SwitchEngine.decide(event: .userAcceptedSwitch, config: cfg, status: st), .none)
        XCTAssertEqual(SwitchEngine.decide(event: .audioStopped, config: cfg, status: st), .none)
    }

    func testDisabledTakesPrecedenceOverNilTarget() {
        let cfg = config(enabled: false, target: nil)
        XCTAssertEqual(
            SwitchEngine.decide(event: .userAcceptedSwitch, config: cfg, status: status(paired: false)),
            .none
        )
    }

    // MARK: - AudioStarted x category

    func testAudioStartedCategoryNotEnabledReturnsNone() {
        let cfg = config(mode: .steal, categories: [.media])
        XCTAssertEqual(
            SwitchEngine.decide(event: .audioStarted(.call), config: cfg, status: status()),
            .none
        )
        XCTAssertEqual(
            SwitchEngine.decide(event: .audioStarted(.notification), config: cfg, status: status()),
            .none
        )
    }

    func testAudioStartedEnabledCategoryProceeds() {
        let cfg = config(mode: .steal, categories: [.media, .call])
        XCTAssertEqual(
            SwitchEngine.decide(event: .audioStarted(.call), config: cfg, status: status()),
            .connect
        )
    }

    // MARK: - AudioStarted x paired / active

    func testAudioStartedNotPairedReturnsNone() {
        for mode in Mode.allCases {
            let cfg = config(mode: mode)
            XCTAssertEqual(
                SwitchEngine.decide(event: .audioStarted(.media), config: cfg, status: status(paired: false)),
                .none,
                "mode \(mode)"
            )
        }
    }

    func testAudioStartedAlreadyActiveReturnsNone() {
        for mode in Mode.allCases {
            let cfg = config(mode: mode)
            XCTAssertEqual(
                SwitchEngine.decide(event: .audioStarted(.media), config: cfg, status: status(active: true)),
                .none,
                "mode \(mode)"
            )
        }
    }

    // MARK: - AudioStarted x mode

    func testAudioStartedStealConnects() {
        let cfg = config(mode: .steal)
        XCTAssertEqual(
            SwitchEngine.decide(event: .audioStarted(.media), config: cfg, status: status()),
            .connect
        )
    }

    func testAudioStartedStealConnectsEvenWithPendingNotification() {
        let cfg = config(mode: .steal)
        XCTAssertEqual(
            SwitchEngine.decide(event: .audioStarted(.media), config: cfg, status: status(pending: true)),
            .connect
        )
    }

    func testAudioStartedAskNoPendingNotifies() {
        let cfg = config(mode: .ask)
        XCTAssertEqual(
            SwitchEngine.decide(event: .audioStarted(.media), config: cfg, status: status(pending: false)),
            .notify
        )
    }

    func testAudioStartedAskWithPendingReturnsNone() {
        let cfg = config(mode: .ask)
        XCTAssertEqual(
            SwitchEngine.decide(event: .audioStarted(.media), config: cfg, status: status(pending: true)),
            .none
        )
    }

    // MARK: - UserAcceptedSwitch

    func testUserAcceptedNotPairedReturnsNone() {
        for mode in Mode.allCases {
            let cfg = config(mode: mode)
            XCTAssertEqual(
                SwitchEngine.decide(event: .userAcceptedSwitch, config: cfg, status: status(paired: false)),
                .none,
                "mode \(mode)"
            )
        }
    }

    func testUserAcceptedNotPairedEvenWhenActiveReturnsNone() {
        let cfg = config(mode: .ask)
        XCTAssertEqual(
            SwitchEngine.decide(
                event: .userAcceptedSwitch,
                config: cfg,
                status: status(paired: false, active: true)
            ),
            .none
        )
    }

    func testUserAcceptedAlreadyActiveReturnsNone() {
        let cfg = config(mode: .ask)
        XCTAssertEqual(
            SwitchEngine.decide(
                event: .userAcceptedSwitch,
                config: cfg,
                status: status(paired: true, active: true)
            ),
            .none
        )
    }

    func testUserAcceptedPairedNotActiveConnects() {
        let cfg = config(mode: .ask)
        XCTAssertEqual(
            SwitchEngine.decide(
                event: .userAcceptedSwitch,
                config: cfg,
                status: status(paired: true, active: false)
            ),
            .connect
        )
    }

    func testUserAcceptedIgnoresPendingFlag() {
        let cfg = config(mode: .ask)
        XCTAssertEqual(
            SwitchEngine.decide(
                event: .userAcceptedSwitch,
                config: cfg,
                status: status(paired: true, active: false, pending: true)
            ),
            .connect
        )
    }

    func testExhaustiveUserAcceptedTable() {
        for mode in Mode.allCases {
            for paired in [true, false] {
                for active in [true, false] {
                    for pending in [true, false] {
                        let cfg = config(mode: mode)
                        let st = status(paired: paired, active: active, pending: pending)
                        let result = SwitchEngine.decide(
                            event: .userAcceptedSwitch,
                            config: cfg,
                            status: st
                        )

                        let expected: SwitchAction
                        if !paired {
                            expected = .none
                        } else if active {
                            expected = .none
                        } else {
                            expected = .connect
                        }

                        XCTAssertEqual(
                            result,
                            expected,
                            "mode \(mode) paired \(paired) active \(active) pending \(pending)"
                        )
                    }
                }
            }
        }
    }

    // MARK: - AudioStopped

    func testAudioStoppedAlwaysNone() {
        for mode in Mode.allCases {
            for paired in [true, false] {
                for active in [true, false] {
                    for pending in [true, false] {
                        let cfg = config(mode: mode)
                        let st = status(paired: paired, active: active, pending: pending)
                        XCTAssertEqual(
                            SwitchEngine.decide(event: .audioStopped, config: cfg, status: st),
                            .none,
                            "mode \(mode) paired \(paired) active \(active) pending \(pending)"
                        )
                    }
                }
            }
        }
    }

    // MARK: - Exhaustive AudioStarted truth table

    func testExhaustiveAudioStartedTable() {
        for mode in Mode.allCases {
            for paired in [true, false] {
                for active in [true, false] {
                    for pending in [true, false] {
                        let cfg = config(mode: mode, categories: [.media])
                        let st = status(paired: paired, active: active, pending: pending)
                        let result = SwitchEngine.decide(
                            event: .audioStarted(.media),
                            config: cfg,
                            status: st
                        )

                        let expected: SwitchAction
                        if !paired {
                            expected = .none
                        } else if active {
                            expected = .none
                        } else {
                            switch mode {
                            case .steal:
                                expected = .connect
                            case .ask:
                                expected = pending ? .none : .notify
                            }
                        }

                        XCTAssertEqual(
                            result,
                            expected,
                            "mode \(mode) paired \(paired) active \(active) pending \(pending)"
                        )
                    }
                }
            }
        }
    }
}
