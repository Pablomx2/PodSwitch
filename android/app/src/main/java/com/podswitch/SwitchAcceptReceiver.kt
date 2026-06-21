package com.podswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Forwards the ASK notification's "Connect" tap to [SwitchService] as [SwitchService.ACTION_ACCEPT]. */
class SwitchAcceptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACCEPT) return

        val serviceIntent = Intent(context, SwitchService::class.java).apply {
            action = SwitchService.ACTION_ACCEPT
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        const val ACTION_ACCEPT = "com.podswitch.action.ACCEPT_FROM_NOTIFICATION"
    }
}
