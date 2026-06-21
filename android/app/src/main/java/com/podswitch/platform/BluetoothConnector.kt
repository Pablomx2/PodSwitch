package com.podswitch.platform

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.podswitch.core.BluetoothConnector

/** Real [BluetoothConnector] backed by the A2DP profile proxy. */
class AndroidBluetoothConnector(
    private val context: Context,
) : BluetoothConnector {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile
    private var a2dpProxy: BluetoothProfile? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dpProxy = null
        }
    }

    /** Acquire the A2DP proxy. Call once when the service starts. */
    fun acquireProxy() {
        try {
            adapter?.getProfileProxy(context, serviceListener, BluetoothProfile.A2DP)
        } catch (_: Throwable) {
        }
    }

    /** Release the A2DP proxy. Call when the service stops. */
    fun releaseProxy() {
        val proxy = a2dpProxy ?: return
        try {
            adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        } catch (_: Throwable) {
        } finally {
            a2dpProxy = null
        }
    }

    /** A bonded audio device candidate for the UI picker. */
    data class BondedDevice(val address: String, val name: String)

    /** List bonded devices (name + address) for the configuration UI. Empty if no permission. */
    fun bondedDevices(): List<BondedDevice> {
        if (!hasConnectPermission()) return emptyList()
        return try {
            adapter?.bondedDevices.orEmpty().map {
                BondedDevice(address = it.address, name = it.name ?: it.address)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun isPaired(targetDeviceId: String): Boolean {
        if (!hasConnectPermission()) return false
        return try {
            findBondedDevice(targetDeviceId) != null
        } catch (_: Throwable) {
            false
        }
    }

    override fun isActiveOutput(targetDeviceId: String): Boolean {
        if (!hasConnectPermission()) return false
        val proxy = a2dpProxy ?: return false
        val device = runCatching { findBondedDevice(targetDeviceId) }.getOrNull() ?: return false
        return try {
            proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Throwable) {
            false
        }
    }

    override fun connect(targetDeviceId: String) {
        if (!hasConnectPermission()) return
        val proxy = a2dpProxy ?: return
        val device = runCatching { findBondedDevice(targetDeviceId) }.getOrNull() ?: return
        if (isReallyConnected(proxy, device)) return
        startAttempt(proxy, device, attempt = 1)
    }

    /** Run one connect attempt, then poll asynchronously; on timeout retry up to [MAX_ATTEMPTS]. */
    private fun startAttempt(proxy: BluetoothProfile, device: BluetoothDevice, attempt: Int) {
        invokeHiddenConnect(proxy, device)
        pollForConnection(proxy, device, attempt, elapsedMs = 0)
    }

    private fun pollForConnection(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        attempt: Int,
        elapsedMs: Long,
    ) {
        mainHandler.postDelayed({
            if (isReallyConnected(proxy, device)) {
                return@postDelayed
            }
            val nextElapsed = elapsedMs + POLL_INTERVAL_MS
            if (nextElapsed < CONNECT_TIMEOUT_MS) {
                pollForConnection(proxy, device, attempt, nextElapsed)
            } else if (attempt < MAX_ATTEMPTS) {
                startAttempt(proxy, device, attempt + 1)
            }
        }, POLL_INTERVAL_MS)
    }

    /** True when the target is REALLY connected (A2DP state == STATE_CONNECTED). */
    private fun isReallyConnected(proxy: BluetoothProfile, device: BluetoothDevice): Boolean {
        return try {
            proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Throwable) {
            false
        }
    }

    /** Reflectively invoke the hidden `BluetoothA2dp.connect(BluetoothDevice): boolean`. */
    private fun invokeHiddenConnect(proxy: BluetoothProfile, device: BluetoothDevice): Boolean {
        return try {
            val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val result = method.invoke(proxy, device)
            (result as? Boolean) ?: true
        } catch (_: Throwable) {
            false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun findBondedDevice(targetDeviceId: String): BluetoothDevice? {
        val bonded = adapter?.bondedDevices ?: return null
        return bonded.firstOrNull { it.address.equals(targetDeviceId, ignoreCase = true) }
    }

    /** True when BLUETOOTH_CONNECT is held; only a runtime concern on Android 12+ (S). */
    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val CONNECT_TIMEOUT_MS = 4_000L
        const val POLL_INTERVAL_MS = 400L
    }
}
