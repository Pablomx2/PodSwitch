package com.podswitch.platform

import android.content.Context
import android.media.AudioManager

/** Detects whether the device is currently in a voice/VoIP call via [AudioManager.getMode]. */
class CallMonitor(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** True when a cellular call or a VoIP communication session is active. */
    fun isInCall(): Boolean = when (audioManager.mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION -> true
        else -> false
    }
}
