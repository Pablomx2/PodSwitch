import Foundation

/// Schedules a single pending, cancellable action.
@MainActor
protocol DebounceScheduler: AnyObject {
    /// Schedule `action` after `interval`, replacing any still-pending action.
    func schedule(after interval: TimeInterval, _ action: @escaping @MainActor () -> Void)
    /// Cancel the pending action, if any.
    func cancel()
}

/// `DispatchQueue.main`-backed scheduler used in production.
@MainActor
final class MainQueueDebounceScheduler: DebounceScheduler {
    private var workItem: DispatchWorkItem?

    func schedule(after interval: TimeInterval, _ action: @escaping @MainActor () -> Void) {
        workItem?.cancel()
        let item = DispatchWorkItem { action() }
        workItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + interval, execute: item)
    }

    func cancel() {
        workItem?.cancel()
        workItem = nil
    }
}

/// Turns output-device running observations into debounced `onStarted` / `onStopped` signals.
@MainActor
final class PlaybackEdgeDetector {

    /// Emitted when the Mac should (re-)consider grabbing the target.
    var onStarted: (() -> Void)?
    /// Emitted once the debounced quiet period confirms playback stopped.
    var onStopped: (() -> Void)?

    /// Whether the current device is considered playing (post-debounce).
    private(set) var isPlaying = false

    /// Raw last-observed running state of the current output device.
    private var deviceRunning = false
    /// Set while a routing change must be confirmed sustained before a start re-emits.
    private var routingChanged = false

    private let debounceInterval: TimeInterval
    private let sustainInterval: TimeInterval
    private let stopScheduler: DebounceScheduler
    private let sustainScheduler: DebounceScheduler

    init(
        debounceInterval: TimeInterval,
        sustainInterval: TimeInterval,
        stopScheduler: DebounceScheduler,
        sustainScheduler: DebounceScheduler
    ) {
        self.debounceInterval = debounceInterval
        self.sustainInterval = sustainInterval
        self.stopScheduler = stopScheduler
        self.sustainScheduler = sustainScheduler
    }

    /// The running state of the CURRENT default output device changed.
    func runningChanged(_ running: Bool) {
        deviceRunning = running
        if running {
            stopScheduler.cancel()
            if isPlaying {
                if routingChanged {
                    armSustain()
                }
            } else {
                isPlaying = true
                if routingChanged {
                    armSustain()
                } else {
                    onStarted?()
                }
            }
        } else {
            sustainScheduler.cancel()
            guard isPlaying else {
                return
            }
            stopScheduler.schedule(after: debounceInterval) { [weak self] in
                guard let self else { return }
                self.isPlaying = false
                self.routingChanged = false
                self.onStopped?()
            }
        }
    }

    /// The default output device itself changed; re-evaluate routing for the new device.
    func deviceChanged(runningNow: Bool) {
        routingChanged = isPlaying
        runningChanged(runningNow)
    }

    /// Establish the initial state without emitting a start.
    func prime(running: Bool) {
        stopScheduler.cancel()
        sustainScheduler.cancel()
        routingChanged = false
        deviceRunning = running
        isPlaying = running
    }

    /// Return to idle and drop any pending work.
    func reset() {
        stopScheduler.cancel()
        sustainScheduler.cancel()
        routingChanged = false
        deviceRunning = false
        isPlaying = false
    }

    /// Re-emit a start once playback has proven sustained past `sustainInterval`.
    private func armSustain() {
        sustainScheduler.schedule(after: sustainInterval) { [weak self] in
            guard let self, self.deviceRunning else { return }
            self.routingChanged = false
            self.onStarted?()
        }
    }
}
