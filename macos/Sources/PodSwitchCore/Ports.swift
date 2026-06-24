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

/// LAN presence coordination: learn whether a peer is actively playing on the target, and announce
/// this machine's own active/playing state. Best-effort — with no reachable peer
/// `peerActiveOnTarget` is false and the engine falls back to the reactive yield guard.
///
/// Implementations manage their own networking thread, so the protocol is `Sendable` and the
/// callback is `@Sendable` (it hops back to the main actor itself).
public protocol PresencePort: AnyObject, Sendable {
    /// True if a peer PodSwitch device currently holds + plays the target.
    func peerActiveOnTarget() -> Bool
    /// Announce whether THIS machine is currently holding the target as active output AND playing.
    func setLocalActive(_ active: Bool)
    /// Invoked when a peer's active state changes (e.g. it released the target).
    var onPeerChanged: (@Sendable () -> Void)? { get set }
}
