import Foundation
import IOBluetooth

/// Polls the target device's Bluetooth link to THIS Mac and reports connect/disconnect edges.
///
/// macOS has no A2DP connection-state broadcast (the Android equivalent), so we poll
/// `IOBluetoothDevice.isConnected()` once a second and emit only on change. This is how PodSwitch
/// infers that another source has taken the headphones (in a single-link setup, that shows up here
/// as a disconnect), feeding the "don't grab back" guard. The reads are cheap, non-blocking
/// property lookups, so polling on the main actor is fine — unlike `openConnection()`, which is why
/// `BluetoothConnector` keeps *that* off the main thread.
@MainActor
public final class TargetConnectionMonitor {

    private var started = false
    private var lastConnected: Bool?

    private let targetAddress: () -> String?
    private let onChange: (Bool) -> Void

    /// - Parameters:
    ///   - targetAddress: the currently-configured target BT address (re-read each poll).
    ///   - onChange: called on the main actor when the link state flips (true = connected to us).
    public init(targetAddress: @escaping () -> String?, onChange: @escaping (Bool) -> Void) {
        self.targetAddress = targetAddress
        self.onChange = onChange
    }

    /// Begin polling. Safe to call once.
    public func start() {
        guard !started else { return }
        started = true
        scheduleNext()
    }

    /// Stop polling and forget the last state.
    public func stop() {
        started = false
        lastConnected = nil
    }

    private func scheduleNext() {
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.pollInterval) { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.started else { return }
                self.poll()
                self.scheduleNext()
            }
        }
    }

    private func poll() {
        guard let address = targetAddress() else {
            // No target configured — treat as a clean slate so a later configure starts fresh.
            lastConnected = nil
            return
        }
        let connected = Self.isConnected(address: address)
        if connected != lastConnected {
            lastConnected = connected
            onChange(connected)
        }
    }

    private static func isConnected(address: String) -> Bool {
        let target = normalize(address)
        guard let devices = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else { return false }
        guard let device = devices.first(where: { normalize($0.addressString ?? "") == target }) else {
            return false
        }
        return device.isConnected()
    }

    private static func normalize(_ address: String) -> String {
        address
            .lowercased()
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: "-", with: "")
    }

    private static let pollInterval: TimeInterval = 1.0
}
