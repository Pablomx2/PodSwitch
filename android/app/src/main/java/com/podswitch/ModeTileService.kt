package com.podswitch

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.podswitch.core.Mode
import com.podswitch.platform.AndroidSettingsStore
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile that toggles the switch mode (STEAL/ASK) from the notification shade.
 */
class ModeTileService : TileService() {

    private val store by lazy { AndroidSettingsStore(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        render(store.currentConfig().mode)
    }

    override fun onClick() {
        super.onClick()
        val next = if (store.currentConfig().mode == Mode.STEAL) Mode.ASK else Mode.STEAL
        runBlocking { store.setMode(next) }
        render(next)
    }

    private fun render(mode: Mode) {
        val tile = qsTile ?: return
        val steal = mode == Mode.STEAL
        tile.state = if (steal) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_podswitch)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (steal) R.string.qs_mode_steal else R.string.qs_mode_ask)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tile.stateDescription = getString(if (steal) R.string.qs_mode_steal else R.string.qs_mode_ask)
        }
        tile.updateTile()
    }
}
