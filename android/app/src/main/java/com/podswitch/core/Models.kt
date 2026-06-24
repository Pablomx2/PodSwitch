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
    /**
     * When true, PodSwitch will not grab the target back after another source has taken it
     * (i.e. while [DeviceStatus.targetYielded] is set). It resumes normal switching only once the
     * target reconnects to this phone on its own, or media playback fully stops.
     */
    val yieldToOtherSource: Boolean = false,
)

/** Status sampled from the platform at the moment a decision is made. */
data class DeviceStatus(
    val targetPaired: Boolean,
    val targetActiveOutput: Boolean,
    val notificationPending: Boolean,
    /**
     * The target was disconnected from this phone by something other than us (another source took
     * it) and has not reconnected since. Combined with [Config.yieldToOtherSource] to avoid yanking
     * it away from whatever is now using it. Android cannot observe what a remote host streams
     * through a shared sink, so this is inferred from the target's own A2DP link to this phone, not
     * from the other source's playback.
     */
    val targetYielded: Boolean = false,
    /**
     * Another PodSwitch device reported (over the LAN) that it is actively playing on this same
     * target right now. This is the authoritative "protect the active device" signal; unlike
     * [targetYielded] it is proactive (we learn it before stealing). Only the device that holds the
     * target as active output broadcasts it, so at most one peer is ever active at a time.
     */
    val peerActiveOnTarget: Boolean = false,
)

/** Events fed into the decision engine. */
sealed interface SwitchEvent {
    data class AudioStarted(val category: Category) : SwitchEvent
    data object UserAcceptedSwitch : SwitchEvent
    data object AudioStopped : SwitchEvent

    /** The target's A2DP connection to this phone changed (true = connected to us). */
    data class TargetConnectionChanged(val connected: Boolean) : SwitchEvent
}

/** Actions emitted by the decision engine. */
enum class SwitchAction { None, Connect, Notify }
