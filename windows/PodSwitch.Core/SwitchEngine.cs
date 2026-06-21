namespace PodSwitch.Core;

/// <summary>Pure, side-effect-free decision engine mapping a switch event to an action.</summary>
public static class SwitchEngine
{
    public static SwitchAction Decide(SwitchEvent ev, Config config, DeviceStatus status)
    {
        if (!config.Enabled) return SwitchAction.None;
        if (config.TargetDeviceId is null) return SwitchAction.None;

        switch (ev)
        {
            case SwitchEvent.AudioStarted started:
                if (!config.EnabledCategories.Contains(started.Category)) return SwitchAction.None;
                if (!status.TargetPaired) return SwitchAction.None;
                if (status.TargetActiveOutput) return SwitchAction.None;
                return config.Mode switch
                {
                    Mode.Steal => SwitchAction.Connect,
                    Mode.Ask => status.NotificationPending ? SwitchAction.None : SwitchAction.Notify,
                    _ => SwitchAction.None,
                };

            case SwitchEvent.UserAcceptedSwitch:
                if (!status.TargetPaired) return SwitchAction.None;
                if (status.TargetActiveOutput) return SwitchAction.None;
                return SwitchAction.Connect;

            case SwitchEvent.AudioStopped:
                return SwitchAction.None;

            default:
                return SwitchAction.None;
        }
    }
}
