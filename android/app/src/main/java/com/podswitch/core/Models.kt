package com.podswitch.core

/** Behaviour when triggering audio appears. */
enum class Mode { STEAL, ASK }

/** Audio category that can trigger a switch. */
enum class Category { MEDIA, CALL, NOTIFICATION }

/** User configuration; targetDeviceId is the paired Bluetooth address, null if unconfigured. */
data class Config(
    val enabled: Boolean,
    val mode: Mode,
    val enabledCategories: Set<Category>,
    val targetDeviceId: String?,
)

/** Status sampled from the platform at the moment a decision is made. */
data class DeviceStatus(
    val targetPaired: Boolean,
    val targetActiveOutput: Boolean,
    val notificationPending: Boolean,
)

/** Events fed into the decision engine. */
sealed interface SwitchEvent {
    data class AudioStarted(val category: Category) : SwitchEvent
    data object UserAcceptedSwitch : SwitchEvent
    data object AudioStopped : SwitchEvent
}

/** Actions emitted by the decision engine. */
enum class SwitchAction { None, Connect, Notify }
