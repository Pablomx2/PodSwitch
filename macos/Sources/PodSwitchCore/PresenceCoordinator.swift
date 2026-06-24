import Foundation
import Darwin

/// UDP-multicast LAN coordination for macOS, interoperating byte-for-byte with the Android app.
///
/// Broadcasts a CLAIM while this Mac holds + plays the target, listens for peer claims, and exposes
/// whether a peer is active. Uses raw POSIX multicast sockets rather than Network.framework, whose
/// multicast API requires an entitlement unavailable to ad-hoc-signed builds. macOS 15+ shows a
/// one-time Local Network permission prompt; if denied, no peers are heard and the engine falls
/// back to the reactive yield guard.
public final class PresenceCoordinator: PresencePort, @unchecked Sendable {

    private let deviceId: String
    private let queue = DispatchQueue(label: "com.podswitch.core.presence")
    private let lock = NSLock()

    private var _onPeerChanged: (@Sendable () -> Void)?
    public var onPeerChanged: (@Sendable () -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _onPeerChanged }
        set { lock.lock(); _onPeerChanged = newValue; lock.unlock() }
    }

    // Queue-confined state.
    private let registry: ClaimRegistry
    private var fd: Int32 = -1
    private var readSource: DispatchSourceRead?
    private var heartbeat: DispatchSourceTimer?
    private var localActive = false
    private var lastPeerActive = false
    private var normalizedTarget: String?

    public init(deviceId: String) {
        self.deviceId = deviceId
        self.registry = ClaimRegistry(ownDeviceId: deviceId)
    }

    /// Update the configured target (raw BT address). Call from anywhere; confined to the queue.
    public func updateTarget(_ rawAddress: String?) {
        let normalized = rawAddress.map { Self.normalize($0) }
        queue.async { [weak self] in self?.normalizedTarget = normalized }
    }

    /// Open the socket, join the group, and start listening.
    public func start() {
        queue.async { [weak self] in self?.openSocket() }
    }

    /// Stop heartbeating, leave the group, and release the socket.
    public func stop() {
        queue.async { [weak self] in self?.closeSocket() }
    }

    public func peerActiveOnTarget() -> Bool {
        queue.sync { registry.peerActive(nowMillis: Self.nowMillis()) }
    }

    public func setLocalActive(_ active: Bool) {
        queue.async { [weak self] in
            guard let self, active != self.localActive else { return }
            self.localActive = active
            if active {
                self.send(.claim)
                let timer = DispatchSource.makeTimerSource(queue: self.queue)
                timer.schedule(deadline: .now() + Self.heartbeat, repeating: Self.heartbeat)
                timer.setEventHandler { [weak self] in self?.send(.claim) }
                self.heartbeat = timer
                timer.resume()
            } else {
                self.heartbeat?.cancel()
                self.heartbeat = nil
                self.send(.release)
            }
        }
    }

    // MARK: - Socket (queue-confined)

    private func openSocket() {
        guard fd < 0 else { return }
        let s = socket(AF_INET, SOCK_DGRAM, 0)
        guard s >= 0 else { return }
        var yes: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(UInt16(Self.port).bigEndian)
        addr.sin_addr.s_addr = INADDR_ANY
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else { close(s); return }

        var mreq = ip_mreq()
        mreq.imr_multiaddr.s_addr = inet_addr(Self.group)
        mreq.imr_interface.s_addr = INADDR_ANY
        setsockopt(s, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, socklen_t(MemoryLayout<ip_mreq>.size))

        _ = fcntl(s, F_SETFL, O_NONBLOCK)
        fd = s

        let source = DispatchSource.makeReadSource(fileDescriptor: s, queue: queue)
        source.setEventHandler { [weak self] in self?.drain() }
        readSource = source
        source.resume()
    }

    private func closeSocket() {
        heartbeat?.cancel(); heartbeat = nil
        readSource?.cancel(); readSource = nil
        if fd >= 0 { close(fd); fd = -1 }
        localActive = false
    }

    private func drain() {
        guard fd >= 0 else { return }
        var buffer = [UInt8](repeating: 0, count: 2048)
        while true {
            let n = recv(fd, &buffer, buffer.count, 0)
            if n <= 0 { break }
            handle(Data(buffer[0..<n]))
        }
    }

    private func handle(_ data: Data) {
        guard let target = normalizedTarget,
              let message = PresenceMessage.decodeAndVerify(data, normalizedTarget: target) else { return }
        registry.record(message, nowMillis: Self.nowMillis())
        let active = registry.peerActive(nowMillis: Self.nowMillis())
        if active != lastPeerActive {
            lastPeerActive = active
            onPeerChanged?()
        }
    }

    private func send(_ kind: PresenceMessage.Kind) {
        guard fd >= 0, let target = normalizedTarget else { return }
        let message = PresenceMessage(
            kind: kind,
            deviceId: deviceId,
            target: target,
            playing: kind == .claim,
            timestamp: Self.nowMillis(),
            ttlMillis: Self.ttl
        )
        let data = message.encode()
        var dest = sockaddr_in()
        dest.sin_family = sa_family_t(AF_INET)
        dest.sin_port = in_port_t(UInt16(Self.port).bigEndian)
        dest.sin_addr.s_addr = inet_addr(Self.group)
        _ = data.withUnsafeBytes { raw in
            withUnsafePointer(to: &dest) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, raw.baseAddress, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    // MARK: - Helpers

    private static func normalize(_ address: String) -> String {
        address.lowercased().replacingOccurrences(of: ":", with: "").replacingOccurrences(of: "-", with: "")
    }

    private static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private static let group = "239.7.7.7"
    private static let port = 54321
    private static let heartbeat: DispatchTimeInterval = .seconds(2)
    private static let ttl: Int64 = 6000
}
