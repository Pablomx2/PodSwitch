import Foundation

/// Receives `SwitchEvent`s emitted by an `AudioMonitoring` implementation.
@MainActor
public protocol AudioMonitorDelegate: AnyObject {
    func audioMonitor(_ monitor: any AudioMonitoring, didEmit event: SwitchEvent)
}

/// Observes audio playback and emits debounced `SwitchEvent`s on the main actor.
@MainActor
public protocol AudioMonitoring: AnyObject {
    var delegate: AudioMonitorDelegate? { get set }
    func start()
    func stop()
}

/// Bluetooth connection to the target device.
public protocol BluetoothConnecting: Sendable {
    /// The target device is the active system audio output right now.
    func isActiveOutput(deviceId: String) -> Bool
    /// The target device is currently paired with this machine.
    func isPaired(deviceId: String) -> Bool
    /// Open an audio connection to the target device (fire-and-forget).
    func connect(deviceId: String)
}

/// User-facing notifications.
@MainActor
public protocol Notifying: AnyObject {
    /// Show the "move audio to the target device?" prompt with a Connect action.
    func showSwitchPrompt()
}

/// Persisted user configuration.
@MainActor
public protocol SettingsStore: AnyObject {
    var config: Config { get set }
}
