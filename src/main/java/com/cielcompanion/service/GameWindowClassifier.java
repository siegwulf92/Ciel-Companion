package com.cielcompanion.service;

import com.cielcompanion.service.AppProfilerService.AppProfile;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;

import java.util.Locale;
import java.util.Optional;

/** Identifies the game owning the foreground window, never a background storefront. */
final class GameWindowClassifier {
    record Game(int pid, String process, String displayName, String command) {}

    private GameWindowClassifier() {}

    static Optional<Game> identify(SystemMetrics metrics) {
        String process = metrics.activeProcessName() == null ? "" : metrics.activeProcessName().toLowerCase(Locale.ROOT);
        String title = metrics.activeWindowTitle() == null ? "" : metrics.activeWindowTitle().trim();
        int pid;
        try { pid = Integer.parseInt(metrics.activeWindowProcessId()); }
        catch (RuntimeException error) { return Optional.empty(); }
        if (pid <= 0 || excluded(process) || title.equalsIgnoreCase("Program Manager")) return Optional.empty();

        String command = ProcessHandle.of(pid).flatMap(handle -> handle.info().command()).orElse("");
        String lowerCommand = command.toLowerCase(Locale.ROOT).replace('/', '\\');
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        if (isEmptyEmulator(process, lowerTitle)) return Optional.empty();
        String kingdomHeartsGame = kingdomHeartsGame(process);

        boolean gamePath = lowerCommand.contains("\\steamapps\\common\\")
                || lowerCommand.contains("\\epic games\\") || lowerCommand.contains("\\xboxgames\\");
        boolean knownExecutable = kingdomHeartsGame != null || process.matches("(?i).*(-win64-shipping|kingdom.?hearts|khfm|eldenring|helldivers2|r5apex|rocketleague|retroarch|xemu|pcsx2(-qt)?|rpcs3|ryujinx|yuzu|cemu|citra-qt|project64|desmume|duckstation-qt|dolphin|minecraft.windows)\\.exe");
        AppProfile profile = AppProfilerService.identifyActiveApp(process, title);
        boolean profiled = profile != null && "Game".equalsIgnoreCase(profile.category()) && !profile.isLauncher()
                && (process.equalsIgnoreCase(profile.processName()) || gamePath || knownExecutable);
        boolean kingdomHearts = lowerTitle.contains("kingdom hearts") && lowerTitle.contains("final mix")
                && (process.contains("kingdom") || process.contains("khfm") || gamePath);
        boolean minecraft = lowerTitle.contains("minecraft")
                && (process.equals("javaw.exe") || process.contains("minecraft"));
        if (!profiled && !kingdomHearts && !minecraft && !gamePath && !knownExecutable) return Optional.empty();

        String name = kingdomHeartsGame != null ? kingdomHeartsGame
                : kingdomHearts ? "Kingdom Hearts Final Mix"
                : minecraft ? "Minecraft"
                : profiled ? profile.displayName()
                : gamePath ? gameFolder(command)
                : title.isBlank() ? process.replaceFirst("(?i)\\.exe$", "") : title;
        return Optional.of(new Game(pid, process, name, command));
    }

    static boolean excluded(String process) {
        if (process.isBlank() || process.equals("unknown")) return true;
        return process.contains("launcher") || process.contains("overlay") || process.contains("webhelper")
                || process.equals("waittitleproject.exe")
                || process.contains("cefsubprocess") || process.contains("crashreporter")
                || process.contains("setup") || process.contains("installer")
                || process.matches("(?i)(chrome|msedge|firefox|brave|opera|steam|discord|dwm|explorer|searchhost|applicationframehost|nvidia app|obs64|vortex|bg3modmanager|voiceattack|notepad|code|obsidian|chatgpt|handbrake)\\.exe");
    }

    private static String kingdomHeartsGame(String process) {
        if (process.equals("khfm.exe") || process.contains("kingdom hearts final mix"))
            return "Kingdom Hearts Final Mix";
        if (process.contains("kingdom hearts ii final mix") || process.contains("kingdom hearts 2 final mix"))
            return "Kingdom Hearts II Final Mix";
        if (process.contains("kingdom hearts re chain of memories") || process.contains("kingdom hearts re:chain of memories"))
            return "Kingdom Hearts Re:Chain of Memories";
        if (process.contains("kingdom hearts birth by sleep final mix"))
            return "Kingdom Hearts Birth by Sleep Final Mix";
        if (process.contains("kingdom hearts 358") && process.contains("days"))
            return "Kingdom Hearts 358/2 Days";
        return null;
    }

    private static boolean isEmptyEmulator(String process, String title) {
        if (!process.matches("(?i)(pcsx2(-qt)?|rpcs3|ryujinx|yuzu|dolphin|xemu|retroarch)\\.exe")) return false;
        return title.isBlank() || title.matches("(?i)^(pcsx2|rpcs3|ryujinx|yuzu|dolphin|xemu|retroarch)( .*?)?$" );
    }

    private static String gameFolder(String command) {
        String normalized = command.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String marker : new String[]{"/steamapps/common/", "/epic games/", "/xboxgames/"}) {
            int at = lower.indexOf(marker);
            if (at >= 0) {
                String tail = normalized.substring(at + marker.length());
                String folder = tail.split("/", 2)[0].replace('_', ' ').trim();
                if (!folder.isBlank()) return folder;
            }
        }
        String[] parts = normalized.split("/");
        return parts[parts.length - 1].replaceFirst("(?i)\\.exe$", "");
    }
}
