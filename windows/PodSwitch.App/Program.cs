namespace PodSwitch.App;

internal static class Program
{
    // Stable name enforcing a single tray instance.
    private const string MutexName = "PodSwitch.SingleInstance.6f1c2e54-0a2b-4f3a-9d1e-7c2b8a55d9f0";

    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(initiallyOwned: true, MutexName, out bool isNewInstance);
        if (!isNewInstance) return;

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp());

        GC.KeepAlive(mutex);
    }
}
