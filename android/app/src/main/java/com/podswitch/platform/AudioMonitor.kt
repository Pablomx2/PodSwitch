package com.podswitch.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.podswitch.core.AudioSource
import com.podswitch.core.Category
import com.podswitch.core.PlaybackEdge
import com.podswitch.core.SwitchEvent

/** Monitors active audio playback, classifying it into a [Category] and debouncing transitions. */
class AudioMonitor(
    context: Context,
    private val callMonitor: CallMonitor,
) : AudioSource {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: ((SwitchEvent) -> Unit)? = null

    /** Debounces raw category observations into start/stop events (filters transient blips). */
    private val edge = PlaybackEdge(
        sustainMillis = MEDIA_SUSTAIN_MS,
        scheduler = HandlerScheduler(mainHandler),
        onStarted = { category -> listener?.invoke(SwitchEvent.AudioStarted(category)) },
        onStopped = { listener?.invoke(SwitchEvent.AudioStopped) },
    )

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            evaluate(configs)
        }
    }

    override fun start(onEvent: (SwitchEvent) -> Unit) {
        listener = onEvent
        audioManager.registerAudioPlaybackCallback(callback, mainHandler)
        evaluate(audioManager.activePlaybackConfigurations)
    }

    override fun stop() {
        audioManager.unregisterAudioPlaybackCallback(callback)
        edge.reset()
        listener = null
    }

    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        val callCategory = if (callMonitor.isInCall()) Category.CALL else null
        val playbackCategory = configs
            .mapNotNull { classify(it.audioAttributes) }
            .minByOrNull { it.priority }
            ?.category

        val next = callCategory ?: playbackCategory
        edge.update(next)
    }

    /** Ordered classification so the highest-priority concurrent stream wins. */
    private data class Classified(val category: Category, val priority: Int)

    private fun classify(attrs: AudioAttributes): Classified? = when (attrs.usage) {
        // Note: USAGE_ASSISTANCE_SONIFICATION (UI/keyboard sounds) is deliberately NOT media —
        // it was causing texting in Google Messages to trigger a switch. USAGE_UNKNOWN stays
        // media but is gated by the sustain debounce so short blips don't count.
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN -> Classified(Category.MEDIA, MEDIA_PRIORITY)

        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> Classified(Category.CALL, CALL_PRIORITY)

        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
        AudioAttributes.USAGE_ALARM -> Classified(Category.NOTIFICATION, NOTIFICATION_PRIORITY)

        else -> null
    }

    /** [PlaybackEdge.Scheduler] backed by the main-thread [Handler]. */
    private class HandlerScheduler(private val handler: Handler) : PlaybackEdge.Scheduler {
        private var token: Runnable? = null

        override fun schedule(delayMillis: Long, action: () -> Unit) {
            cancel()
            val runnable = Runnable { token = null; action() }
            token = runnable
            handler.postDelayed(runnable, delayMillis)
        }

        override fun cancel() {
            token?.let { handler.removeCallbacks(it) }
            token = null
        }
    }

    private companion object {
        // Lower number = higher priority.
        const val CALL_PRIORITY = 0
        const val MEDIA_PRIORITY = 1
        const val NOTIFICATION_PRIORITY = 2

        /** Media must persist this long before it counts as a real start (filters UI blips). */
        const val MEDIA_SUSTAIN_MS = 1000L
    }
}
