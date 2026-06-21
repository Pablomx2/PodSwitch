import Foundation

/// Streams the system "Now Playing" play/pause state via the bundled MediaRemote
/// perl adapter, watched event-driven over a temp file.
@MainActor
final class NowPlayingSignal {

    /// Emitted as `(isPlaying, hasSession)` whenever either changes.
    var onChange: ((Bool, Bool) -> Void)?
    /// Emitted if the adapter repeatedly fails to produce output.
    var onUnavailable: (() -> Void)?

    private let perlPath: String
    private let scriptPath: String
    private let frameworkPath: String
    private let outputURL: URL

    private var process: Process?
    private var readFD: Int32 = -1
    private var fileSource: (any DispatchSourceFileSystemObject)?
    private var procSource: (any DispatchSourceProcess)?
    private var readOffset: off_t = 0
    private var buffer = Data()
    private var lastPlaying = false
    private var lastHasSession = false
    private var started = false
    private var gotOutput = false
    private var respawns = 0

    init(
        scriptPath: String,
        frameworkPath: String,
        perlPath: String = "/usr/bin/perl"
    ) {
        self.scriptPath = scriptPath
        self.frameworkPath = frameworkPath
        self.perlPath = perlPath
        self.outputURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("podswitch-nowplaying-\(getpid()).jsonl")
    }

    func start() {
        guard !started else { return }
        started = true

        FileManager.default.createFile(atPath: outputURL.path, contents: Data())
        readFD = open(outputURL.path, O_RDONLY)
        guard readFD >= 0 else {
            started = false
            onUnavailable?()
            return
        }

        let source = DispatchSource.makeFileSystemObjectSource(
            fileDescriptor: readFD, eventMask: [.write, .extend], queue: DispatchQueue.main)
        source.setEventHandler { [weak self] in
            Task { @MainActor [weak self] in self?.drainNewOutput() }
        }
        fileSource = source
        source.resume()

        spawn()
    }

    func stop() {
        started = false
        fileSource?.cancel()
        fileSource = nil
        procSource?.cancel()
        procSource = nil
        process?.terminate()
        process = nil
        if readFD >= 0 { close(readFD); readFD = -1 }
        try? FileManager.default.removeItem(at: outputURL)
        buffer.removeAll()
    }

    /// Latest known state, for an initial combine before the first edge.
    var isPlaying: Bool { lastPlaying }
    var hasSession: Bool { lastHasSession }

    // MARK: - Adapter process

    private func spawn() {
        guard started else { return }
        // The shell `>` truncates the file (same inode the vnode source watches); restart reading from the top.
        readOffset = 0
        buffer.removeAll()

        let command = "exec "
            + Self.shellQuote(perlPath) + " "
            + Self.shellQuote(scriptPath) + " "
            + Self.shellQuote(frameworkPath)
            + " stream --no-diff --no-artwork > "
            + Self.shellQuote(outputURL.path) + " 2>/dev/null < /dev/null"

        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/bin/sh")
        proc.arguments = ["-c", command]
        proc.standardOutput = FileHandle.nullDevice
        proc.standardError = FileHandle.nullDevice
        do {
            try proc.run()
            process = proc
            watchExit(of: proc.processIdentifier)
            drainNewOutput()
        } catch {
            scheduleRespawn()
        }
    }

    private func watchExit(of pid: pid_t) {
        procSource?.cancel()
        let source = DispatchSource.makeProcessSource(identifier: pid, eventMask: .exit, queue: DispatchQueue.main)
        source.setEventHandler { [weak self] in
            Task { @MainActor [weak self] in self?.handleExit() }
        }
        procSource = source
        source.resume()
    }

    private func handleExit() {
        guard started else { return }
        procSource?.cancel()
        procSource = nil
        process = nil
        if !gotOutput {
            respawns += 1
            if respawns >= 4 {
                started = false
                onUnavailable?()
                return
            }
        }
        scheduleRespawn()
    }

    /// One-shot backoff before re-spawning (a single delayed retry, not a poll).
    private func scheduleRespawn() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.started else { return }
                self.spawn()
            }
        }
    }

    // MARK: - Reading

    private func drainNewOutput() {
        guard readFD >= 0 else { return }
        lseek(readFD, readOffset, SEEK_SET)
        var chunk = [UInt8](repeating: 0, count: 16384)
        while true {
            let n = read(readFD, &chunk, chunk.count)
            if n <= 0 { break }
            readOffset += off_t(n)
            gotOutput = true
            buffer.append(contentsOf: chunk[0..<n])
        }
        while let newline = buffer.firstIndex(of: 0x0A) {
            let lineData = Data(buffer[buffer.startIndex..<newline])
            buffer.removeSubrange(buffer.startIndex...newline)
            if let line = String(data: lineData, encoding: .utf8) {
                handleLine(line)
            }
        }
    }

    private func handleLine(_ line: String) {
        guard let parsed = Self.parse(line: line) else { return }
        guard parsed.playing != lastPlaying || parsed.hasSession != lastHasSession else { return }
        lastPlaying = parsed.playing
        lastHasSession = parsed.hasSession
        onChange?(parsed.playing, parsed.hasSession)
    }

    /// Parses one stream line into play/session state; `nil` for non-data lines.
    static func parse(line: String) -> (playing: Bool, hasSession: Bool)? {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else { return nil }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (object["type"] as? String) == "data",
              let payload = object["payload"] as? [String: Any] else { return nil }
        if let playing = payload["playing"] as? Bool {
            return (playing, true)
        }
        return (false, false)
    }

    /// Single-quote a path for safe interpolation into the `/bin/sh -c` command.
    static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
