package com.cielcompanion.service;

import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

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
    private static volatile boolean cpuMetricsUnavailable = false;
    private static volatile boolean processInventoryUnavailable = false;

    private static long virtualIdleStartTime = System.currentTimeMillis();
    private static long lastHardwareIdleMs = 0;

    public static void resetVirtualIdle() {
        virtualIdleStartTime = System.currentTimeMillis();
        lastHardwareIdleMs = WindowsInputService.getIdleTimeMillis();
    }

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
        double cpuLoad = 0.0;
        if (!cpuMetricsUnavailable) {
            try {
                cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevLoadTicks) * 100;
                prevLoadTicks = processor.getSystemCpuLoadTicks();
            } catch (RuntimeException unavailable) {
                cpuMetricsUnavailable = true;
                System.err.println("Ciel Warning: CPU metrics unavailable; using 0% fallback for this session: "
                        + unavailable.getMessage());
            }
        }

        long availableMem = memory.getAvailable();
        long totalMem = memory.getTotal();
        double memUsage = 100.0 * (totalMem - availableMem) / totalMem;

        long hardwareIdleMs = WindowsInputService.getIdleTimeMillis();
        
        if (hardwareIdleMs < lastHardwareIdleMs) {
            if (AzureSpeechService.isSimulatingKeystroke || (System.currentTimeMillis() - AzureSpeechService.lastSimulatedInputTime < 3000)) {
                System.out.println("Ciel Debug: OS Idle timer reset by Ciel's simulated keystroke. Ignoring to preserve true physical idle state.");
            } else {
                virtualIdleStartTime = System.currentTimeMillis() - hardwareIdleMs;
            }
        }
        lastHardwareIdleMs = hardwareIdleMs;
        
        long realIdleMs = System.currentTimeMillis() - virtualIdleStartTime;
        long idleMin = realIdleMs / 60000;

        int activePid = WindowsApiService.INSTANCE.GetForegroundWindow() != null 
            ? getPidFromHwnd(WindowsApiService.INSTANCE.GetForegroundWindow()) 
            : 0;
        
        String activeProcName = activePid > 0 ? WindowsApiService.getProcessName(activePid) : "Unknown";
        String activeTitle = WindowsApiService.getActiveWindowTitle(WindowsApiService.INSTANCE.GetForegroundWindow());

        Set<String> processes;
        if (processInventoryUnavailable) {
            processes = Set.of();
        } else try {
            processes = os.getProcesses().stream()
                .map(p -> p.getName().toLowerCase())
                .collect(Collectors.toSet());
        } catch (RuntimeException unavailable) {
            // OSHI's Windows process-performance provider can be inaccessible
            // to a non-elevated logon task (WMI/CIM denial or missing counters).
            // Process inventory is advisory; it must never stop the main loop
            // or flood the log until the JVM is killed.  Foreground-window and
            // hardware metrics remain usable, and the next refresh retries.
            processInventoryUnavailable = true;
            System.err.println("Ciel Warning: Process inventory unavailable; using an empty process list for this session: "
                    + unavailable.getMessage());
            processes = Set.of();
        }

        boolean isBrowser = activeProcName.matches(Settings.getBrowserProcessesRegex());
        boolean isFullScreen = WindowsApiService.isWindowFullscreen(WindowsApiService.INSTANCE.GetForegroundWindow());
        
        boolean isStreaming = false;
        String streamingRegex = Settings.getStreamingTitleRegex();
        if (streamingRegex != null && !streamingRegex.isBlank()) {
            Pattern p = Pattern.compile(streamingRegex, Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(activeTitle);
            isStreaming = m.find();
        }

        // Fullscreen browsing alone is not evidence of media playback/streaming.

        boolean isMedia = activeProcName.matches(Settings.getPlayerProcessesRegex())
                || activeProcName.toLowerCase(java.util.Locale.ROOT).contains("stremio");
        boolean isMediaPlatform = activeTitle.toLowerCase().matches(".*(youtube|netflix|twitch|crunchyroll|hulu|prime video|disney\\+|max|peacock|paramount\\+|apple tv).*");
        boolean isHardMuted = Settings.getHardMuteProcs().stream().anyMatch(processes::contains);
        boolean isGaming = ShortTermMemoryService.getMemory().isInGamingSession();
        
        // CRITICAL SYNC FIX: Queries HabitTracker to see if the AI classified the background activity as Media
        boolean isHabitMedia = "Media".equals(HabitTrackerService.getCurrentCategory());

        // THE ULTIMATE MEDIA LOCK: If ANY part of the system knows you are watching media, freeze the idle timer.
        if (isMedia || isHabitMedia || ((isStreaming || isMediaPlatform || isHardMuted) && !isGaming)) {
            idleMin = 0;
            virtualIdleStartTime = System.currentTimeMillis(); 
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

    // OSHI 7 renamed this metric; allow validation against either device's
    // current dependency set while the paired deployment is being reconciled.
    private static long residentMemory(OSProcess process) {
        for (String name : new String[]{"getResidentMemory", "getResidentSetSize"}) {
            try { return ((Number) OSProcess.class.getMethod(name).invoke(process)).longValue(); }
            catch (NoSuchMethodException unavailable) { /* Try the supported older API. */ }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("OSHI memory metric failed", error); }
        }
        throw new IllegalStateException("OSHI resident memory metric unavailable");
    }

    public static Optional<ProcessInfo> getTopProcessByMemory() {
        return os.getProcesses(null, (p1, p2) -> Long.compare(residentMemory(p2), residentMemory(p1)), 1).stream()
            .findFirst()
            .map(p -> new ProcessInfo(p.getName(), residentMemory(p) / 1024.0 / 1024.0, p.getProcessID()));
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
