import Foundation
import Darwin
import os

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
    private let logger = Logger(subsystem: "com.podswitch.core", category: "Presence")

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
    private var releaseTimer: DispatchSourceTimer?
    private var localActive = false
    private var lastPeerActive = false
    private var normalizedTarget: String?
    /// Subnet-directed broadcast address for the pinned interface (e.g. 192.168.1.255), used as a
    /// fallback delivery path alongside multicast. Nil if no suitable interface was found.
    private var broadcastAddress: String?

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
                self.logger.debug("local active=true, sending CLAIM + starting heartbeat")
                self.releaseTimer?.cancel()
                self.releaseTimer = nil
                self.send(.claim)
                let timer = DispatchSource.makeTimerSource(queue: self.queue)
                timer.schedule(deadline: .now() + Self.heartbeat, repeating: Self.heartbeat)
                timer.setEventHandler { [weak self] in self?.send(.claim) }
                self.heartbeat = timer
                timer.resume()
            } else {
                // Don't send RELEASE immediately: a brief pause (e.g. between tracks) would hand
                // the target to a peer that's only reacting to a momentary gap. Instead, stop
                // heartbeating now and send RELEASE after a debounce -- long enough to ride out a
                // track change, but still short enough to proactively notify the peer of a genuine
                // stop (rather than relying solely on the passive TTL, which never fires if no
                // further packets arrive to trigger a re-check).
                self.logger.debug("local active=false, stopping heartbeat, scheduling delayed RELEASE")
                self.heartbeat?.cancel()
                self.heartbeat = nil
                let timer = DispatchSource.makeTimerSource(queue: self.queue)
                timer.schedule(deadline: .now() + Self.releaseDebounce)
                timer.setEventHandler { [weak self] in
                    self?.logger.debug("debounce elapsed, sending RELEASE")
                    self?.send(.release)
                    self?.releaseTimer = nil
                }
                self.releaseTimer = timer
                timer.resume()
            }
        }
    }

    // MARK: - Socket (queue-confined)

    private func openSocket() {
        guard fd < 0 else { return }
        let s = socket(AF_INET, SOCK_DGRAM, 0)
        guard s >= 0 else {
            logger.error("socket() failed errno=\(errno) (\(String(cString: strerror(errno))))")
            return
        }
        var yes: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))
        if setsockopt(s, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size)) != 0 {
            logger.warning("SO_BROADCAST failed errno=\(errno)")
        }

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(UInt16(Self.port).bigEndian)
        addr.sin_addr.s_addr = INADDR_ANY
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            logger.error("bind() failed errno=\(errno) (\(String(cString: strerror(errno))))")
            close(s)
            return
        }

        let iface = Self.activeInterface()
        if let iface {
            logger.info("pinning multicast to interface \(iface.name, privacy: .public) addr=\(iface.address, privacy: .private)")
            broadcastAddress = iface.broadcast
        } else {
            logger.warning("no active non-loopback IPv4 interface found; joining on INADDR_ANY")
            broadcastAddress = nil
        }

        var mreq = ip_mreq()
        mreq.imr_multiaddr.s_addr = inet_addr(Self.group)
        mreq.imr_interface.s_addr = iface.map { inet_addr($0.address) } ?? INADDR_ANY
        if setsockopt(s, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, socklen_t(MemoryLayout<ip_mreq>.size)) != 0 {
            logger.error("IP_ADD_MEMBERSHIP failed errno=\(errno) (\(String(cString: strerror(errno))))")
        }

        // IP_ADD_MEMBERSHIP only pins the receive/join side; pin outgoing multicast separately.
        if let iface {
            var ifAddr = in_addr(s_addr: inet_addr(iface.address))
            if setsockopt(s, IPPROTO_IP, IP_MULTICAST_IF, &ifAddr, socklen_t(MemoryLayout<in_addr>.size)) != 0 {
                logger.warning("IP_MULTICAST_IF failed errno=\(errno)")
            }
        }
        var ttl: UInt8 = 1
        setsockopt(s, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, socklen_t(MemoryLayout<UInt8>.size))
        var loop: UInt8 = 1
        setsockopt(s, IPPROTO_IP, IP_MULTICAST_LOOP, &loop, socklen_t(MemoryLayout<UInt8>.size))

        _ = fcntl(s, F_SETFL, O_NONBLOCK)
        fd = s
        logger.info("socket opened fd=\(s) group=\(Self.group, privacy: .public) port=\(Self.port)")

        let source = DispatchSource.makeReadSource(fileDescriptor: s, queue: queue)
        source.setEventHandler { [weak self] in self?.drain() }
        readSource = source
        source.resume()
    }

    private func closeSocket() {
        heartbeat?.cancel(); heartbeat = nil
        releaseTimer?.cancel(); releaseTimer = nil
        readSource?.cancel(); readSource = nil
        if fd >= 0 { close(fd); fd = -1 }
        localActive = false
    }

    private func drain() {
        guard fd >= 0 else { return }
        var buffer = [UInt8](repeating: 0, count: 2048)
        while true {
            let n = recv(fd, &buffer, buffer.count, 0)
            if n < 0 {
                if errno != EWOULDBLOCK && errno != EAGAIN {
                    logger.error("recv() failed errno=\(errno) (\(String(cString: strerror(errno))))")
                }
                break
            }
            if n == 0 { break }
            handle(Data(buffer[0..<n]))
        }
    }

    private func handle(_ data: Data) {
        // Log the sender before the target/HMAC filter so a two-device test can tell "nothing
        // arrived" (network problem) apart from "arrived but rejected" (decode/logic problem).
        let rawSenderId = String(data: data, encoding: .utf8)?
            .components(separatedBy: "|")
            .dropFirst(2).first
        logger.debug("received datagram (\(data.count) bytes) from deviceId=\(rawSenderId ?? "?", privacy: .public)")

        guard let target = normalizedTarget else {
            logger.debug("dropping datagram: no target configured")
            return
        }
        guard let message = PresenceMessage.decodeAndVerify(data, normalizedTarget: target) else {
            logger.debug("dropping datagram: decode/HMAC verification failed")
            return
        }
        logger.debug("decoded \(message.kind.rawValue, privacy: .public) from \(message.deviceId, privacy: .public) playing=\(message.playing)")
        registry.record(message, nowMillis: Self.nowMillis())
        let active = registry.peerActive(nowMillis: Self.nowMillis())
        if active != lastPeerActive {
            lastPeerActive = active
            logger.info("peerActive -> \(active)")
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
        sendTo(data, address: Self.group)
        if let broadcastAddress {
            sendTo(data, address: broadcastAddress)
        }
        sendTo(data, address: "255.255.255.255")
    }

    private func sendTo(_ data: Data, address: String) {
        var dest = sockaddr_in()
        dest.sin_family = sa_family_t(AF_INET)
        dest.sin_port = in_port_t(UInt16(Self.port).bigEndian)
        dest.sin_addr.s_addr = inet_addr(address)
        let sent = data.withUnsafeBytes { raw in
            withUnsafePointer(to: &dest) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, raw.baseAddress, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        if sent < 0 {
            logger.error("sendto \(address, privacy: .public) failed errno=\(errno) (\(String(cString: strerror(errno))))")
        }
    }

    // MARK: - Interface discovery

    private struct Interface {
        let name: String
        let address: String
        let broadcast: String?
    }

    /// The first up, non-loopback, IPv4 interface (typically Wi-Fi `en0`), used to pin outgoing
    /// multicast/broadcast rather than leaving it to `INADDR_ANY`, which can silently bind to the
    /// wrong interface (or none) and drop all peer traffic.
    private static func activeInterface() -> Interface? {
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else { return nil }
        defer { freeifaddrs(first) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        var candidate: Interface?
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let addr = entry.pointee.ifa_addr, addr.pointee.sa_family == sa_family_t(AF_INET)
            else { continue }

            let name = String(cString: entry.pointee.ifa_name)
            let address = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                String(cString: inet_ntoa($0.pointee.sin_addr))
            }
            var broadcast: String?
            if flags & IFF_BROADCAST != 0, let bcastAddr = entry.pointee.ifa_dstaddr,
               bcastAddr.pointee.sa_family == sa_family_t(AF_INET) {
                broadcast = bcastAddr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                    String(cString: inet_ntoa($0.pointee.sin_addr))
                }
            }
            let found = Interface(name: name, address: address, broadcast: broadcast)
            // Prefer en0 (typical Wi-Fi on Mac laptops/desktops) but accept the first candidate
            // found so we still work on machines where Wi-Fi isn't en0.
            if name == "en0" { return found }
            if candidate == nil { candidate = found }
        }
        return candidate
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
    private static let releaseDebounce: DispatchTimeInterval = .seconds(4)
}
