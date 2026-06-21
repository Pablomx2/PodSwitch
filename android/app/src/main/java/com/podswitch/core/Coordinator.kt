package com.podswitch.core

/** Wires events through [SwitchEngine.decide] to platform actions. */
class Coordinator(
    private val settings: SettingsStore,
    private val connector: BluetoothConnector,
    private val notifier: NotificationPresenter,
) {
    private var notificationPending: Boolean = false

    /** Feed one event through the engine and perform the resulting action. */
    fun handle(event: SwitchEvent) {
        val config = settings.currentConfig()

        val target = config.targetDeviceId
        val paired = target != null && connector.isPaired(target)
        val active = target != null && connector.isActiveOutput(target)

        val status = DeviceStatus(
            targetPaired = paired,
            targetActiveOutput = active,
            notificationPending = notificationPending,
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
                if (target != null) connector.connect(target)
            }
        }

        if (event is SwitchEvent.AudioStopped && notificationPending) {
            notificationPending = false
            notifier.clearAsk()
        }
    }
}
