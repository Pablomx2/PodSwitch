package com.podswitch.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.podswitch.core.ClaimRegistry
import com.podswitch.core.PresenceMessage
import com.podswitch.core.PresencePort
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
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

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registry = ClaimRegistry(deviceId)

    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiverThread: Thread? = null
    /** Subnet-directed broadcast address for the Wi-Fi interface, used as a fallback delivery path
     *  alongside multicast. Null if no Wi-Fi interface/broadcast address could be resolved. */
    private var broadcastAddress: InetAddress? = null

    private val sender = Executors.newSingleThreadScheduledExecutor()
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
    private var releaseTimer: java.util.concurrent.ScheduledFuture<*>? = null
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
            val wifiNif = resolveWifiInterface()
            socket = MulticastSocket(PORT).apply {
                reuseAddress = true
                broadcast = true
                if (wifiNif != null) {
                    networkInterface = wifiNif
                    joinGroup(InetSocketAddress(group, PORT), wifiNif)
                    Log.i(TAG, "socket opened, joined $GROUP:$PORT on interface ${wifiNif.name}")
                } else {
                    @Suppress("DEPRECATION")
                    joinGroup(group)
                    Log.w(TAG, "no Wi-Fi NetworkInterface resolved; joined $GROUP:$PORT unbound")
                }
                timeToLive = 1
            }
            broadcastAddress = resolveBroadcastAddress(wifiNif)
            receiverThread = Thread({ receiveLoop() }, "podswitch-presence-rx").also { it.start() }
        } catch (t: Throwable) {
            Log.e(TAG, "start() failed", t)
            stop()
        }
    }

    /** Stop heartbeating, leave the group, and release resources. */
    fun stop() {
        running.set(false)
        cancelHeartbeat()
        cancelReleaseTimer()
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
                Log.d(TAG, "local active=true, sending CLAIM + starting heartbeat")
                cancelReleaseTimer()
                sendNow(PresenceMessage.Type.CLAIM)
                heartbeat = sender.scheduleAtFixedRate(
                    { sendNow(PresenceMessage.Type.CLAIM) },
                    HEARTBEAT_MS, HEARTBEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            } else {
                // Don't send RELEASE immediately: a brief pause (e.g. between tracks) would hand
                // the target to a peer that's only reacting to a momentary gap. Instead, stop
                // heartbeating now and send RELEASE after a debounce -- long enough to ride out a
                // track change, but still short enough to proactively notify the peer of a genuine
                // stop (rather than relying solely on the passive TTL, which never fires if no
                // further packets arrive to trigger a re-check).
                Log.d(TAG, "local active=false, stopping heartbeat, scheduling delayed RELEASE")
                cancelHeartbeat()
                releaseTimer = sender.schedule(
                    {
                        Log.d(TAG, "debounce elapsed, sending RELEASE")
                        sendNow(PresenceMessage.Type.RELEASE)
                        releaseTimer = null
                    },
                    RELEASE_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun cancelHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    private fun cancelReleaseTimer() {
        releaseTimer?.cancel(false)
        releaseTimer = null
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
        val bytes = message.encode()
        runCatching {
            sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(GROUP), PORT))
        }.onFailure { Log.w(TAG, "send to multicast group failed", it) }

        val bcast = broadcastAddress
        if (bcast != null) {
            runCatching {
                sock.send(DatagramPacket(bytes, bytes.size, bcast, PORT))
            }.onFailure { Log.w(TAG, "send to subnet broadcast failed", it) }
        }
        runCatching {
            sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), PORT))
        }.onFailure { Log.w(TAG, "send to 255.255.255.255 failed", it) }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(2048)
        while (running.get()) {
            val sock = socket ?: break
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { sock.receive(packet); true }
                .onFailure { if (running.get()) Log.w(TAG, "receive() failed", it) }
                .getOrDefault(false)
            if (!received) break

            // Log the sender before the target/HMAC filter so a two-device test can tell "nothing
            // arrived" (network problem) apart from "arrived but rejected" (decode/logic problem).
            val rawSenderId = runCatching {
                String(packet.data, 0, packet.length, Charsets.UTF_8).split("|").getOrNull(2)
            }.getOrNull()
            Log.d(TAG, "received datagram (${packet.length} bytes) from deviceId=$rawSenderId")

            val target = targetProvider()?.let { normalize(it) }
            if (target == null) {
                Log.d(TAG, "dropping datagram: no target configured")
                continue
            }
            val message = PresenceMessage.decodeAndVerify(packet.data, packet.length, target)
            if (message == null) {
                Log.d(TAG, "dropping datagram: decode/HMAC verification failed")
                continue
            }
            Log.d(TAG, "decoded ${message.type} from ${message.deviceId} playing=${message.playing}")
            val now = synchronized(registry) {
                registry.record(message, System.currentTimeMillis())
                registry.peerActive(System.currentTimeMillis())
            }
            if (now != lastPeerActive) {
                lastPeerActive = now
                Log.i(TAG, "peerActive -> $now")
                mainHandler.post { onPeerChanged?.invoke() }
            }
        }
    }

    /** Resolve the [NetworkInterface] backing the currently active Wi-Fi network, if any. Pinning
     *  the socket to it avoids multicast silently binding to cellular when both are up. */
    private fun resolveWifiInterface(): NetworkInterface? {
        return runCatching {
            val network = connectivity.activeNetwork ?: return null
            val linkProperties = connectivity.getLinkProperties(network) ?: return null
            val name = linkProperties.interfaceName ?: return null
            NetworkInterface.getByName(name)
        }.onFailure { Log.w(TAG, "resolveWifiInterface failed", it) }.getOrNull()
    }

    private fun resolveBroadcastAddress(nif: NetworkInterface?): InetAddress? {
        val iface = nif ?: return null
        return runCatching {
            iface.interfaceAddresses.firstOrNull { it.broadcast != null }?.broadcast
        }.onFailure { Log.w(TAG, "resolveBroadcastAddress failed", it) }.getOrNull()
    }

    private fun normalize(address: String): String =
        address.lowercase().replace(":", "").replace("-", "")

    private companion object {
        const val TAG = "PodSwitchPresence"
        const val GROUP = "239.7.7.7"
        const val PORT = 54321
        const val HEARTBEAT_MS = 2000L
        const val TTL_MS = 6000L
        const val RELEASE_DEBOUNCE_MS = 4000L
    }
}
