package com.podswitch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.podswitch.platform.AndroidSettingsStore

/**
 * Starts [SwitchService] on device boot, but only if PodSwitch is enabled and a target
 * device has been configured.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val config = AndroidSettingsStore(context.applicationContext).currentConfig()
        if (!config.enabled || config.targetDeviceId == null) return

        // On S+ the FGS type needs BLUETOOTH_CONNECT; starting without it would crash SwitchService.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val serviceIntent = Intent(context, SwitchService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
