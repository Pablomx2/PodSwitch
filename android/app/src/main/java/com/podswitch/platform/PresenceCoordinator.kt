package com.podswitch.platform

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.podswitch.core.ClaimRegistry
import com.podswitch.core.PresenceMessage
import com.podswitch.core.PresencePort
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP-multicast LAN coordination. Broadcasts a CLAIM while this device holds + plays the target,
 * listens for peer claims, and exposes whether a peer is currently active. Best-effort: if the
 * network blocks multicast or no peer is present, [peerActiveOnTarget] stays false and the engine
 * falls back to the reactive yield guard.
 *
 * Auto-paired: messages are authenticated with an HMAC keyed on the normalized target address, so
 * two devices targeting the same earbuds coordinate with no typed setup, and nobody else can.
 */
class AndroidPresenceCoordinator(
    context: Context,
    private val deviceId: String,
    /** Returns the raw configured target Bluetooth address, or null if unconfigured. */
    private val targetProvider: () -> String?,
) : PresencePort {

    override var onPeerChanged: (() -> Unit)? = null

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registry = ClaimRegistry(deviceId)

    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiverThread: Thread? = null

    private val sender = Executors.newSingleThreadScheduledExecutor()
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
    private var localActive = false

    private var lastPeerActive = false

    /** Open the socket, join the group, and start listening. Safe to call once. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            multicastLock = wifi.createMulticastLock("podswitch").apply {
                setReferenceCounted(false)
                acquire()
            }
            val group = InetAddress.getByName(GROUP)
            socket = MulticastSocket(PORT).apply {
                reuseAddress = true
                @Suppress("DEPRECATION")
                joinGroup(group)
            }
            receiverThread = Thread({ receiveLoop() }, "podswitch-presence-rx").also { it.start() }
        } catch (_: Throwable) {
            stop()
        }
    }

    /** Stop heartbeating, leave the group, and release resources. */
    fun stop() {
        running.set(false)
        cancelHeartbeat()
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        receiverThread = null
    }

    fun shutdown() {
        stop()
        sender.shutdownNow()
    }

    override fun peerActiveOnTarget(): Boolean =
        synchronized(registry) { registry.peerActive(System.currentTimeMillis()) }

    override fun setLocalActive(active: Boolean) {
        sender.execute {
            if (active == localActive) return@execute
            localActive = active
            if (active) {
                sendNow(PresenceMessage.Type.CLAIM)
                heartbeat = sender.scheduleAtFixedRate(
                    { sendNow(PresenceMessage.Type.CLAIM) },
                    HEARTBEAT_MS, HEARTBEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            } else {
                cancelHeartbeat()
                sendNow(PresenceMessage.Type.RELEASE)
            }
        }
    }

    private fun cancelHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    private fun sendNow(type: PresenceMessage.Type) {
        val target = targetProvider()?.let { normalize(it) } ?: return
        val sock = socket ?: return
        val message = PresenceMessage(
            type = type,
            deviceId = deviceId,
            target = target,
            playing = type == PresenceMessage.Type.CLAIM,
            timestamp = System.currentTimeMillis(),
            ttlMillis = TTL_MS,
        )
        runCatching {
            val bytes = message.encode()
            sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(GROUP), PORT))
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(2048)
        while (running.get()) {
            val sock = socket ?: break
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { sock.receive(packet); true }.getOrDefault(false)
            if (!received) break
            val target = targetProvider()?.let { normalize(it) } ?: continue
            val message = PresenceMessage.decodeAndVerify(packet.data, packet.length, target) ?: continue
            val now = synchronized(registry) {
                registry.record(message, System.currentTimeMillis())
                registry.peerActive(System.currentTimeMillis())
            }
            if (now != lastPeerActive) {
                lastPeerActive = now
                mainHandler.post { onPeerChanged?.invoke() }
            }
        }
    }

    private fun normalize(address: String): String =
        address.lowercase().replace(":", "").replace("-", "")

    private companion object {
        const val GROUP = "239.7.7.7"
        const val PORT = 54321
        const val HEARTBEAT_MS = 2000L
        const val TTL_MS = 6000L
    }
}
