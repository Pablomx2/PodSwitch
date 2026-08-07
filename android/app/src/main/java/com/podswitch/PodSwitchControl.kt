package com.podswitch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.podswitch.platform.AndroidSettingsStore
import kotlinx.coroutines.runBlocking

/**
 * Enable/disable logic shared by every surface that can toggle PodSwitch outside the config
 * screen: the ongoing notification's action button, the home-screen widget, and the Quick
 * Settings tile.
 */
object PodSwitchControl {

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        runBlocking { AndroidSettingsStore(app).setEnabled(enabled) }
        if (enabled) {
            ContextCompat.startForegroundService(app, Intent(app, SwitchService::class.java))
        } else {
            app.stopService(Intent(app, SwitchService::class.java))
        }
        PodSwitchWidgetProvider.refreshAll(app)
        refreshEnableTile(app)
    }

    fun toggle(context: Context) {
        val enabled = AndroidSettingsStore(context.applicationContext).currentConfig().enabled
        setEnabled(context, !enabled)
    }

    /**
     * Nudges the on/off Quick Settings tile to redraw if it's currently visible in the shade —
     * e.g. so its mode subtitle updates the moment STEAL/ASK changes in the app, without waiting
     * for the panel to be reopened.
     */
    fun refreshEnableTile(context: Context) {
        TileService.requestListeningState(context, ComponentName(context, EnableTileService::class.java))
    }
}
