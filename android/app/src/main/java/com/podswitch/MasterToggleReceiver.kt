package com.podswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the ongoing notification's "Turn off" action button. */
class MasterToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISABLE) return
        PodSwitchControl.setEnabled(context, false)
    }

    companion object {
        const val ACTION_DISABLE = "com.podswitch.action.DISABLE_FROM_NOTIFICATION"
    }
}
