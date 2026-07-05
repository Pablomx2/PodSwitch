package com.podswitch

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.podswitch.core.Coordinator
import com.podswitch.core.SwitchEvent
import com.podswitch.platform.AndroidBluetoothConnector
import com.podswitch.platform.AndroidNotificationPresenter
import com.podswitch.platform.AndroidSettingsStore
import com.podswitch.platform.AudioMonitor
import com.podswitch.platform.AndroidPresenceCoordinator
import com.podswitch.platform.CallMonitor
import com.podswitch.platform.TargetConnectionMonitor

/**
 * Foreground service wiring the platform implementations into a [Coordinator] and posting the
 * ongoing notification.
 */
class SwitchService : LifecycleService() {

    private lateinit var connector: AndroidBluetoothConnector
    private lateinit var notifier: AndroidNotificationPresenter
    private lateinit var coordinator: Coordinator
    private lateinit var audioMonitor: AudioMonitor
    private lateinit var connectionMonitor: TargetConnectionMonitor
    private lateinit var presence: AndroidPresenceCoordinator

    private var started = false

    override fun onCreate() {
        super.onCreate()
        connector = AndroidBluetoothConnector(applicationContext)
        notifier = AndroidNotificationPresenter(applicationContext)
        val settings = AndroidSettingsStore(applicationContext)
        presence = AndroidPresenceCoordinator(
            context = applicationContext,
            deviceId = settings.deviceId(),
            targetProvider = { settings.currentConfig().targetDeviceId },
        )
        coordinator = Coordinator(
            settings, connector, notifier, presence,
            debugLog = { msg -> Log.d("PodSwitchPresence", msg) },
        )
        // Coordinator's init already claims presence.onPeerChanged for its own take-over logic;
        // chain onto it (rather than overwrite it) so the ongoing notification also refreshes.
        val coordinatorPeerChanged = presence.onPeerChanged
        presence.onPeerChanged = {
            coordinatorPeerChanged?.invoke()
            notifier.updateOngoing(presence.peerActiveOnTarget())
        }
        audioMonitor = AudioMonitor(applicationContext, CallMonitor(applicationContext))
        connectionMonitor = TargetConnectionMonitor(
            context = applicationContext,
            targetAddress = { settings.currentConfig().targetDeviceId },
            onChanged = { connected ->
                coordinator.handle(SwitchEvent.TargetConnectionChanged(connected))
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!started) {
            if (!startForegroundNotification()) {
                return START_NOT_STICKY
            }
            started = true
            connector.acquireProxy()
            connectionMonitor.start()
            presence.start()
            audioMonitor.start { event -> coordinator.handle(event) }
        }

        if (intent?.action == ACTION_ACCEPT) {
            coordinator.handle(SwitchEvent.UserAcceptedSwitch)
        }
        return START_STICKY
    }

    /**
     * Promotes the service to the foreground; returns false (after [stopSelf]) when it cannot start.
     */
    private fun startForegroundNotification(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return false
        }

        val notification = notifier.buildOngoing()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AndroidNotificationPresenter.ID_ONGOING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(AndroidNotificationPresenter.ID_ONGOING, notification)
            }
            true
        } catch (_: SecurityException) {
            stopSelf()
            false
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                stopSelf()
                false
            } else {
                throw e
            }
        }
    }

    override fun onDestroy() {
        if (started) {
            audioMonitor.stop()
            connectionMonitor.stop()
            presence.shutdown()
            connector.releaseProxy()
            started = false
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_ACCEPT = "com.podswitch.action.ACCEPT_SWITCH"
    }
}
