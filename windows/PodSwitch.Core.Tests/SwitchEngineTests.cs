using PodSwitch.Core;
using Xunit;

namespace PodSwitch.Core.Tests;

public class SwitchEngineTests
{
    private static Config Cfg(bool enabled = true, Mode mode = Mode.Steal, string? target = "aa:bb:cc",
        IReadOnlySet<Category>? cats = null)
        => new(enabled, mode, cats ?? new HashSet<Category> { Category.Media }, target);

    private static DeviceStatus St(bool paired = true, bool active = false, bool pending = false)
        => new(paired, active, pending);

    [Fact]
    public void Disabled_ReturnsNone_ForEveryEvent()
    {
        var c = Cfg(enabled: false);
        Assert.Equal(SwitchAction.None, SwitchEngine.Decide(new SwitchEvent.AudioStarted(Category.Media), c, St()));
        Assert.Equal(SwitchAction.None, SwitchEngine.Decide(new SwitchEvent.UserAcceptedSwitch(), c, St()));
        Assert.Equal(SwitchAction.None, SwitchEngine.Decide(new SwitchEvent.AudioStopped(), c, St()));
    }

    [Fact]
    public void NullTarget_ReturnsNone()
    {
        var c = Cfg(target: null);
        Assert.Equal(SwitchAction.None, SwitchEngine.Decide(new SwitchEvent.AudioStarted(Category.Media), c, St()));
        Assert.Equal(SwitchAction.None, SwitchEngine.Decide(new SwitchEvent.UserAcceptedSwitch(), c, St()));
        Assert.Equal(SwitchAction.None, SwitchEngine.Decide(new SwitchEvent.AudioStopped(), c, St()));
    }

    [Fact]
    public void AudioStarted_CategoryNotEnabled_ReturnsNone()
    {
        var c = Cfg(cats: new HashSet<Category>());
        Assert.Equal(SwitchAction.None, SwitchEngine.Decide(new SwitchEvent.AudioStarted(Category.Media), c, St()));
    }

    [Theory]
    [InlineData(Mode.Steal, true, false, false, SwitchAction.Connect)]
    [InlineData(Mode.Steal, true, false, true, SwitchAction.Connect)]
    [InlineData(Mode.Steal, true, true, false, SwitchAction.None)]
    [InlineData(Mode.Steal, true, true, true, SwitchAction.None)]
    [InlineData(Mode.Steal, false, false, false, SwitchAction.None)]
    [InlineData(Mode.Steal, false, true, false, SwitchAction.None)]
    [InlineData(Mode.Ask, true, false, false, SwitchAction.Notify)]
    [InlineData(Mode.Ask, true, false, true, SwitchAction.None)]
    [InlineData(Mode.Ask, true, true, false, SwitchAction.None)]
    [InlineData(Mode.Ask, true, true, true, SwitchAction.None)]
    [InlineData(Mode.Ask, false, false, false, SwitchAction.None)]
    [InlineData(Mode.Ask, false, false, true, SwitchAction.None)]
    public void AudioStarted_TruthTable(Mode mode, bool paired, bool active, bool pending, SwitchAction expected)
    {
        var s = St(paired: paired, active: active, pending: pending);
        Assert.Equal(expected, SwitchEngine.Decide(new SwitchEvent.AudioStarted(Category.Media), Cfg(mode: mode), s));
    }

    [Theory]
    [InlineData(true, false, SwitchAction.Connect)]
    [InlineData(true, true, SwitchAction.None)]
    [InlineData(false, false, SwitchAction.None)]
    [InlineData(false, true, SwitchAction.None)]
    public void UserAccepted_TruthTable(bool paired, bool active, SwitchAction expected)
    {
        foreach (var pending in new[] { true, false })
        {
            var s = St(paired: paired, active: active, pending: pending);
            Assert.Equal(expected, SwitchEngine.Decide(new SwitchEvent.UserAcceptedSwitch(), Cfg(mode: Mode.Ask), s));
        }
    }

    [Fact]
    public void AudioStopped_AlwaysNone()
    {
        foreach (var mode in new[] { Mode.Steal, Mode.Ask })
        foreach (var paired in new[] { true, false })
        foreach (var active in new[] { true, false })
        foreach (var pending in new[] { true, false })
        {
            var s = St(paired, active, pending);
            Assert.Equal(SwitchAction.None, SwitchEngine.Decide(new SwitchEvent.AudioStopped(), Cfg(mode: mode), s));
        }
    }
}
