using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using PodSwitch.Core;

namespace PodSwitch.App.Platform;

/// <summary>
/// Connects the target Bluetooth audio device by toggling its audio services and setting it as the
/// default render endpoint. Fire-and-forget: <see cref="Connect"/> returns immediately.
/// </summary>
internal sealed class WindowsBluetoothConnector : IBluetoothConnector
{
    private const int MaxAttempts = 2;
    private const int VerifyTimeoutMs = 8000;
    private const int PollIntervalMs = 400;

    private const uint BLUETOOTH_SERVICE_DISABLE = 0x00;
    private const uint BLUETOOTH_SERVICE_ENABLE = 0x01;
    private const int ToggleGapMs = 1000;
    private const int EnableRetries = 3;
    private const int EnableRetryDelayMs = 1000;

    private static readonly Guid A2dpSink = new("0000110B-0000-1000-8000-00805F9B34FB");
    private static readonly Guid HandsFree = new("0000111E-0000-1000-8000-00805F9B34FB");

    private readonly MMDeviceEnumerator _audio = new();

    // ---- IBluetoothConnector ----

    public bool IsPaired(string targetDeviceId)
        => FindPairedName(Normalize(targetDeviceId)) is not null;

    public bool IsActiveOutput(string targetDeviceId)
    {
        var name = FindPairedName(Normalize(targetDeviceId));
        return name is not null && IsDefaultRenderEndpoint(name);
    }

    public void Connect(string targetDeviceId)
    {
        var normalized = Normalize(targetDeviceId);
        Task.Run(() => ConnectWithVerify(normalized));
    }

    /// <summary>The paired audio devices, for the tray "Target device" picker.</summary>
    public IReadOnlyList<(string Address, string Name)> ListPairedDevices()
    {
        var list = new List<(string, string)>();
        EnumeratePaired(info =>
        {
            list.Add((FormatAddress(info.Address), info.szName));
            return true;
        });
        return list;
    }

    // ---- Connect / verify / retry ----

    private void ConnectWithVerify(string normalizedAddress)
    {
        for (int attempt = 1; attempt <= MaxAttempts; attempt++)
        {
            var name = FindPairedName(normalizedAddress);
            if (name is null) return;
            if (IsDefaultRenderEndpoint(name)) return;

            ToggleAudioServices(normalizedAddress);

            int waited = 0;
            while (waited < VerifyTimeoutMs)
            {
                Thread.Sleep(PollIntervalMs);
                waited += PollIntervalMs;
                TrySetDefaultEndpoint(name);
                if (IsDefaultRenderEndpoint(name)) return;
            }
        }
    }

    /// <summary>Toggles (disable then enable) the device audio services, A2DP sink last.</summary>
    private void ToggleAudioServices(string normalizedAddress)
    {
        BLUETOOTH_DEVICE_INFO? target = null;
        EnumeratePaired(info =>
        {
            if (Normalize(FormatAddress(info.Address)) == normalizedAddress) { target = info; return false; }
            return true;
        });
        if (target is null) return;

        var info2 = target.Value;
        ToggleService(ref info2, HandsFree);
        ToggleService(ref info2, A2dpSink);
    }

    private static void ToggleService(ref BLUETOOTH_DEVICE_INFO info, Guid service)
    {
        var g = service;
        try { BluetoothSetServiceState(IntPtr.Zero, ref info, ref g, BLUETOOTH_SERVICE_DISABLE); }
        catch { }
        Thread.Sleep(ToggleGapMs);

        for (int i = 0; i < EnableRetries; i++)
        {
            uint r;
            try { r = BluetoothSetServiceState(IntPtr.Zero, ref info, ref g, BLUETOOTH_SERVICE_ENABLE); }
            catch { return; }
            if (r == 0) return;
            Thread.Sleep(EnableRetryDelayMs);
        }
    }

    // ---- Paired-device enumeration ----

    private string? FindPairedName(string normalizedAddress)
    {
        string? found = null;
        EnumeratePaired(info =>
        {
            if (Normalize(FormatAddress(info.Address)) == normalizedAddress)
            {
                found = info.szName;
                return false;
            }
            return true;
        });
        return found;
    }

    private static void EnumeratePaired(Func<BLUETOOTH_DEVICE_INFO, bool> onDevice)
    {
        var search = new BLUETOOTH_DEVICE_SEARCH_PARAMS
        {
            dwSize = (uint)Marshal.SizeOf<BLUETOOTH_DEVICE_SEARCH_PARAMS>(),
            fReturnAuthenticated = 1,
            fReturnRemembered = 1,
            fReturnUnknown = 0,
            fReturnConnected = 1,
            fIssueInquiry = 0,
            cTimeoutMultiplier = 0,
            hRadio = IntPtr.Zero,
        };
        var info = new BLUETOOTH_DEVICE_INFO { dwSize = (uint)Marshal.SizeOf<BLUETOOTH_DEVICE_INFO>() };

        IntPtr hFind = BluetoothFindFirstDevice(ref search, ref info);
        if (hFind == IntPtr.Zero) return;
        try
        {
            do
            {
                if (!onDevice(info)) return;
                info = new BLUETOOTH_DEVICE_INFO { dwSize = (uint)Marshal.SizeOf<BLUETOOTH_DEVICE_INFO>() };
            }
            while (BluetoothFindNextDevice(hFind, ref info));
        }
        finally
        {
            BluetoothFindDeviceClose(hFind);
        }
    }

    // ---- Audio endpoint helpers (NAudio + IPolicyConfig) ----

    private bool IsDefaultRenderEndpoint(string deviceName)
    {
        try
        {
            if (!_audio.HasDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia)) return false;
            using var def = _audio.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            return EndpointMatches(def.FriendlyName, deviceName);
        }
        catch
        {
            return false;
        }
    }

    private void TrySetDefaultEndpoint(string deviceName)
    {
        try
        {
            foreach (var dev in _audio.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
            {
                using (dev)
                {
                    if (!EndpointMatches(dev.FriendlyName, deviceName)) continue;
                    if (IsDefaultRenderEndpoint(deviceName)) return;
                    var policy = (IPolicyConfig)new CPolicyConfigClient();
                    try
                    {
                        policy.SetDefaultEndpoint(dev.ID, 0);
                        policy.SetDefaultEndpoint(dev.ID, 1);
                        policy.SetDefaultEndpoint(dev.ID, 2);
                    }
                    finally
                    {
                        Marshal.ReleaseComObject(policy);
                    }
                    return;
                }
            }
        }
        catch
        {
        }
    }

    private static bool EndpointMatches(string endpointFriendlyName, string deviceName)
    {
        if (string.IsNullOrEmpty(endpointFriendlyName) || string.IsNullOrEmpty(deviceName)) return false;
        return endpointFriendlyName.Contains(deviceName, StringComparison.OrdinalIgnoreCase)
            || deviceName.Contains(endpointFriendlyName, StringComparison.OrdinalIgnoreCase);
    }

    // ---- Address helpers ----

    private static string FormatAddress(ulong address)
    {
        var b = new byte[6];
        for (int i = 0; i < 6; i++) b[i] = (byte)((address >> (8 * i)) & 0xFF);
        return $"{b[5]:X2}:{b[4]:X2}:{b[3]:X2}:{b[2]:X2}:{b[1]:X2}:{b[0]:X2}";
    }

    private static string Normalize(string address)
        => address.Replace(":", "").Replace("-", "").Replace(" ", "").ToLowerInvariant();

    // ---- P/Invoke: Win32 Bluetooth API (bthprops.cpl) ----

    [StructLayout(LayoutKind.Sequential)]
    private struct SYSTEMTIME
    {
        public ushort wYear, wMonth, wDayOfWeek, wDay, wHour, wMinute, wSecond, wMilliseconds;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct BLUETOOTH_DEVICE_INFO
    {
        public uint dwSize;
        public ulong Address;
        public uint ulClassofDevice;
        public int fConnected;
        public int fRemembered;
        public int fAuthenticated;
        public SYSTEMTIME stLastSeen;
        public SYSTEMTIME stLastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)]
        public string szName;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BLUETOOTH_DEVICE_SEARCH_PARAMS
    {
        public uint dwSize;
        public int fReturnAuthenticated;
        public int fReturnRemembered;
        public int fReturnUnknown;
        public int fReturnConnected;
        public int fIssueInquiry;
        public byte cTimeoutMultiplier;
        public IntPtr hRadio;
    }

    [DllImport("bthprops.cpl", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr BluetoothFindFirstDevice(ref BLUETOOTH_DEVICE_SEARCH_PARAMS pbtsp, ref BLUETOOTH_DEVICE_INFO pbtdi);

    [DllImport("bthprops.cpl", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool BluetoothFindNextDevice(IntPtr hFind, ref BLUETOOTH_DEVICE_INFO pbtdi);

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern bool BluetoothFindDeviceClose(IntPtr hFind);

    [DllImport("bthprops.cpl", SetLastError = true)]
    private static extern uint BluetoothSetServiceState(IntPtr hRadio, ref BLUETOOTH_DEVICE_INFO pbtdi, ref Guid pGuidService, uint dwServiceFlags);

    // ---- IPolicyConfig (undocumented default-endpoint switching) ----

    [ComImport, Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")]
    private class CPolicyConfigClient { }

    [ComImport, Guid("f8679f50-850a-41cf-9c72-430f290290c8"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPolicyConfig
    {
        [PreserveSig] int GetMixFormat(string pszDeviceName, IntPtr ppFormat);
        [PreserveSig] int GetDeviceFormat(string pszDeviceName, int bDefault, IntPtr ppFormat);
        [PreserveSig] int ResetDeviceFormat(string pszDeviceName);
        [PreserveSig] int SetDeviceFormat(string pszDeviceName, IntPtr pEndpointFormat, IntPtr mixFormat);
        [PreserveSig] int GetProcessingPeriod(string pszDeviceName, int bDefault, IntPtr pmftDefaultPeriod, IntPtr pmftMinimumPeriod);
        [PreserveSig] int SetProcessingPeriod(string pszDeviceName, IntPtr pmftPeriod);
        [PreserveSig] int GetShareMode(string pszDeviceName, IntPtr pMode);
        [PreserveSig] int SetShareMode(string pszDeviceName, IntPtr mode);
        [PreserveSig] int GetPropertyValue(string pszDeviceName, int bFxStore, IntPtr key, IntPtr pv);
        [PreserveSig] int SetPropertyValue(string pszDeviceName, int bFxStore, IntPtr key, IntPtr pv);
        [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string pszDeviceName, int eRole);
        [PreserveSig] int SetEndpointVisibility(string pszDeviceName, int bVisible);
    }
}
