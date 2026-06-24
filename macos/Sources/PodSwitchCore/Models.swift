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
    /// When true, don't grab the target back after another source took it (see `DeviceStatus.targetYielded`).
    public var yieldToOtherSource: Bool

    public init(
        enabled: Bool,
        mode: Mode,
        enabledCategories: Set<Category>,
        targetDeviceId: String?,
        yieldToOtherSource: Bool = false
    ) {
        self.enabled = enabled
        self.mode = mode
        self.enabledCategories = enabledCategories
        self.targetDeviceId = targetDeviceId
        self.yieldToOtherSource = yieldToOtherSource
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
    /// The target was taken by another source (lost while we held it) and not yet returned.
    public var targetYielded: Bool
    /// A peer PodSwitch device reported (over the LAN) it is actively playing on this same target.
    public var peerActiveOnTarget: Bool

    public init(
        targetPaired: Bool,
        targetActiveOutput: Bool,
        notificationPending: Bool,
        targetYielded: Bool = false,
        peerActiveOnTarget: Bool = false
    ) {
        self.targetPaired = targetPaired
        self.targetActiveOutput = targetActiveOutput
        self.notificationPending = notificationPending
        self.targetYielded = targetYielded
        self.peerActiveOnTarget = peerActiveOnTarget
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
    /// The target's connection to THIS machine changed (true = connected to us).
    case targetConnectionChanged(Bool)
}

/// The action the platform layer should perform.
public enum SwitchAction: Sendable, Equatable {
    case none
    case connect
    case notify
}
