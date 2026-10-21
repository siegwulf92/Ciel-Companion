package com.cielcompanion.service;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Psapi;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.io.File;

public class WindowsApiService {

    public interface MyUser32 extends User32 {
        MyUser32 INSTANCE = Native.load("user32", MyUser32.class);
        int GetWindowTextW(WinDef.HWND hWnd, char[] lpString, int nMaxCount);
        int GetWindowTextLengthW(WinDef.HWND hWnd);
        boolean GetLastInputInfo(WinUser.LASTINPUTINFO result);
        WinNT.HANDLE OpenInputDesktop(int flags, boolean inherit, int access);
        boolean GetUserObjectInformationW(WinNT.HANDLE handle, int index, char[] value, int length, IntByReference needed);
        boolean CloseDesktop(WinNT.HANDLE handle);
    }
    
    public static final MyUser32 INSTANCE = MyUser32.INSTANCE;
    private static volatile boolean lastSessionLocked;
    private static long lastDesktopCheckNanos;

    /** The secure Winlogon desktop is visible while the user session is locked. */
    public static synchronized boolean isSessionLocked() {
        long now = System.nanoTime();
        if (now - lastDesktopCheckNanos < 250_000_000L) return lastSessionLocked;
        lastDesktopCheckNanos = now;
        WinNT.HANDLE desktop = INSTANCE.OpenInputDesktop(0, false, 0x0001);
        if (desktop == null) return lastSessionLocked; // Preserve state while Windows switches desktops.
        try {
            char[] name = new char[128];
            if (!INSTANCE.GetUserObjectInformationW(desktop, 2, name, name.length * 2, new IntByReference()))
                return lastSessionLocked;
            lastSessionLocked = !"Default".equalsIgnoreCase(Native.toString(name));
            return lastSessionLocked;
        } finally {
            INSTANCE.CloseDesktop(desktop);
        }
    }

    public static String getProcessName(int pid) {
        WinNT.HANDLE processHandle = Kernel32.INSTANCE.OpenProcess(
            WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ,
            false,
            pid
        );
        if (processHandle == null) {
            return "Unknown";
        }
        try {
            char[] buffer = new char[1024];
            Psapi.INSTANCE.GetModuleFileNameExW(processHandle, null, buffer, buffer.length);
            return new File(Native.toString(buffer)).getName();
        } finally {
            Kernel32.INSTANCE.CloseHandle(processHandle);
        }
    }

    public static boolean isWindowFullscreen(WinDef.HWND hwnd) {
        WinDef.RECT windowRect = new WinDef.RECT();
        User32.INSTANCE.GetWindowRect(hwnd, windowRect);

        WinUser.HMONITOR hMonitor = User32.INSTANCE.MonitorFromWindow(hwnd, User32.MONITOR_DEFAULTTOPRIMARY);
        WinUser.MONITORINFOEX monitorInfo = new WinUser.MONITORINFOEX();
        monitorInfo.cbSize = monitorInfo.size();
        User32.INSTANCE.GetMonitorInfo(hMonitor, monitorInfo);

        return windowRect.left == monitorInfo.rcMonitor.left &&
               windowRect.right == monitorInfo.rcMonitor.right &&
               windowRect.top == monitorInfo.rcMonitor.top &&
               windowRect.bottom == monitorInfo.rcMonitor.bottom;
    }
    
    public static String getActiveWindowTitle(WinDef.HWND hwnd) {
        int len = INSTANCE.GetWindowTextLengthW(hwnd) + 1;
        char[] buffer = new char[len];
        INSTANCE.GetWindowTextW(hwnd, buffer, len);
        return Native.toString(buffer);
    }
    
    public static boolean GetLastInputInfo(WinUser.LASTINPUTINFO result) {
        return MyUser32.INSTANCE.GetLastInputInfo(result);
    }
}
