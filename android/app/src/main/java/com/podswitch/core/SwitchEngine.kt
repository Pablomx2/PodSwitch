package com.podswitch.core

/** Pure, side-effect-free decision engine mapping a [SwitchEvent] to a [SwitchAction]. */
object SwitchEngine {

    fun decide(
        event: SwitchEvent,
        config: Config,
        status: DeviceStatus,
    ): SwitchAction {
        if (!config.enabled) return SwitchAction.None
        if (config.targetDeviceId == null) return SwitchAction.None

        return when (event) {
            is SwitchEvent.AudioStarted -> {
                if (event.category !in config.enabledCategories) return SwitchAction.None
                if (!status.targetPaired) return SwitchAction.None
                if (status.targetActiveOutput) return SwitchAction.None
                when (config.mode) {
                    Mode.STEAL -> SwitchAction.Connect
                    Mode.ASK -> if (status.notificationPending) SwitchAction.None else SwitchAction.Notify
                }
            }

            SwitchEvent.UserAcceptedSwitch -> {
                if (!status.targetPaired) return SwitchAction.None
                if (status.targetActiveOutput) return SwitchAction.None
                SwitchAction.Connect
            }

            SwitchEvent.AudioStopped -> SwitchAction.None
        }
    }
}
