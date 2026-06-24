import AppKit
import IOBluetooth
import PodSwitchCore

/// Menu-bar controller: builds the status menu and drives the `Coordinator`.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {

    private var statusItem: NSStatusItem!
    private let settings = Settings()
    private let bluetooth = BluetoothConnector()
    private let notifier = NotificationManager()
    private let monitor = AudioMonitor()
    private var coordinator: Coordinator!
    private var connectionMonitor: TargetConnectionMonitor!
    private var presence: PresenceCoordinator!

    func applicationDidFinishLaunching(_ notification: Notification) {
        presence = PresenceCoordinator(deviceId: settings.deviceId())
        presence.updateTarget(settings.config.targetDeviceId)
        coordinator = Coordinator(
            monitor: monitor,
            bluetooth: bluetooth,
            notifier: notifier,
            settings: settings,
            presence: presence
        )
        notifier.onAccept = { [weak self] in
            self?.coordinator.handle(.userAcceptedSwitch)
        }

        connectionMonitor = TargetConnectionMonitor(
            targetAddress: { [weak self] in self?.settings.config.targetDeviceId },
            onChange: { [weak self] connected in
                self?.coordinator.handle(.targetConnectionChanged(connected))
            }
        )

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            button.image = Self.menuBarImage()
        }

        coordinator.start()
        connectionMonitor.start()
        presence.start()
        rebuildMenu()
    }

    /// The menu-bar template image: bundled brand glyph, with an SF Symbol fallback.
    private static func menuBarImage() -> NSImage? {
        if let url = Bundle.main.url(forResource: "MenuBarIcon", withExtension: "pdf"),
           let image = NSImage(contentsOf: url) {
            image.isTemplate = true
            image.size = NSSize(width: 18, height: 18)
            return image
        }
        let fallback = NSImage(systemSymbolName: "headphones", accessibilityDescription: "PodSwitch")
        fallback?.isTemplate = true
        return fallback
    }

    // MARK: - Menu construction

    private func rebuildMenu() {
        let config = settings.config
        let menu = NSMenu()

        let enabledItem = NSMenuItem(
            title: "Enabled",
            action: #selector(toggleEnabled),
            keyEquivalent: ""
        )
        enabledItem.target = self
        enabledItem.state = config.enabled ? .on : .off
        menu.addItem(enabledItem)

        menu.addItem(.separator())

        let modeHeader = NSMenuItem(title: "Mode", action: nil, keyEquivalent: "")
        modeHeader.isEnabled = false
        menu.addItem(modeHeader)

        let stealItem = NSMenuItem(
            title: "Steal (switch automatically)",
            action: #selector(selectSteal),
            keyEquivalent: ""
        )
        stealItem.target = self
        stealItem.state = config.mode == .steal ? .on : .off
        menu.addItem(stealItem)

        let askItem = NSMenuItem(
            title: "Ask (notify me first)",
            action: #selector(selectAsk),
            keyEquivalent: ""
        )
        askItem.target = self
        askItem.state = config.mode == .ask ? .on : .off
        menu.addItem(askItem)

        menu.addItem(.separator())

        let protectItem = NSMenuItem(
            title: "Protect the active device",
            action: #selector(toggleProtect),
            keyEquivalent: ""
        )
        protectItem.target = self
        protectItem.state = config.yieldToOtherSource ? .on : .off
        menu.addItem(protectItem)

        menu.addItem(.separator())

        let deviceHeader = NSMenuItem(title: "Device", action: nil, keyEquivalent: "")
        deviceHeader.isEnabled = false
        menu.addItem(deviceHeader)

        let currentName = settings.targetDeviceName
        let pairedSubmenu = NSMenu()
        let pairedDevices = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        if pairedDevices.isEmpty {
            let empty = NSMenuItem(title: "No paired devices", action: nil, keyEquivalent: "")
            empty.isEnabled = false
            pairedSubmenu.addItem(empty)
        } else {
            for device in pairedDevices {
                let name = device.name ?? device.addressString ?? "Unknown"
                let item = NSMenuItem(
                    title: name,
                    action: #selector(selectDevice(_:)),
                    keyEquivalent: ""
                )
                item.target = self
                item.representedObject = device
                if let address = device.addressString,
                   address.caseInsensitiveCompare(config.targetDeviceId ?? "") == .orderedSame {
                    item.state = .on
                }
                pairedSubmenu.addItem(item)
            }
        }

        let deviceItem = NSMenuItem(
            title: currentName.map { "Target: \($0)" } ?? "Choose device…",
            action: nil,
            keyEquivalent: ""
        )
        deviceItem.submenu = pairedSubmenu
        menu.addItem(deviceItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(
            title: "Quit PodSwitch",
            action: #selector(quit),
            keyEquivalent: "q"
        )
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
    }

    // MARK: - Actions

    @objc private func toggleEnabled() {
        var config = settings.config
        config.enabled.toggle()
        settings.config = config
        rebuildMenu()
    }

    @objc private func selectSteal() {
        var config = settings.config
        config.mode = .steal
        settings.config = config
        rebuildMenu()
    }

    @objc private func selectAsk() {
        var config = settings.config
        config.mode = .ask
        settings.config = config
        rebuildMenu()
    }

    @objc private func toggleProtect() {
        var config = settings.config
        config.yieldToOtherSource.toggle()
        settings.config = config
        rebuildMenu()
    }

    @objc private func selectDevice(_ sender: NSMenuItem) {
        guard let device = sender.representedObject as? IOBluetoothDevice,
              let address = device.addressString else { return }
        let name = device.name ?? address
        settings.setTargetDevice(address: address, name: name)
        presence.updateTarget(address)
        rebuildMenu()
    }

    @objc private func quit() {
        connectionMonitor.stop()
        presence.stop()
        coordinator.stop()
        NSApplication.shared.terminate(nil)
    }
}
