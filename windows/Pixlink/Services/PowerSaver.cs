using System.Runtime.InteropServices;

namespace Pixlink.Services;

/// <summary>
/// Windows "Efficiency mode" (EcoQoS) while the window is hidden or minimized: Windows runs Tethr Link
/// at low priority on its power-saving cores, which matters on laptops. Notifications, messages and
/// calls still arrive straight away; opening the window turns it off again.
/// </summary>
public static class PowerSaver
{
    private static bool _on;

    public static void Set(bool efficient)
    {
        if (efficient == _on) return;
        _on = efficient;
        try
        {
            var state = new PROCESS_POWER_THROTTLING_STATE
            {
                Version = 1,                       // PROCESS_POWER_THROTTLING_CURRENT_VERSION
                ControlMask = 0x1,                 // PROCESS_POWER_THROTTLING_EXECUTION_SPEED
                StateMask = efficient ? 0x1u : 0u, // on = EcoQoS, off = let Windows decide
            };
            var process = GetCurrentProcess();
            SetProcessInformation(process, 4 /* ProcessPowerThrottling */, ref state, Marshal.SizeOf<PROCESS_POWER_THROTTLING_STATE>());
            SetPriorityClass(process, efficient ? 0x40u /* IDLE */ : 0x20u /* NORMAL */);
            // Hand unused memory back while we sit in the tray.
            if (efficient) SetProcessWorkingSetSize(process, -1, -1);
            Log.Info(efficient ? "Efficiency mode on (window hidden)" : "Efficiency mode off");
        }
        catch (Exception ex) { Log.Warn($"Couldn't change Efficiency mode: {ex.Message}"); }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_POWER_THROTTLING_STATE
    {
        public uint Version;
        public uint ControlMask;
        public uint StateMask;
    }

    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetProcessInformation(IntPtr process, int infoClass, ref PROCESS_POWER_THROTTLING_STATE info, int size);
    [DllImport("kernel32.dll")] private static extern bool SetPriorityClass(IntPtr process, uint priorityClass);
    [DllImport("kernel32.dll")] private static extern bool SetProcessWorkingSetSize(IntPtr process, nint min, nint max);
}
