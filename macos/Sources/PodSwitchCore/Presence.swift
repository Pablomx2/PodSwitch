import Foundation
import CryptoKit

/// A presence message exchanged between PodSwitch devices on the LAN.
///
/// Wire format is byte-for-byte identical to the Android implementation so the two interoperate:
/// `VERSION|TYPE|deviceId|target|playing|ts|ttlMs|auth`, where `auth` is the lowercase-hex
/// HMAC-SHA256 of the first seven fields, keyed on the normalized target Bluetooth address. No
/// typed pairing: only devices configured for the same earbuds can authenticate each other.
public struct PresenceMessage: Equatable {

    public enum Kind: String { case claim = "CLAIM", release = "RELEASE" }

    public let kind: Kind
    public let deviceId: String
    public let target: String
    public let playing: Bool
    public let timestamp: Int64
    public let ttlMillis: Int64

    public static let version = 1

    public init(kind: Kind, deviceId: String, target: String, playing: Bool, timestamp: Int64, ttlMillis: Int64) {
        self.kind = kind
        self.deviceId = deviceId
        self.target = target
        self.playing = playing
        self.timestamp = timestamp
        self.ttlMillis = ttlMillis
    }

    private func canonical() -> String {
        "\(Self.version)|\(kind.rawValue)|\(deviceId)|\(target)|\(playing)|\(timestamp)|\(ttlMillis)"
    }

    public func encode() -> Data {
        let body = canonical()
        return Data("\(body)|\(Self.authTag(target: target, body: body))".utf8)
    }

    /// Parse + authenticate a received datagram for `normalizedTarget`, or `nil` if invalid.
    public static func decodeAndVerify(_ data: Data, normalizedTarget: String) -> PresenceMessage? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        let parts = text.components(separatedBy: "|")
        guard parts.count == 8 else { return nil }
        guard parts[0] == String(version) else { return nil }
        guard parts[3] == normalizedTarget else { return nil }
        guard let kind = Kind(rawValue: parts[1]) else { return nil }
        guard let timestamp = Int64(parts[5]), let ttl = Int64(parts[6]) else { return nil }
        let body = parts[0...6].joined(separator: "|")
        guard constantTimeEquals(authTag(target: normalizedTarget, body: body), parts[7]) else { return nil }
        return PresenceMessage(
            kind: kind,
            deviceId: parts[2],
            target: parts[3],
            playing: parts[4] == "true",
            timestamp: timestamp,
            ttlMillis: ttl
        )
    }

    private static func authTag(target: String, body: String) -> String {
        let key = SymmetricKey(data: Data(target.utf8))
        let code = HMAC<SHA256>.authenticationCode(for: Data(body.utf8), using: key)
        return code.map { String(format: "%02x", $0) }.joined()
    }

    private static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let ab = Array(a.utf8), bb = Array(b.utf8)
        guard ab.count == bb.count else { return false }
        var diff: UInt8 = 0
        for i in ab.indices { diff |= ab[i] ^ bb[i] }
        return diff == 0
    }
}

/// Pure, clock-injected store of peer claims for OUR target. Only the device currently holding the
/// target broadcasts, so at most one peer is ever "active". Expired claims are pruned on read.
public final class ClaimRegistry {

    private struct Entry { let playing: Bool; let expiresAt: Int64 }

    private let ownDeviceId: String
    private var byPeer: [String: Entry] = [:]

    public init(ownDeviceId: String) {
        self.ownDeviceId = ownDeviceId
    }

    /// Record a verified peer message. Our own loopback messages are ignored.
    public func record(_ message: PresenceMessage, nowMillis: Int64) {
        guard message.deviceId != ownDeviceId else { return }
        switch message.kind {
        case .release:
            byPeer[message.deviceId] = nil
        case .claim:
            byPeer[message.deviceId] = Entry(playing: message.playing, expiresAt: nowMillis + message.ttlMillis)
        }
    }

    /// True if any non-expired peer reports it is actively playing on the target.
    public func peerActive(nowMillis: Int64) -> Bool {
        prune(nowMillis)
        return byPeer.values.contains { $0.playing }
    }

    private func prune(_ nowMillis: Int64) {
        byPeer = byPeer.filter { $0.value.expiresAt > nowMillis }
    }
}
