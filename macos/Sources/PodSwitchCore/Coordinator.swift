import Foundation
import os

/// Wires the audio monitor to the decision engine and executes the resulting action.
@MainActor
public final class Coordinator: AudioMonitorDelegate {

    private let monitor: any AudioMonitoring
    private let bluetooth: any BluetoothConnecting
    private let notifier: any Notifying
    private let settings: any SettingsStore
    private let presence: (any PresencePort)?
    private let logger = Logger(subsystem: "com.podswitch.core", category: "Presence")

    /// True while an ASK prompt is on screen and awaiting the user.
    private(set) var notificationPending = false

    /// Last observed connection state of the target to THIS machine.
    private var targetConnected = false

    /// Target was taken by another source (lost while we held it) and not yet returned.
    private var targetYielded = false

    /// Whether local audio is currently playing, and of what category (for coordination claims).
    private var localPlaying = false
    private var localCategory: Category?

    public init(
        monitor: any AudioMonitoring,
        bluetooth: any BluetoothConnecting,
        notifier: any Notifying,
        settings: any SettingsStore,
        presence: (any PresencePort)? = nil
    ) {
        self.monitor = monitor
        self.bluetooth = bluetooth
        self.notifier = notifier
        self.settings = settings
        self.presence = presence
        self.monitor.delegate = self
        // When a peer releases the target, re-evaluate so we can take over if we're still playing.
        // The callback fires off the networking thread, so hop back to the main actor.
        self.presence?.onPeerChanged = { [weak self] in
            Task { @MainActor in self?.onPeerChanged() }
        }
    }

    /// Begin monitoring audio.
    public func start() {
        monitor.start()
    }

    /// Stop monitoring audio.
    public func stop() {
        monitor.stop()
    }

    /// Funnel an external event through the same pipeline as monitor-emitted events.
    public func handle(_ event: SwitchEvent) {
        // A connection-state change only updates yield tracking; it never triggers an action.
        if case .targetConnectionChanged(let connected) = event {
            if connected {
                // The target came back to us on its own -> it's free again.
                targetYielded = false
            } else if targetConnected {
                // We had it, then lost it to something else -> yield until it returns.
                targetYielded = true
            }
            targetConnected = connected
            updatePresenceLocalActive()
            return
        }

        // Track the local playing level that drives our outgoing presence claims.
        switch event {
        case .audioStarted(let category):
            localPlaying = true
            localCategory = category
        case .audioStopped:
            localPlaying = false
            localCategory = nil
        default:
            break
        }

        let config = settings.config
        let status = currentStatus(for: config)
        let action = SwitchEngine.decide(event: event, config: config, status: status)
        if config.yieldToOtherSource && status.peerActiveOnTarget && action == .none {
            logger.info("suppressed steal (peer active) for event=\(String(describing: event), privacy: .public)")
        } else {
            logger.debug("decision for event=\(String(describing: event), privacy: .public): \(String(describing: action), privacy: .public)")
        }
        execute(action, for: event, config: config)

        if case .audioStopped = event, targetConnected {
            // Only a stop while we still HOLD the device ends the session and clears the yield;
            // a stop while we don't hold it is the auto-pause from another source stealing it.
            targetYielded = false
        }

        updatePresenceLocalActive()
    }

    /// Broadcast a claim only while we both hold the target AND are playing on it.
    private func updatePresenceLocalActive() {
        presence?.setLocalActive(localPlaying && targetConnected)
    }

    /// A peer's active state changed (typically it released the target). Take over if we can.
    private func onPeerChanged() {
        if localPlaying {
            handle(.audioStarted(localCategory ?? .media))
        }
    }

    // MARK: - AudioMonitorDelegate

    public func audioMonitor(_ monitor: any AudioMonitoring, didEmit event: SwitchEvent) {
        handle(event)
    }

    // MARK: - Private

    private func currentStatus(for config: Config) -> DeviceStatus {
        guard let target = config.targetDeviceId else {
            return DeviceStatus(
                targetPaired: false,
                targetActiveOutput: false,
                notificationPending: notificationPending
            )
        }
        let active = bluetooth.isActiveOutput(deviceId: target)
        // If the target is currently our output it clearly isn't held elsewhere: clear any stale
        // yield and prime the link state (covers a start with the device already connected).
        if active {
            targetYielded = false
            targetConnected = true
        }
        return DeviceStatus(
            targetPaired: bluetooth.isPaired(deviceId: target),
            targetActiveOutput: active,
            notificationPending: notificationPending,
            targetYielded: targetYielded,
            peerActiveOnTarget: presence?.peerActiveOnTarget() ?? false
        )
    }

    private func execute(_ action: SwitchAction, for event: SwitchEvent, config: Config) {
        switch action {
        case .none:
            break

        case .connect:
            // An explicit accept overrides the yield guard; clear it so we don't re-suppress.
            if case .userAcceptedSwitch = event { targetYielded = false }
            performConnect(config: config)

        case .notify:
            notificationPending = true
            notifier.showSwitchPrompt()
        }
    }

    private func performConnect(config: Config) {
        guard let target = config.targetDeviceId else { return }
        notificationPending = false
        bluetooth.connect(deviceId: target)
    }
}
