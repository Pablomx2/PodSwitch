package com.podswitch

import com.podswitch.core.ClaimRegistry
import com.podswitch.core.PresenceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {

    private val target = "aabbccddeeff"

    private fun claim(deviceId: String, playing: Boolean = true, ts: Long = 1_000, ttl: Long = 6_000) =
        PresenceMessage(PresenceMessage.Type.CLAIM, deviceId, target, playing, ts, ttl)

    // ---- message encode / verify ----

    @Test
    fun encode_matchesKnownWireFormat() {
        // Locks the wire format + HMAC so Android and macOS authenticate each other byte-for-byte.
        val msg = PresenceMessage(PresenceMessage.Type.CLAIM, "peer-1", "aabbccddeeff", true, 1000, 6000)
        val expected = "1|CLAIM|peer-1|aabbccddeeff|true|1000|6000|" +
            "2b2a02d69ba7a225105935163807be973ebb4951f9bb7235a97aaf64f493bd6c"
        assertEquals(expected, String(msg.encode()))
    }

    @Test
    fun roundTrip_verifiesWithMatchingTarget() {
        val bytes = claim("peer-1").encode()
        val decoded = PresenceMessage.decodeAndVerify(bytes, bytes.size, target)
        assertEquals("peer-1", decoded?.deviceId)
        assertEquals(true, decoded?.playing)
    }

    @Test
    fun verify_rejectsWrongTarget() {
        val bytes = claim("peer-1").encode()
        assertNull(PresenceMessage.decodeAndVerify(bytes, bytes.size, "ffffffffffff"))
    }

    @Test
    fun verify_rejectsTamperedAuth() {
        val original = String(claim("peer-1").encode())
        // Flip the playing flag without recomputing the HMAC.
        val tampered = original.replace("|true|", "|false|").toByteArray()
        assertNull(PresenceMessage.decodeAndVerify(tampered, tampered.size, target))
    }

    @Test
    fun verify_rejectsGarbage() {
        val junk = "not json".toByteArray()
        assertNull(PresenceMessage.decodeAndVerify(junk, junk.size, target))
    }

    // ---- claim registry ----

    @Test
    fun peerClaim_makesPeerActive() {
        val reg = ClaimRegistry(ownDeviceId = "me")
        reg.record(claim("peer-1"), nowMillis = 0)
        assertTrue(reg.peerActive(nowMillis = 0))
    }

    @Test
    fun ownClaim_isIgnored() {
        val reg = ClaimRegistry(ownDeviceId = "me")
        reg.record(claim("me"), nowMillis = 0)
        assertFalse(reg.peerActive(nowMillis = 0))
    }

    @Test
    fun release_clearsPeer() {
        val reg = ClaimRegistry(ownDeviceId = "me")
        reg.record(claim("peer-1"), nowMillis = 0)
        reg.record(PresenceMessage(PresenceMessage.Type.RELEASE, "peer-1", target, false, 1, 6_000), nowMillis = 0)
        assertFalse(reg.peerActive(nowMillis = 0))
    }

    @Test
    fun claim_expiresAfterTtl() {
        val reg = ClaimRegistry(ownDeviceId = "me")
        reg.record(claim("peer-1", ttl = 6_000), nowMillis = 0)
        assertTrue(reg.peerActive(nowMillis = 5_999))
        assertFalse("expired at now >= recordedAt + ttl", reg.peerActive(nowMillis = 6_000))
    }

    @Test
    fun heartbeat_refreshesExpiry() {
        val reg = ClaimRegistry(ownDeviceId = "me")
        reg.record(claim("peer-1", ttl = 6_000), nowMillis = 0)
        reg.record(claim("peer-1", ttl = 6_000), nowMillis = 4_000) // refreshed
        assertTrue(reg.peerActive(nowMillis = 9_000))
    }

    @Test
    fun notPlayingClaim_isNotActive() {
        val reg = ClaimRegistry(ownDeviceId = "me")
        reg.record(claim("peer-1", playing = false), nowMillis = 0)
        assertFalse(reg.peerActive(nowMillis = 0))
    }
}
