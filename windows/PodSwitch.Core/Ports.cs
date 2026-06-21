namespace PodSwitch.Core;

/// <summary>A source of audio start/stop events. The implementation owns its own debounce.</summary>
public interface IAudioMonitor
{
    /// <summary>Begin emitting events to <paramref name="onEvent"/>.</summary>
    void Start(Action<SwitchEvent> onEvent);

    /// <summary>Stop emitting and release platform resources.</summary>
    void Stop();
}

/// <summary>Connects to / inspects the target Bluetooth audio device.</summary>
public interface IBluetoothConnector
{
    /// <summary>True if the target device is currently paired.</summary>
    bool IsPaired(string targetDeviceId);

    /// <summary>True if the target device is the active audio output right now.</summary>
    bool IsActiveOutput(string targetDeviceId);

    /// <summary>Make the target the active output (fire-and-forget).</summary>
    void Connect(string targetDeviceId);
}

/// <summary>Presents the ASK prompt (no error notifications — connect failures are silent).</summary>
public interface INotifier
{
    void ShowAsk();
    void ClearAsk();
}

/// <summary>Read access to persisted configuration.</summary>
public interface ISettingsStore
{
    Config CurrentConfig();
}
