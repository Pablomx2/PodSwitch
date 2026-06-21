using PodSwitch.Core;
using Xunit;

namespace PodSwitch.Core.Tests;

public class CoordinatorTests
{
    private const string Target = "aa:bb:cc";

    private sealed class FakeSettings(Config config) : ISettingsStore
    {
        public Config Current = config;
        public Config CurrentConfig() => Current;
    }

    private sealed class FakeConnector : IBluetoothConnector
    {
        public bool Paired = true;
        public bool Active;
        public int ConnectCalls;
        public bool IsPaired(string id) => Paired;
        public bool IsActiveOutput(string id) => Active;
        public void Connect(string id) => ConnectCalls++;
    }

    private sealed class FakeNotifier : INotifier
    {
        public int AskShown;
        public int AskCleared;
        public void ShowAsk() => AskShown++;
        public void ClearAsk() => AskCleared++;
    }

    private static Config Cfg(bool enabled = true, Mode mode = Mode.Steal, string? target = Target)
        => new(enabled, mode, new HashSet<Category> { Category.Media }, target);

    [Fact]
    public void Steal_ConnectsOnAudioStarted()
    {
        var con = new FakeConnector();
        var co = new Coordinator(new FakeSettings(Cfg(mode: Mode.Steal)), con, new FakeNotifier());
        co.Handle(new SwitchEvent.AudioStarted(Category.Media));
        Assert.Equal(1, con.ConnectCalls);
    }

    [Fact]
    public void Disabled_DoesNothing()
    {
        var con = new FakeConnector();
        var no = new FakeNotifier();
        var co = new Coordinator(new FakeSettings(Cfg(enabled: false)), con, no);
        co.Handle(new SwitchEvent.AudioStarted(Category.Media));
        Assert.Equal(0, con.ConnectCalls);
        Assert.Equal(0, no.AskShown);
    }

    [Fact]
    public void Ask_NotifiesOnce_ThenSuppressedWhilePending()
    {
        var con = new FakeConnector();
        var no = new FakeNotifier();
        var co = new Coordinator(new FakeSettings(Cfg(mode: Mode.Ask)), con, no);
        co.Handle(new SwitchEvent.AudioStarted(Category.Media));
        co.Handle(new SwitchEvent.AudioStarted(Category.Media));
        Assert.Equal(1, no.AskShown);
        Assert.Equal(0, con.ConnectCalls);
    }

    [Fact]
    public void Ask_ThenAccept_ClearsPromptAndConnects()
    {
        var con = new FakeConnector();
        var no = new FakeNotifier();
        var co = new Coordinator(new FakeSettings(Cfg(mode: Mode.Ask)), con, no);
        co.Handle(new SwitchEvent.AudioStarted(Category.Media));
        co.Handle(new SwitchEvent.UserAcceptedSwitch());
        Assert.Equal(1, no.AskShown);
        Assert.Equal(1, no.AskCleared);
        Assert.Equal(1, con.ConnectCalls);
    }

    [Fact]
    public void Ask_AudioStopped_ClearsPendingPrompt_ThenNotifiesAgain()
    {
        var con = new FakeConnector();
        var no = new FakeNotifier();
        var co = new Coordinator(new FakeSettings(Cfg(mode: Mode.Ask)), con, no);
        co.Handle(new SwitchEvent.AudioStarted(Category.Media));
        co.Handle(new SwitchEvent.AudioStopped());
        co.Handle(new SwitchEvent.AudioStarted(Category.Media));
        Assert.Equal(2, no.AskShown);
        Assert.True(no.AskCleared >= 1);
    }

    [Fact]
    public void Accept_WhileUnpaired_NoConnect()
    {
        var con = new FakeConnector { Paired = false };
        var co = new Coordinator(new FakeSettings(Cfg(mode: Mode.Ask)), con, new FakeNotifier());
        co.Handle(new SwitchEvent.UserAcceptedSwitch());
        Assert.Equal(0, con.ConnectCalls);
    }

    [Fact]
    public void Steal_AlreadyActive_DoesNotConnect()
    {
        var con = new FakeConnector { Active = true };
        var co = new Coordinator(new FakeSettings(Cfg(mode: Mode.Steal)), con, new FakeNotifier());
        co.Handle(new SwitchEvent.AudioStarted(Category.Media));
        Assert.Equal(0, con.ConnectCalls);
    }
}
