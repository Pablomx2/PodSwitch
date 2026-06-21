package com.podswitch.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.podswitch.core.AudioSource
import com.podswitch.core.Category
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

    /** The category currently considered "playing", or null when idle. */
    private var currentCategory: Category? = null

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
        listener = null
        currentCategory = null
    }

    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        val callCategory = if (callMonitor.isInCall()) Category.CALL else null
        val playbackCategory = configs
            .mapNotNull { classify(it.audioAttributes) }
            .minByOrNull { it.priority }
            ?.category

        val next = callCategory ?: playbackCategory
        emitTransition(next)
    }

    /** Ordered classification so the highest-priority concurrent stream wins. */
    private data class Classified(val category: Category, val priority: Int)

    private fun emitTransition(next: Category?) {
        val previous = currentCategory
        if (next == previous) return

        currentCategory = next
        if (next == null) {
            listener?.invoke(SwitchEvent.AudioStopped)
        } else {
            listener?.invoke(SwitchEvent.AudioStarted(next))
        }
    }

    private fun classify(attrs: AudioAttributes): Classified? = when (attrs.usage) {
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
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

    private companion object {
        // Lower number = higher priority.
        const val CALL_PRIORITY = 0
        const val MEDIA_PRIORITY = 1
        const val NOTIFICATION_PRIORITY = 2
    }
}
