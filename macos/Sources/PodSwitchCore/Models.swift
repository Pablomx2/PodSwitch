import Foundation

/// How PodSwitch reacts when a relevant audio category starts playing.
public enum Mode: String, Sendable, Codable, CaseIterable {
    /// Immediately route audio to the target device.
    case steal = "STEAL"
    /// Show a notification and let the user decide.
    case ask = "ASK"
}

/// The kind of audio that triggered an event. macOS only uses `.media`.
public enum Category: String, Sendable, Codable, CaseIterable {
    case media = "MEDIA"
    case call = "CALL"
    case notification = "NOTIFICATION"
}

/// User configuration for the switching behaviour. Pure value type.
public struct Config: Sendable, Equatable {
    /// Master on/off switch.
    public var enabled: Bool
    /// STEAL or ASK.
    public var mode: Mode
    /// Categories that may trigger a switch. macOS implicitly `{.media}`.
    public var enabledCategories: Set<Category>
    /// Target Bluetooth device address; `nil` means unconfigured.
    public var targetDeviceId: String?

    public init(
        enabled: Bool,
        mode: Mode,
        enabledCategories: Set<Category>,
        targetDeviceId: String?
    ) {
        self.enabled = enabled
        self.mode = mode
        self.enabledCategories = enabledCategories
        self.targetDeviceId = targetDeviceId
    }
}

/// Live device/environment status sampled at decision time. Pure value type.
public struct DeviceStatus: Sendable, Equatable {
    /// The target device is paired with this machine.
    public var targetPaired: Bool
    /// The target device is already the active output on THIS device.
    public var targetActiveOutput: Bool
    /// An ASK notification is already on screen.
    public var notificationPending: Bool

    public init(
        targetPaired: Bool,
        targetActiveOutput: Bool,
        notificationPending: Bool
    ) {
        self.targetPaired = targetPaired
        self.targetActiveOutput = targetActiveOutput
        self.notificationPending = notificationPending
    }
}

/// Events that drive the decision engine.
public enum SwitchEvent: Sendable, Equatable {
    /// Audio of a given category began playing (post-debounce).
    case audioStarted(Category)
    /// The user tapped "Connect" on the ASK notification.
    case userAcceptedSwitch
    /// Audio stopped (monitor-side debounce only).
    case audioStopped
}

/// The action the platform layer should perform.
public enum SwitchAction: Sendable, Equatable {
    case none
    case connect
    case notify
}
