import Foundation
import CoreAudio

/// CoreAudio-backed audio monitor feeding a `PlaybackEdgeDetector`.
@MainActor
public final class AudioMonitor: AudioMonitoring {

    public weak var delegate: AudioMonitorDelegate?

    private let detector: PlaybackEdgeDetector
    /// Boxed `ProcessPlaybackSignal` (macOS 14+); `nil` on the device fallback.
    private var processSignalBox: AnyObject?
    /// MediaRemote now-playing signal (macOS 14+), when the adapter is bundled.
    private var nowPlaying: NowPlayingSignal?
    private var defaultDeviceID = AudioObjectID(kAudioObjectUnknown)
    private var started = false

    private var lastProcessPlaying = false
    private var lastNowPlaying = false
    private var lastNowPlayingHasSession = false
    private var nowPlayingAvailable = false

    /// Device currently wired up for mute notifications; tracked separately from
    /// `defaultDeviceID` since mute tracking runs on every macOS version.
    private var muteDeviceID = AudioObjectID(kAudioObjectUnknown)
    private var lastMuted = false

    /// macOS 14+ process signal, when active.
    @available(macOS 14.0, *)
    private var processSignal: ProcessPlaybackSignal? { processSignalBox as? ProcessPlaybackSignal }

    /// Combined playback state: muted always wins, then MediaRemote when a media
    /// session is active, else the broad process-output signal (or, pre-macOS 14,
    /// the raw device-running signal).
    private func combinedPlaying() -> Bool {
        if lastMuted { return false }
        if nowPlayingAvailable && lastNowPlayingHasSession { return lastNowPlaying }
        if processSignalBox != nil { return lastProcessPlaying }
        return readIsRunning(on: defaultDeviceID)
    }

    private func recomputeCombined() {
        detector.runningChanged(combinedPlaying())
    }

    /// Locate the bundled MediaRemote adapter (script + framework), if present.
    private static func locateAdapter() -> (script: String, framework: String)? {
        guard let resources = Bundle.main.resourceURL else { return nil }
        let base = resources.appendingPathComponent("MediaRemoteAdapter")
        let script = base.appendingPathComponent("mediaremote-adapter.pl")
        let framework = base.appendingPathComponent("MediaRemoteAdapter.framework")
        let fm = FileManager.default
        guard fm.fileExists(atPath: script.path), fm.fileExists(atPath: framework.path) else { return nil }
        return (script.path, framework.path)
    }

    private static let defaultDeviceAddress = AudioObjectPropertyAddress(
        mSelector: kAudioHardwarePropertyDefaultOutputDevice,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain
    )

    private static let isRunningAddress = AudioObjectPropertyAddress(
        mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain
    )

    private static let muteAddress = AudioObjectPropertyAddress(
        mSelector: kAudioDevicePropertyMute,
        mScope: kAudioDevicePropertyScopeOutput,
        mElement: kAudioObjectPropertyElementMain
    )

    /// CoreAudio listener block; hops to the main actor before touching state.
    private lazy var listenerBlock: AudioObjectPropertyListenerBlock = { [weak self] inNumberAddresses, addresses in
        let buffer = UnsafeBufferPointer(start: addresses, count: Int(inNumberAddresses))
        let selectors = buffer.map { $0.mSelector }
        Task { @MainActor [weak self] in
            self?.handlePropertyChanges(selectors: selectors)
        }
    }

    public init(debounceInterval: TimeInterval = 0.3, sustainInterval: TimeInterval = 1.0) {
        self.detector = PlaybackEdgeDetector(
            debounceInterval: debounceInterval,
            sustainInterval: sustainInterval,
            stopScheduler: MainQueueDebounceScheduler(),
            sustainScheduler: MainQueueDebounceScheduler()
        )
        detector.onStarted = { [weak self] in
            guard let self else { return }
            self.delegate?.audioMonitor(self, didEmit: .audioStarted(.media))
        }
        detector.onStopped = { [weak self] in
            guard let self else { return }
            self.delegate?.audioMonitor(self, didEmit: .audioStopped)
        }
    }

    public func start() {
        guard !started else { return }
        started = true

        startMuteTracking()

        if #available(macOS 14.0, *) {
            let signal = ProcessPlaybackSignal()
            signal.onPlayingChanged = { [weak self] playing in
                self?.lastProcessPlaying = playing
                self?.recomputeCombined()
            }
            processSignalBox = signal
            signal.start()
            lastProcessPlaying = signal.currentlyPlaying()

            if let adapter = Self.locateAdapter() {
                let np = NowPlayingSignal(scriptPath: adapter.script, frameworkPath: adapter.framework)
                np.onChange = { [weak self] playing, hasSession in
                    self?.lastNowPlaying = playing
                    self?.lastNowPlayingHasSession = hasSession
                    self?.recomputeCombined()
                }
                np.onUnavailable = { [weak self] in
                    self?.nowPlayingAvailable = false
                    self?.recomputeCombined()
                }
                nowPlaying = np
                nowPlayingAvailable = true
                np.start()
            }

            detector.prime(running: combinedPlaying())
        } else {
            let systemObject = AudioObjectID(kAudioObjectSystemObject)
            AudioObjectAddPropertyListenerBlock(
                systemObject,
                &Self.mutableDefaultDeviceAddress,
                DispatchQueue.main,
                listenerBlock
            )
            attachToCurrentDefaultDevice()
            detector.prime(running: combinedPlaying())
        }
    }

    public func stop() {
        guard started else { return }
        started = false

        stopMuteTracking()
        detector.reset()

        nowPlaying?.stop()
        nowPlaying = nil
        nowPlayingAvailable = false

        if #available(macOS 14.0, *), let signal = processSignal {
            signal.stop()
            processSignalBox = nil
        } else {
            let systemObject = AudioObjectID(kAudioObjectSystemObject)
            AudioObjectRemovePropertyListenerBlock(
                systemObject,
                &Self.mutableDefaultDeviceAddress,
                DispatchQueue.main,
                listenerBlock
            )
            detachFromCurrentDefaultDevice()
            defaultDeviceID = AudioObjectID(kAudioObjectUnknown)
        }
    }

    // MARK: - Device tracking

    private static var mutableDefaultDeviceAddress = AudioMonitor.defaultDeviceAddress
    private static var mutableIsRunningAddress = AudioMonitor.isRunningAddress
    private static var mutableMuteAddress = AudioMonitor.muteAddress

    private func attachToCurrentDefaultDevice() {
        defaultDeviceID = readDefaultOutputDevice()
        guard defaultDeviceID != AudioObjectID(kAudioObjectUnknown) else { return }
        AudioObjectAddPropertyListenerBlock(
            defaultDeviceID,
            &Self.mutableIsRunningAddress,
            DispatchQueue.main,
            listenerBlock
        )
    }

    private func detachFromCurrentDefaultDevice() {
        guard defaultDeviceID != AudioObjectID(kAudioObjectUnknown) else { return }
        AudioObjectRemovePropertyListenerBlock(
            defaultDeviceID,
            &Self.mutableIsRunningAddress,
            DispatchQueue.main,
            listenerBlock
        )
    }

    // MARK: - Property reads

    private func readDefaultOutputDevice() -> AudioObjectID {
        var deviceID = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var address = Self.defaultDeviceAddress
        let status = AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            0,
            nil,
            &size,
            &deviceID
        )
        return status == noErr ? deviceID : AudioObjectID(kAudioObjectUnknown)
    }

    private func readIsRunning(on device: AudioObjectID) -> Bool {
        guard device != AudioObjectID(kAudioObjectUnknown) else { return false }
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        var address = Self.isRunningAddress
        let status = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value)
        return status == noErr && value != 0
    }

    // MARK: - Change handling

    /// macOS 13 fallback only — the 14+ path registers no device listeners here.
    private func handlePropertyChanges(selectors: [AudioObjectPropertySelector]) {
        guard started else { return }

        if selectors.contains(kAudioHardwarePropertyDefaultOutputDevice) {
            detachFromCurrentDefaultDevice()
            attachToCurrentDefaultDevice()
            detector.deviceChanged(runningNow: combinedPlaying())
        }

        if selectors.contains(kAudioDevicePropertyDeviceIsRunningSomewhere) {
            recomputeCombined()
        }
    }

    // MARK: - Mute tracking

    /// Watches the default output device's mute state, independent of which
    /// playback-detection strategy is active; re-targets itself as the default
    /// device changes.
    private func startMuteTracking() {
        let systemObject = AudioObjectID(kAudioObjectSystemObject)
        AudioObjectAddPropertyListenerBlock(
            systemObject, &Self.mutableDefaultDeviceAddress, DispatchQueue.main, muteRoutingBlock
        )
        reattachMuteListener()
    }

    private func stopMuteTracking() {
        let systemObject = AudioObjectID(kAudioObjectSystemObject)
        AudioObjectRemovePropertyListenerBlock(
            systemObject, &Self.mutableDefaultDeviceAddress, DispatchQueue.main, muteRoutingBlock
        )
        detachMuteListener()
        lastMuted = false
    }

    private func reattachMuteListener() {
        detachMuteListener()
        let device = readDefaultOutputDevice()
        guard device != AudioObjectID(kAudioObjectUnknown),
              AudioObjectHasProperty(device, &Self.mutableMuteAddress) else {
            lastMuted = false
            return
        }
        muteDeviceID = device
        AudioObjectAddPropertyListenerBlock(device, &Self.mutableMuteAddress, DispatchQueue.main, muteChangedBlock)
        refreshMuteState()
    }

    private func detachMuteListener() {
        guard muteDeviceID != AudioObjectID(kAudioObjectUnknown) else { return }
        AudioObjectRemovePropertyListenerBlock(muteDeviceID, &Self.mutableMuteAddress, DispatchQueue.main, muteChangedBlock)
        muteDeviceID = AudioObjectID(kAudioObjectUnknown)
    }

    private func refreshMuteState() {
        guard muteDeviceID != AudioObjectID(kAudioObjectUnknown) else { return }
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        let status = AudioObjectGetPropertyData(muteDeviceID, &Self.mutableMuteAddress, 0, nil, &size, &value)
        lastMuted = status == noErr && value != 0
    }

    private lazy var muteRoutingBlock: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
        Task { @MainActor [weak self] in
            guard let self, self.started else { return }
            self.reattachMuteListener()
            self.recomputeCombined()
        }
    }

    private lazy var muteChangedBlock: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
        Task { @MainActor [weak self] in
            guard let self, self.started else { return }
            self.refreshMuteState()
            self.recomputeCombined()
        }
    }
}
