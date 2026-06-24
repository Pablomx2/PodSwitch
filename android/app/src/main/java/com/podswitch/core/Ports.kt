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

/**
 * LAN presence coordination: lets this device learn whether a peer is actively playing on the
 * target, and announce its own active/playing state. Implementations are best-effort — when no
 * peer is reachable [peerActiveOnTarget] simply returns false and the engine falls back to the
 * reactive yield guard.
 */
interface PresencePort {
    /** True if a peer PodSwitch device currently holds + plays the target. */
    fun peerActiveOnTarget(): Boolean

    /** Announce whether THIS device is currently holding the target as active output AND playing. */
    fun setLocalActive(active: Boolean)

    /** Invoked when a peer's active state changes (e.g. it released the target). */
    var onPeerChanged: (() -> Unit)?
}
