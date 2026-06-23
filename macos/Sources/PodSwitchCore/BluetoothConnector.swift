import Foundation
import CoreAudio
import IOBluetooth
import os

/// IOBluetooth-backed connector. Device IDs are Bluetooth addresses.
public struct BluetoothConnector: BluetoothConnecting {

    private static let maxAttempts = 2
    private static let attemptTimeout: TimeInterval = 4.0
    private static let pollInterval: TimeInterval = 0.4

    /// All IOBluetooth work runs here. `IOBluetoothDevice.openConnection()` is synchronous and
    /// blocks the calling thread until the baseband link is up (or times out), so it must never
    /// run on the main thread — otherwise the menu-bar app stalls for seconds during a connect.
    private static let queue = DispatchQueue(label: "com.podswitch.core.bluetooth")

    private static let log = Logger(subsystem: "com.podswitch.core", category: "BluetoothConnector")

    public init() {}

    public func isPaired(deviceId: String) -> Bool {
        pairedDevice(matching: deviceId) != nil
    }

    /// Fire-and-forget connect: hops to a background queue, verifies asynchronously, retries
    /// once, then gives up silently. Returns immediately so the caller (main actor) never blocks.
    public func connect(deviceId: String) {
        Self.queue.async {
            guard let device = pairedDevice(matching: deviceId) else {
                Self.log.error("connect: device not found among paired devices")
                return
            }
            if device.isConnected() && isActiveOutput(deviceId: deviceId) {
                return
            }
            attemptConnect(deviceId: deviceId, attempt: 1)
        }
    }

    /// Initiate one connection attempt and start polling for success.
    private func attemptConnect(deviceId: String, attempt: Int) {
        guard let device = pairedDevice(matching: deviceId) else {
            Self.log.error("connect: device disappeared before attempt \(attempt)")
            return
        }
        if !device.isConnected() {
            _ = device.openConnection()
        }
        pollForSuccess(deviceId: deviceId, attempt: attempt, elapsed: 0)
    }

    /// Poll `isActiveOutput` until success, retry on per-attempt timeout, or give up when exhausted.
    private func pollForSuccess(deviceId: String, attempt: Int, elapsed: TimeInterval) {
        if isActiveOutput(deviceId: deviceId) {
            Self.log.debug("connect: verified active output on attempt \(attempt)")
            return
        }
        if elapsed >= Self.attemptTimeout {
            if attempt < Self.maxAttempts {
                Self.log.debug("connect: attempt \(attempt) timed out, retrying")
                attemptConnect(deviceId: deviceId, attempt: attempt + 1)
            } else {
                Self.log.error("connect: gave up after \(Self.maxAttempts) attempts")
            }
            return
        }
        let interval = Self.pollInterval
        Self.queue.asyncAfter(deadline: .now() + interval) {
            pollForSuccess(deviceId: deviceId, attempt: attempt, elapsed: elapsed + interval)
        }
    }

    public func isActiveOutput(deviceId: String) -> Bool {
        guard let device = pairedDevice(matching: deviceId) else { return false }
        guard let targetName = device.name, !targetName.isEmpty else { return false }
        guard let outputName = currentDefaultOutputName() else { return false }
        return outputName.localizedCaseInsensitiveContains(targetName)
            || targetName.localizedCaseInsensitiveContains(outputName)
    }

    // MARK: - IOBluetooth helpers

    private func pairedDevice(matching deviceId: String) -> IOBluetoothDevice? {
        let normalizedTarget = normalize(deviceId)
        guard let devices = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else {
            return nil
        }
        return devices.first { device in
            guard let address = device.addressString else { return false }
            return normalize(address) == normalizedTarget
        }
    }

    private func normalize(_ address: String) -> String {
        address
            .lowercased()
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: "-", with: "")
    }

    // MARK: - CoreAudio helpers

    private func currentDefaultOutputName() -> String? {
        var deviceID = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var deviceAddress = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &deviceAddress,
            0,
            nil,
            &size,
            &deviceID
        ) == noErr, deviceID != AudioObjectID(kAudioObjectUnknown) else {
            return nil
        }

        var nameAddress = AudioObjectPropertyAddress(
            mSelector: kAudioObjectPropertyName,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var nameSize = UInt32(MemoryLayout<CFString?>.size)
        var name: CFString? = nil
        let status = withUnsafeMutablePointer(to: &name) { pointer -> OSStatus in
            AudioObjectGetPropertyData(deviceID, &nameAddress, 0, nil, &nameSize, pointer)
        }
        guard status == noErr else { return nil }
        return name as String?
    }
}
