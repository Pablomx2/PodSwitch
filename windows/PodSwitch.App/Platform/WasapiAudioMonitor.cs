using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;
using PodSwitch.Core;

namespace PodSwitch.App.Platform;

/// <summary>Detects "any audio playing" on the default render device via WASAPI audio-session events.</summary>
internal sealed class WasapiAudioMonitor : IAudioMonitor, IMMNotificationClient
{
    private const int StopDebounceMs = 750;

    private readonly MMDeviceEnumerator _enumerator = new();
    private readonly object _gate = new();
    private readonly List<SessionHandler> _handlers = new();

    private SynchronizationContext? _ctx;
    private Action<SwitchEvent>? _onEvent;
    private MMDevice? _device;
    private AudioSessionManager? _sessionManager;
    private System.Threading.Timer? _stopTimer;
    private bool _isPlaying;
    private bool _started;

    public void Start(Action<SwitchEvent> onEvent)
    {
        lock (_gate)
        {
            if (_started) return;
            _started = true;
            _onEvent = onEvent;
            _ctx = SynchronizationContext.Current
                ?? throw new InvalidOperationException(
                    "WasapiAudioMonitor.Start must run on a thread with a WinForms SynchronizationContext.");
            try { _enumerator.RegisterEndpointNotificationCallback(this); } catch { }
            AttachToDefaultDevice();
            _isPlaying = AnySessionActive();
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            if (!_started) return;
            _started = false;
            _stopTimer?.Dispose();
            _stopTimer = null;
            DetachSessions();
            try { _enumerator.UnregisterEndpointNotificationCallback(this); } catch { }
            _onEvent = null;
        }
    }

    private void AttachToDefaultDevice()
    {
        DetachSessions();
        try
        {
            if (!_enumerator.HasDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia)) return;
            _device = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            _sessionManager = _device.AudioSessionManager;
            _sessionManager.OnSessionCreated += OnSessionCreated;

            var sessions = _sessionManager.Sessions;
            for (int i = 0; i < sessions.Count; i++)
            {
                var handler = new SessionHandler(this);
                sessions[i].RegisterEventClient(handler);
                _handlers.Add(handler);
            }
        }
        catch
        {
        }
    }

    private void DetachSessions()
    {
        _handlers.Clear();
        if (_sessionManager is not null) _sessionManager.OnSessionCreated -= OnSessionCreated;
        _sessionManager = null;
        try { _device?.Dispose(); } catch { }
        _device = null;
    }

    private void OnSessionCreated(object? sender, IAudioSessionControl newSession)
    {
        // Fires on the session manager's COM thread; re-attaching here is a re-entrancy hazard, so marshal it off.
        var ctx = _ctx;
        if (ctx is null) return;
        ctx.Post(_ =>
        {
            lock (_gate)
            {
                if (!_started) return;
                AttachToDefaultDevice();
            }
            Reevaluate();
        }, null);
    }

    /// <summary>Recompute aggregate playing state from the live sessions.</summary>
    internal void Reevaluate()
    {
        bool active;
        lock (_gate)
        {
            if (!_started) return;
            active = AnySessionActive();
        }
        UpdatePlaying(active);
    }

    private bool AnySessionActive()
    {
        try
        {
            var sessions = _sessionManager?.Sessions;
            if (sessions is null) return false;
            for (int i = 0; i < sessions.Count; i++)
            {
                if (sessions[i].State == AudioSessionState.AudioSessionStateActive) return true;
            }
        }
        catch
        {
        }
        return false;
    }

    private void UpdatePlaying(bool playing)
    {
        lock (_gate)
        {
            if (!_started) return;
            if (playing)
            {
                _stopTimer?.Dispose();
                _stopTimer = null;
                if (_isPlaying) return;
                _isPlaying = true;
                Emit(new SwitchEvent.AudioStarted(Category.Media));
            }
            else
            {
                if (!_isPlaying) return;
                _stopTimer?.Dispose();
                _stopTimer = new System.Threading.Timer(_ =>
                {
                    lock (_gate)
                    {
                        if (!_isPlaying) return;
                        _isPlaying = false;
                        Emit(new SwitchEvent.AudioStopped());
                    }
                }, null, StopDebounceMs, Timeout.Infinite);
            }
        }
    }

    private void Emit(SwitchEvent ev)
    {
        var ctx = _ctx;
        var cb = _onEvent;
        if (ctx is null || cb is null) return;
        ctx.Post(_ => cb(ev), null);
    }

    // ---- IMMNotificationClient ----
    public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId)
    {
        if (flow != DataFlow.Render || role != Role.Multimedia) return;
        lock (_gate)
        {
            if (!_started) return;
            AttachToDefaultDevice();
        }
        Reevaluate();
    }

    public void OnDeviceStateChanged(string deviceId, DeviceState newState) { }
    public void OnDeviceAdded(string pwstrDeviceId) { }
    public void OnDeviceRemoved(string deviceId) { }
    public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key) { }

    private sealed class SessionHandler(WasapiAudioMonitor owner) : IAudioSessionEventsHandler
    {
        public void OnStateChanged(AudioSessionState state) => owner.Reevaluate();
        public void OnSessionDisconnected(AudioSessionDisconnectReason disconnectReason) => owner.Reevaluate();
        public void OnVolumeChanged(float volume, bool isMuted) { }
        public void OnDisplayNameChanged(string displayName) { }
        public void OnIconPathChanged(string iconPath) { }
        public void OnChannelVolumeChanged(uint channelCount, IntPtr newVolumes, uint channelIndex) { }
        public void OnGroupingParamChanged(ref Guid groupingId) { }
    }
}
