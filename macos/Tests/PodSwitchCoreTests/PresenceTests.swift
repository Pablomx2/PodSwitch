import XCTest
@testable import PodSwitchCore

final class PresenceTests: XCTestCase {

    private let target = "aabbccddeeff"

    private func claim(_ deviceId: String, playing: Bool = true, ts: Int64 = 1000, ttl: Int64 = 6000) -> PresenceMessage {
        PresenceMessage(kind: .claim, deviceId: deviceId, target: target, playing: playing, timestamp: ts, ttlMillis: ttl)
    }

    // MARK: - message encode / verify

    func testEncodeMatchesKnownWireFormat() {
        // Locks the wire format + HMAC so macOS and Android authenticate each other byte-for-byte.
        let msg = PresenceMessage(kind: .claim, deviceId: "peer-1", target: "aabbccddeeff", playing: true, timestamp: 1000, ttlMillis: 6000)
        let expected = "1|CLAIM|peer-1|aabbccddeeff|true|1000|6000|" +
            "2b2a02d69ba7a225105935163807be973ebb4951f9bb7235a97aaf64f493bd6c"
        XCTAssertEqual(String(data: msg.encode(), encoding: .utf8), expected)
    }

    func testRoundTripVerifiesWithMatchingTarget() {
        let decoded = PresenceMessage.decodeAndVerify(claim("peer-1").encode(), normalizedTarget: target)
        XCTAssertEqual(decoded?.deviceId, "peer-1")
        XCTAssertEqual(decoded?.playing, true)
    }

    func testVerifyRejectsWrongTarget() {
        XCTAssertNil(PresenceMessage.decodeAndVerify(claim("peer-1").encode(), normalizedTarget: "ffffffffffff"))
    }

    func testVerifyRejectsTamperedAuth() {
        let original = String(data: claim("peer-1").encode(), encoding: .utf8)!
        let tampered = original.replacingOccurrences(of: "|true|", with: "|false|")
        XCTAssertNil(PresenceMessage.decodeAndVerify(Data(tampered.utf8), normalizedTarget: target))
    }

    func testVerifyRejectsGarbage() {
        XCTAssertNil(PresenceMessage.decodeAndVerify(Data("not a message".utf8), normalizedTarget: target))
    }

    // MARK: - claim registry

    func testPeerClaimMakesPeerActive() {
        let reg = ClaimRegistry(ownDeviceId: "me")
        reg.record(claim("peer-1"), nowMillis: 0)
        XCTAssertTrue(reg.peerActive(nowMillis: 0))
    }

    func testOwnClaimIsIgnored() {
        let reg = ClaimRegistry(ownDeviceId: "me")
        reg.record(claim("me"), nowMillis: 0)
        XCTAssertFalse(reg.peerActive(nowMillis: 0))
    }

    func testReleaseClearsPeer() {
        let reg = ClaimRegistry(ownDeviceId: "me")
        reg.record(claim("peer-1"), nowMillis: 0)
        reg.record(PresenceMessage(kind: .release, deviceId: "peer-1", target: target, playing: false, timestamp: 1, ttlMillis: 6000), nowMillis: 0)
        XCTAssertFalse(reg.peerActive(nowMillis: 0))
    }

    func testClaimExpiresAfterTtl() {
        let reg = ClaimRegistry(ownDeviceId: "me")
        reg.record(claim("peer-1", ttl: 6000), nowMillis: 0)
        XCTAssertTrue(reg.peerActive(nowMillis: 5999))
        XCTAssertFalse(reg.peerActive(nowMillis: 6000))
    }

    func testHeartbeatRefreshesExpiry() {
        let reg = ClaimRegistry(ownDeviceId: "me")
        reg.record(claim("peer-1", ttl: 6000), nowMillis: 0)
        reg.record(claim("peer-1", ttl: 6000), nowMillis: 4000)
        XCTAssertTrue(reg.peerActive(nowMillis: 9000))
    }

    func testNotPlayingClaimIsNotActive() {
        let reg = ClaimRegistry(ownDeviceId: "me")
        reg.record(claim("peer-1", playing: false), nowMillis: 0)
        XCTAssertFalse(reg.peerActive(nowMillis: 0))
    }
}
