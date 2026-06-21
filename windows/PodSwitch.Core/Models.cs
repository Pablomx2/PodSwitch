namespace PodSwitch.Core;

/// <summary>Behaviour when triggering audio appears.</summary>
public enum Mode { Steal, Ask }

/// <summary>Audio category that can trigger a switch.</summary>
public enum Category { Media }

/// <summary>Actions emitted by the decision engine.</summary>
public enum SwitchAction { None, Connect, Notify }

/// <summary>User configuration.</summary>
public sealed record Config(
    bool Enabled,
    Mode Mode,
    IReadOnlySet<Category> EnabledCategories,
    string? TargetDeviceId);

/// <summary>Status sampled from the platform at the moment a decision is made.</summary>
public sealed record DeviceStatus(
    bool TargetPaired,
    bool TargetActiveOutput,
    bool NotificationPending);

/// <summary>Events fed into the decision engine.</summary>
public abstract record SwitchEvent
{
    private SwitchEvent() { }

    public sealed record AudioStarted(Category Category) : SwitchEvent;
    public sealed record UserAcceptedSwitch : SwitchEvent;
    public sealed record AudioStopped : SwitchEvent;
}
