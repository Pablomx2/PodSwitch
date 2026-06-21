package com.podswitch.core

/** Platform boundary interfaces the engine and [Coordinator] depend on. */

/** A source of audio start/stop events. */
interface AudioSource {
    /** Begin emitting events to [onEvent]. */
    fun start(onEvent: (SwitchEvent) -> Unit)

    /** Stop emitting and release platform resources. */
    fun stop()
}

/** Connects to / inspects the target Bluetooth audio device. */
interface BluetoothConnector {
    /** True if the target device is currently bonded/paired. */
    fun isPaired(targetDeviceId: String): Boolean

    /** True if the target device is the active connected audio output. */
    fun isActiveOutput(targetDeviceId: String): Boolean

    /** Make the target the active output. Fire-and-forget with internal verify/retry. */
    fun connect(targetDeviceId: String)
}

/** Presents notifications: the ASK prompt. */
interface NotificationPresenter {
    /** Show the "Connect?" ASK prompt. */
    fun showAsk()

    /** Dismiss the ASK prompt (e.g. after acceptance or audio stop). */
    fun clearAsk()
}

/** Read access to persisted configuration. */
interface SettingsStore {
    /** Snapshot the current configuration. */
    fun currentConfig(): Config
}
