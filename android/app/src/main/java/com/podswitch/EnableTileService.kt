package com.podswitch

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.podswitch.core.Config
import com.podswitch.core.Mode
import com.podswitch.platform.AndroidSettingsStore

/**
 * The single Quick Settings tile PodSwitch offers. Tap toggles PodSwitch fully on/off; its
 * subtitle always shows the current STEAL/ASK mode too ("On · Ask first" / "On · Auto-switch" /
 * "Off"), since Android gives third-party tiles no long-press hook — long-pressing any custom
 * tile is hardcoded by the OS to open the app's "App info" screen, not something this app can
 * redirect. Changing STEAL/ASK mode itself still happens in the app.
 *
 * This is also the recommended Samsung Routines integration point: Routines can drive a
 * quick-panel tile's on/off state as an action for any app that declares one, so this tile is
 * what a Routine should target ("If ... then turn on/off PodSwitch On/Off") to enable or disable
 * PodSwitch.
 */
class EnableTileService : TileService() {

    private val store by lazy { AndroidSettingsStore(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        render(store.currentConfig())
    }

    override fun onClick() {
        super.onClick()
        val config = store.currentConfig()
        val next = !config.enabled
        PodSwitchControl.setEnabled(applicationContext, next)
        render(config.copy(enabled = next))
    }

    private fun render(config: Config) {
        val tile = qsTile ?: return
        tile.state = if (config.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_enable_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_podswitch)
        val subtitle = subtitleFor(config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tile.stateDescription = subtitle
        }
        tile.updateTile()
    }

    private fun subtitleFor(config: Config): String {
        if (!config.enabled) return getString(R.string.qs_enable_off)
        val mode = getString(if (config.mode == Mode.STEAL) R.string.qs_mode_steal else R.string.qs_mode_ask)
        return getString(R.string.qs_enable_on_with_mode, mode)
    }
}
