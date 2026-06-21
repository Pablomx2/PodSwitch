import Foundation

/// Wires the audio monitor to the decision engine and executes the resulting action.
@MainActor
public final class Coordinator: AudioMonitorDelegate {

    private let monitor: any AudioMonitoring
    private let bluetooth: any BluetoothConnecting
    private let notifier: any Notifying
    private let settings: any SettingsStore

    /// True while an ASK prompt is on screen and awaiting the user.
    private(set) var notificationPending = false

    public init(
        monitor: any AudioMonitoring,
        bluetooth: any BluetoothConnecting,
        notifier: any Notifying,
        settings: any SettingsStore
    ) {
        self.monitor = monitor
        self.bluetooth = bluetooth
        self.notifier = notifier
        self.settings = settings
        self.monitor.delegate = self
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
        let config = settings.config
        let status = currentStatus(for: config)
        let action = SwitchEngine.decide(event: event, config: config, status: status)
        execute(action, for: event, config: config)
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
        return DeviceStatus(
            targetPaired: bluetooth.isPaired(deviceId: target),
            targetActiveOutput: bluetooth.isActiveOutput(deviceId: target),
            notificationPending: notificationPending
        )
    }

    private func execute(_ action: SwitchAction, for event: SwitchEvent, config: Config) {
        switch action {
        case .none:
            break

        case .connect:
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
