import Foundation
import CoreAudio

/// macOS 14+ process-level "is any other app playing audio output right now?" signal.
/// ORs `kAudioProcessPropertyIsRunningOutput` across audio processes; needs no permission.
@available(macOS 14.0, *)
@MainActor
final class ProcessPlaybackSignal {

    /// Called with the new aggregate whenever "is any other app playing output" flips.
    var onPlayingChanged: ((Bool) -> Void)?

    private let ownPID = getpid()

    private var started = false
    private var lastAggregate = false
    private var observedProcesses: Set<AudioObjectID> = []

    init() {}

    // MARK: - Lifecycle

    func start() {
        guard !started else { return }
        started = true

        var listAddr = Self.address(kAudioHardwarePropertyProcessObjectList)
        AudioObjectAddPropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &listAddr,
            DispatchQueue.main,
            listenerBlock
        )

        rebuildProcessListeners()
        lastAggregate = computeAggregate()
    }

    func stop() {
        guard started else { return }
        started = false

        var listAddr = Self.address(kAudioHardwarePropertyProcessObjectList)
        AudioObjectRemovePropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &listAddr,
            DispatchQueue.main,
            listenerBlock
        )
        for obj in observedProcesses { removeRunningListener(obj) }
        observedProcesses.removeAll()
        lastAggregate = false
    }

    /// Raw, immediate "is any (other) app producing output IO right now?"
    func currentlyPlaying() -> Bool { computeAggregate() }

    // MARK: - Change detection (testable seam)

    /// Apply a freshly-computed aggregate, emitting only on a change.
    func apply(aggregate: Bool) {
        guard aggregate != lastAggregate else { return }
        lastAggregate = aggregate
        onPlayingChanged?(aggregate)
    }

    private func evaluate() {
        guard started else { return }
        rebuildProcessListeners()
        apply(aggregate: computeAggregate())
    }

    // MARK: - CoreAudio listener

    private lazy var listenerBlock: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
        Task { @MainActor [weak self] in self?.evaluate() }
    }

    private func rebuildProcessListeners() {
        let current = Set(processList())
        for obj in current.subtracting(observedProcesses) { addRunningListener(obj) }
        for obj in observedProcesses.subtracting(current) { removeRunningListener(obj) }
        observedProcesses = current
    }

    private func addRunningListener(_ obj: AudioObjectID) {
        var addr = Self.address(kAudioProcessPropertyIsRunning)
        AudioObjectAddPropertyListenerBlock(obj, &addr, DispatchQueue.main, listenerBlock)
    }

    private func removeRunningListener(_ obj: AudioObjectID) {
        var addr = Self.address(kAudioProcessPropertyIsRunning)
        AudioObjectRemovePropertyListenerBlock(obj, &addr, DispatchQueue.main, listenerBlock)
    }

    // MARK: - CoreAudio reads

    private func computeAggregate() -> Bool {
        for obj in processList() where pid(of: obj) != ownPID {
            if isRunningOutput(obj) { return true }
        }
        return false
    }

    private func processList() -> [AudioObjectID] {
        var addr = Self.address(kAudioHardwarePropertyProcessObjectList)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(
            AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size) == noErr,
            size > 0 else { return [] }
        let count = Int(size) / MemoryLayout<AudioObjectID>.stride
        var ids = [AudioObjectID](repeating: 0, count: count)
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids) == noErr
        else { return [] }
        return ids
    }

    private func isRunningOutput(_ obj: AudioObjectID) -> Bool {
        var addr = Self.address(kAudioProcessPropertyIsRunningOutput)
        guard AudioObjectHasProperty(obj, &addr) else { return false }
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(obj, &addr, 0, nil, &size, &value) == noErr else { return false }
        return value != 0
    }

    private func pid(of obj: AudioObjectID) -> pid_t {
        var addr = Self.address(kAudioProcessPropertyPID)
        var value: pid_t = -1
        var size = UInt32(MemoryLayout<pid_t>.size)
        _ = AudioObjectGetPropertyData(obj, &addr, 0, nil, &size, &value)
        return value
    }

    private static func address(_ selector: AudioObjectPropertySelector) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(
            mSelector: selector,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
    }
}
