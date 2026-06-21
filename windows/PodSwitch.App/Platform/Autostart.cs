using Microsoft.Win32;

namespace PodSwitch.App.Platform;

/// <summary>Start-at-login via the per-user Run registry key (no admin rights needed).</summary>
internal static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "PodSwitch";

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
            var value = key?.GetValue(ValueName) as string;
            return !string.IsNullOrEmpty(value);
        }
        catch
        {
            return false;
        }
    }

    public static void SetEnabled(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (key is null) return;
            if (enabled)
            {
                var exe = Environment.ProcessPath;
                if (!string.IsNullOrEmpty(exe)) key.SetValue(ValueName, $"\"{exe}\"");
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
        catch
        {
            // Ignore registry failures (e.g. locked-down policies).
        }
    }
}
