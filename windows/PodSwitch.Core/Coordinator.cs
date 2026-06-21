namespace PodSwitch.Core;

/// <summary>Wires events through <see cref="SwitchEngine.Decide"/> to platform actions.</summary>
public sealed class Coordinator
{
    private readonly ISettingsStore _settings;
    private readonly IBluetoothConnector _connector;
    private readonly INotifier _notifier;
    private bool _notificationPending;

    public Coordinator(ISettingsStore settings, IBluetoothConnector connector, INotifier notifier)
    {
        _settings = settings;
        _connector = connector;
        _notifier = notifier;
    }

    /// <summary>Feed one event through the engine and perform the resulting action.</summary>
    public void Handle(SwitchEvent ev)
    {
        var config = _settings.CurrentConfig();
        var target = config.TargetDeviceId;
        var paired = target is not null && _connector.IsPaired(target);
        var active = target is not null && _connector.IsActiveOutput(target);
        var status = new DeviceStatus(paired, active, _notificationPending);

        switch (SwitchEngine.Decide(ev, config, status))
        {
            case SwitchAction.None:
                break;

            case SwitchAction.Notify:
                _notificationPending = true;
                _notifier.ShowAsk();
                break;

            case SwitchAction.Connect:
                if (_notificationPending)
                {
                    _notificationPending = false;
                    _notifier.ClearAsk();
                }
                if (target is not null) _connector.Connect(target);
                break;
        }

        if (ev is SwitchEvent.AudioStopped && _notificationPending)
        {
            _notificationPending = false;
            _notifier.ClearAsk();
        }
    }
}
