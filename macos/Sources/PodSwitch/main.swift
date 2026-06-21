import AppKit
import PodSwitchCore

// PodSwitch runs as a menu-bar-only agent (LSUIElement); UI lives in AppDelegate.

let app = NSApplication.shared
app.setActivationPolicy(.accessory)

let delegate = AppDelegate()
app.delegate = delegate

app.run()
