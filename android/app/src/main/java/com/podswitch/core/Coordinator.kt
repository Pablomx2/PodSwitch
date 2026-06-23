package com.podswitch.core

/** Wires events through [SwitchEngine.decide] to platform actions. */
class Coordinator(
    private val settings: SettingsStore,
    private val connector: BluetoothConnector,
    private val notifier: NotificationPresenter,
) {
    private var notificationPending: Boolean = false

    /** Last observed A2DP link state of the target to this phone. */
    private var targetConnected: Boolean = false

    /** Target was taken by another source (connected -> disconnected, not by us) and not yet freed. */
    private var targetYielded: Boolean = false

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
            return
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
        )

        when (SwitchEngine.decide(event, config, status)) {
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
            // A fresh playback session starts unbiased by a past yield.
            targetYielded = false
            if (notificationPending) {
                notificationPending = false
                notifier.clearAsk()
            }
        }
    }
}
