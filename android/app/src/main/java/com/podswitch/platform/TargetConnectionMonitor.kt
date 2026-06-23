package com.podswitch.platform

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Observes the target device's A2DP link to THIS phone and reports connect/disconnect transitions.
 *
 * This is how PodSwitch infers that another source has taken the headphones: in a single-link
 * (non-multipoint) setup, another host grabbing the device shows up here as a disconnect. Android
 * exposes no way to see what a remote host streams through a shared sink, so this link state is the
 * best available signal for the "don't grab back" guard ([com.podswitch.core.Config.yieldToOtherSource]).
 */
class TargetConnectionMonitor(
    private val context: Context,
    private val targetAddress: () -> String?,
    private val onChanged: (connected: Boolean) -> Unit,
) {

    private var receiver: BroadcastReceiver? = null

    private val callback = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
            val device = deviceFrom(intent) ?: return
            val wanted = targetAddress() ?: return
            if (!device.address.equals(wanted, ignoreCase = true)) return

            when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)) {
                BluetoothProfile.STATE_CONNECTED -> onChanged(true)
                BluetoothProfile.STATE_DISCONNECTED -> onChanged(false)
                else -> Unit // ignore transient CONNECTING / DISCONNECTING
            }
        }
    }

    /** Begin listening. Safe to call once when the service starts. */
    fun start() {
        if (receiver != null) return
        receiver = callback
        ContextCompat.registerReceiver(
            context,
            callback,
            IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Stop listening and release the receiver. */
    fun stop() {
        receiver?.let {
            runCatching { context.unregisterReceiver(it) }
            receiver = null
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
