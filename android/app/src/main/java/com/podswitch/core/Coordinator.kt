package com.podswitch.core

/** Wires events through [SwitchEngine.decide] to platform actions. */
class Coordinator(
    private val settings: SettingsStore,
    private val connector: BluetoothConnector,
    private val notifier: NotificationPresenter,
    private val presence: PresencePort? = null,
    /** Testable logging seam; no-op by default so plain-JVM unit tests need no Android framework. */
    private val debugLog: (String) -> Unit = {},
) {
    private var notificationPending: Boolean = false

    /** Last observed A2DP link state of the target to this phone. */
    private var targetConnected: Boolean = false

    /** Target was taken by another source (connected -> disconnected, not by us) and not yet freed. */
    private var targetYielded: Boolean = false

    /** Whether local audio is currently playing, and of what category (for coordination claims). */
    private var localPlaying: Boolean = false
    private var localCategory: Category? = null

    init {
        // When a peer releases the target, re-evaluate so we can take over if we're still playing.
        presence?.onPeerChanged = { onPeerChanged() }
    }

    /** Feed one event through the engine and perform the resulting action. */
    fun handle(event: SwitchEvent) {
        // A connection-state change only updates yield tracking; it never triggers an action.
        if (event is SwitchEvent.TargetConnectionChanged) {
            if (event.connected) {
                // The target came back to us on its own -> it's free again.
                targetYielded = false
            } else if (targetConnected) {
                // We had it, then lost it to something else -> yield until it returns.
                targetYielded = true
            }
            targetConnected = event.connected
            updatePresenceLocalActive()
            return
        }

        // Track the local playing level that drives our outgoing presence claims.
        when (event) {
            is SwitchEvent.AudioStarted -> {
                localPlaying = true
                localCategory = event.category
            }
            SwitchEvent.AudioStopped -> {
                localPlaying = false
                localCategory = null
            }
            else -> Unit
        }

        val config = settings.currentConfig()

        val target = config.targetDeviceId
        val paired = target != null && connector.isPaired(target)
        val active = target != null && connector.isActiveOutput(target)

        // If the target is currently our output it clearly isn't held elsewhere: clear any stale
        // yield and prime the link state (covers a service start with the device already connected).
        if (active) {
            targetYielded = false
            targetConnected = true
        }

        val status = DeviceStatus(
            targetPaired = paired,
            targetActiveOutput = active,
            notificationPending = notificationPending,
            targetYielded = targetYielded,
            peerActiveOnTarget = presence?.peerActiveOnTarget() ?: false,
        )

        val action = SwitchEngine.decide(event, config, status)
        if (config.yieldToOtherSource && status.peerActiveOnTarget && action == SwitchAction.None) {
            debugLog("suppressed steal (peer active) for event=$event")
        } else {
            debugLog("decision for event=$event: $action")
        }

        when (action) {
            SwitchAction.None -> Unit

            SwitchAction.Notify -> {
                notificationPending = true
                notifier.showAsk()
            }

            SwitchAction.Connect -> {
                if (notificationPending) {
                    notificationPending = false
                    notifier.clearAsk()
                }
                // An explicit accept overrides the yield guard; clear it so we don't re-suppress.
                if (event is SwitchEvent.UserAcceptedSwitch) targetYielded = false
                if (target != null) connector.connect(target)
            }
        }

        if (event is SwitchEvent.AudioStopped) {
            // Only a stop while we still HOLD the device is a genuine "session ended" — clear the
            // yield so the next play is unbiased. A stop while we DON'T hold it is almost always
            // the auto-pause caused by another source stealing the device (ACTION_AUDIO_BECOMING_
            // NOISY); clearing the yield there would let us wrongly grab it back.
            if (targetConnected) targetYielded = false
            if (notificationPending) {
                notificationPending = false
                notifier.clearAsk()
            }
        }

        updatePresenceLocalActive()
    }

    /** Broadcast a claim only while we both hold the target AND are playing on it. */
    private fun updatePresenceLocalActive() {
        presence?.setLocalActive(localPlaying && targetConnected)
    }

    /** A peer's active state changed (typically it released the target). Take over if we can. */
    private fun onPeerChanged() {
        if (localPlaying) {
            handle(SwitchEvent.AudioStarted(localCategory ?: Category.MEDIA))
        }
    }
}
