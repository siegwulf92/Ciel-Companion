package com.cielcompanion.service;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SystemMonitor {

    private static final SystemInfo systemInfo = new SystemInfo();
    private static final CentralProcessor processor = systemInfo.getHardware().getProcessor();
    private static final GlobalMemory memory = systemInfo.getHardware().getMemory();
    private static final OperatingSystem os = systemInfo.getOperatingSystem();
    
    private static long[] prevLoadTicks = new long[CentralProcessor.TickType.values().length];

    public record SystemMetrics(
        double cpuLoadPercent,
        double memoryUsagePercent,
        long idleTimeMinutes,
        String activeWindowProcessId,
        String activeWindowTitle,
        String activeProcessName,
        boolean isStreaming,
        boolean isPlayingMedia,
        boolean isInFullScreen,
        boolean isBrowserActive,
        boolean isHardMuted,
        Set<String> runningProcesses
    ) {}

    public record ProcessInfo(String name, double usage, int pid) {}

    public static SystemMetrics getSystemMetrics() {
        // CPU Load
        double cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevLoadTicks) * 100;
        prevLoadTicks = processor.getSystemCpuLoadTicks();

        // Memory
        long availableMem = memory.getAvailable();
        long totalMem = memory.getTotal();
        double memUsage = 100.0 * (totalMem - availableMem) / totalMem;

        // Idle Time
        long idleMs = WindowsInputService.getIdleTimeMillis();
        long idleMin = idleMs / 60000;

        // Active Window & Process
        int activePid = WindowsApiService.INSTANCE.GetForegroundWindow() != null 
            ? getPidFromHwnd(WindowsApiService.INSTANCE.GetForegroundWindow()) 
            : 0;
        
        String activeProcName = activePid > 0 ? WindowsApiService.getProcessName(activePid) : "Unknown";
        String activeTitle = WindowsApiService.getActiveWindowTitle(WindowsApiService.INSTANCE.GetForegroundWindow());

        // Process List
        Set<String> processes = os.getProcesses().stream()
            .map(p -> p.getName().toLowerCase())
            .collect(Collectors.toSet());

        // --- Improved Heuristics ---
        boolean isBrowser = activeProcName.matches(Settings.getBrowserProcessesRegex());
        boolean isFullScreen = WindowsApiService.isWindowFullscreen(WindowsApiService.INSTANCE.GetForegroundWindow());
        
        // 1. Streaming Detection (Use find() for partial matches like "Video - YouTube")
        boolean isStreaming = false;
        String streamingRegex = Settings.getStreamingTitleRegex();
        if (streamingRegex != null && !streamingRegex.isBlank()) {
            Pattern p = Pattern.compile(streamingRegex, Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(activeTitle);
            isStreaming = m.find();
        }

        // 2. Fullscreen Browser Rule (Catch-all for unlisted streaming sites)
        if (isBrowser && isFullScreen) {
            isStreaming = true;
        }

        boolean isMedia = activeProcName.matches(Settings.getPlayerProcessesRegex());
        boolean isHardMuted = Settings.getHardMuteProcs().stream().anyMatch(processes::contains);

        // 3. Force "Active" State if Media is Playing
        // This resets Ciel's boredom timer so she doesn't interrupt movies
        if (isStreaming || isMedia || isHardMuted) {
            idleMin = 0;
        }

        return new SystemMetrics(
            cpuLoad, memUsage, idleMin, String.valueOf(activePid), activeTitle, activeProcName,
            isStreaming, isMedia, isFullScreen, isBrowser, isHardMuted, processes
        );
    }

    private static int getPidFromHwnd(com.sun.jna.platform.win32.WinDef.HWND hwnd) {
        com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
        WindowsApiService.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        return pid.getValue();
    }

    public static Optional<ProcessInfo> getTopProcessByMemory() {
        return os.getProcesses(null, (p1, p2) -> Long.compare(p2.getResidentSetSize(), p1.getResidentSetSize()), 1).stream()
            .findFirst()
            .map(p -> new ProcessInfo(p.getName(), p.getResidentSetSize() / 1024.0 / 1024.0, p.getProcessID()));
    }

    public static Optional<ProcessInfo> getTopProcessByCpu() {
        return os.getProcesses(null, (p1, p2) -> Double.compare(p2.getProcessCpuLoadCumulative(), p1.getProcessCpuLoadCumulative()), 1).stream()
            .findFirst()
            .map(p -> new ProcessInfo(p.getName(), p.getProcessCpuLoadCumulative() * 100d, p.getProcessID()));
    }

    public static boolean isProcessUsingNetwork(String processNamePartial, long thresholdBytesPerSec) {
        String lowerName = processNamePartial.toLowerCase();
        
        List<OSProcess> targetsA = os.getProcesses().stream()
            .filter(p -> p.getName().toLowerCase().contains(lowerName))
            .collect(Collectors.toList());
        
        if (targetsA.isEmpty()) return false;

        long totalBytesA = targetsA.stream().mapToLong(p -> p.getBytesRead() + p.getBytesWritten()).sum();

        try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }

        List<OSProcess> targetsB = os.getProcesses().stream()
            .filter(p -> p.getName().toLowerCase().contains(lowerName))
            .collect(Collectors.toList());

        long totalBytesB = targetsB.stream().mapToLong(p -> p.getBytesRead() + p.getBytesWritten()).sum();

        long delta = totalBytesB - totalBytesA;
        return delta > thresholdBytesPerSec;
    }
}