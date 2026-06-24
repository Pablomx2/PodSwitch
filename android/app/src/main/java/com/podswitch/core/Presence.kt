package com.podswitch.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A presence message exchanged between PodSwitch devices on the LAN to coordinate who holds a
 * shared target. Authenticated by an HMAC keyed on the (normalized) target Bluetooth address, so
 * only devices configured for the *same* earbuds accept each other's messages — no typed pairing
 * code, and unrelated LAN users can't interfere.
 */
data class PresenceMessage(
    val type: Type,
    val deviceId: String,
    /** Normalized target Bluetooth address the claim is about. */
    val target: String,
    val playing: Boolean,
    val timestamp: Long,
    val ttlMillis: Long,
) {
    enum class Type { CLAIM, RELEASE }

    /**
     * Serialize to a pipe-delimited line with a trailing HMAC tag keyed on [target]:
     * `VERSION|TYPE|deviceId|target|playing|ts|ttlMs|auth`. Plain text (no JSON) so it's trivial to
     * mirror byte-for-byte on macOS and testable on the plain JVM. deviceId is a UUID and target is
     * hex, so neither contains the '|' delimiter.
     */
    fun encode(): ByteArray {
        val body = canonical()
        return "$body|${authTag(target, body)}".toByteArray(Charsets.UTF_8)
    }

    private fun canonical(): String =
        "$VERSION|${type.name}|$deviceId|$target|$playing|$timestamp|$ttlMillis"

    companion object {
        const val VERSION = 1

        /**
         * Parse + authenticate a received datagram. Returns null if it's malformed, for a different
         * target, a wrong version, or fails the HMAC check against [normalizedTarget].
         */
        fun decodeAndVerify(bytes: ByteArray, length: Int, normalizedTarget: String): PresenceMessage? {
            val text = runCatching { String(bytes, 0, length, Charsets.UTF_8) }.getOrNull() ?: return null
            val parts = text.split("|")
            if (parts.size != 8) return null
            if (parts[0] != VERSION.toString()) return null
            if (parts[3] != normalizedTarget) return null
            val type = runCatching { Type.valueOf(parts[1]) }.getOrNull() ?: return null
            val timestamp = parts[5].toLongOrNull() ?: return null
            val ttl = parts[6].toLongOrNull() ?: return null
            val body = parts.subList(0, 7).joinToString("|")
            if (!constantTimeEquals(authTag(normalizedTarget, body), parts[7])) return null
            return PresenceMessage(
                type = type,
                deviceId = parts[2],
                target = parts[3],
                playing = parts[4].toBooleanStrict(),
                timestamp = timestamp,
                ttlMillis = ttl,
            )
        }

        private fun authTag(target: String, canonical: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(target.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            // Mask to a byte — a raw signed Byte would sign-extend to 8 hex chars and break interop
            // with the macOS HMAC (which emits unsigned 2-char hex).
            return mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}

/**
 * Pure, clock-injected store of peer claims for OUR target. Only the device currently holding the
 * target broadcasts, so at most one peer is ever "active". Expired claims are pruned on read, so a
 * sleeping/crashed peer never locks the target forever.
 */
class ClaimRegistry(private val ownDeviceId: String) {

    private data class Entry(val playing: Boolean, val expiresAt: Long)

    private val byPeer = HashMap<String, Entry>()

    /** Record a verified peer message. Our own loopback messages are ignored. */
    fun record(message: PresenceMessage, nowMillis: Long) {
        if (message.deviceId == ownDeviceId) return
        when (message.type) {
            PresenceMessage.Type.RELEASE -> byPeer.remove(message.deviceId)
            PresenceMessage.Type.CLAIM ->
                byPeer[message.deviceId] = Entry(message.playing, nowMillis + message.ttlMillis)
        }
    }

    /** True if any non-expired peer reports it is actively playing on the target. */
    fun peerActive(nowMillis: Long): Boolean {
        prune(nowMillis)
        return byPeer.values.any { it.playing }
    }

    private fun prune(nowMillis: Long) {
        val it = byPeer.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiresAt <= nowMillis) it.remove()
        }
    }
}
