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
            switch config.mode {
            case .steal:
                return .connect
            case .ask:
                return status.notificationPending ? .none : .notify
            }

        case .userAcceptedSwitch:
            guard status.targetPaired else { return .none }
            guard !status.targetActiveOutput else { return .none }
            return .connect

        case .audioStopped:
            return .none
        }
    }
}
