import Foundation

/// `UserDefaults`-backed implementation of `SettingsStore`.
@MainActor
public final class Settings: SettingsStore {

    private enum Key {
        static let enabled = "podswitch.enabled"
        static let mode = "podswitch.mode"
        static let address = "podswitch.target.address"
        static let name = "podswitch.target.name"
        static let yield = "podswitch.yield"
        static let deviceId = "podswitch.deviceId"
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: Key.enabled) == nil {
            defaults.set(true, forKey: Key.enabled)
        }
        if defaults.object(forKey: Key.yield) == nil {
            defaults.set(true, forKey: Key.yield)
        }
    }

    /// Display name of the configured target device, if any.
    public var targetDeviceName: String? {
        get { defaults.string(forKey: Key.name) }
        set { defaults.set(newValue, forKey: Key.name) }
    }

    public var config: Config {
        get {
            let enabled = defaults.object(forKey: Key.enabled) as? Bool ?? true
            let mode = (defaults.string(forKey: Key.mode)).flatMap(Mode.init(rawValue:)) ?? .ask
            let address = defaults.string(forKey: Key.address)
            return Config(
                enabled: enabled,
                mode: mode,
                enabledCategories: [.media],
                targetDeviceId: address,
                yieldToOtherSource: defaults.bool(forKey: Key.yield)
            )
        }
        set {
            defaults.set(newValue.enabled, forKey: Key.enabled)
            defaults.set(newValue.mode.rawValue, forKey: Key.mode)
            defaults.set(newValue.yieldToOtherSource, forKey: Key.yield)
            if let address = newValue.targetDeviceId {
                defaults.set(address, forKey: Key.address)
            } else {
                defaults.removeObject(forKey: Key.address)
                defaults.removeObject(forKey: Key.name)
            }
        }
    }

    /// Persist a chosen target device (address + display name) together.
    public func setTargetDevice(address: String, name: String) {
        defaults.set(address, forKey: Key.address)
        defaults.set(name, forKey: Key.name)
    }

    /// Stable per-install identifier used to distinguish this device in LAN coordination.
    public func deviceId() -> String {
        if let existing = defaults.string(forKey: Key.deviceId) { return existing }
        let id = UUID().uuidString
        defaults.set(id, forKey: Key.deviceId)
        return id
    }
}
