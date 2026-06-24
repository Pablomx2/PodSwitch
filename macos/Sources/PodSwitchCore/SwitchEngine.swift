import Foundation

/// The shared, pure, side-effect-free decision engine. Identical contract on every platform.
public enum SwitchEngine {

    /// Map an event + config + sampled status to the action to perform.
    public static func decide(
        event: SwitchEvent,
        config: Config,
        status: DeviceStatus
    ) -> SwitchAction {
        guard config.enabled else { return .none }
        guard config.targetDeviceId != nil else { return .none }

        switch event {
        case .audioStarted(let category):
            guard config.enabledCategories.contains(category) else { return .none }
            guard status.targetPaired else { return .none }
            guard !status.targetActiveOutput else { return .none }
            // Coordination layer (authoritative): a peer device is actively playing on the target.
            if config.yieldToOtherSource && status.peerActiveOnTarget { return .none }
            // Reactive fallback: we lost the target to another source and haven't got it back.
            if config.yieldToOtherSource && status.targetYielded { return .none }
            switch config.mode {
            case .steal:
                return .connect
            case .ask:
                return status.notificationPending ? .none : .notify
            }

        // Explicit user request overrides the yield guard.
        case .userAcceptedSwitch:
            guard status.targetPaired else { return .none }
            guard !status.targetActiveOutput else { return .none }
            return .connect

        case .audioStopped:
            return .none

        // Pure state input for the Coordinator; never an action on its own.
        case .targetConnectionChanged:
            return .none
        }
    }
}
