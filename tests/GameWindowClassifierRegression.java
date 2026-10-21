package com.cielcompanion.service;

import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import java.util.Set;

public class GameWindowClassifierRegression {
    private static SystemMetrics metrics(String process, String title) {
        return new SystemMetrics(5, 25, 0, Long.toString(ProcessHandle.current().pid()),
                title, process, false, false, true, false, false, Set.of(process));
    }

    public static void main(String[] args) {
        AppProfilerService.initialize();
        var kh = GameWindowClassifier.identify(metrics("KINGDOM HEARTS FINAL MIX.exe", "KINGDOM HEARTS FINAL MIX"));
        if (kh.isEmpty() || !kh.get().displayName().equals("Kingdom Hearts Final Mix"))
            throw new AssertionError("Foreground KH Final Mix was not recognized");
        if (GameWindowClassifier.identify(metrics("artcraft-launcher.exe", "KINGDOM HEARTS FINAL MIX")).isPresent())
            throw new AssertionError("Launcher became a game");
        if (GameWindowClassifier.identify(metrics("nvidia overlay.exe", "KINGDOM HEARTS FINAL MIX")).isPresent())
            throw new AssertionError("Overlay became a game");
        if (GameWindowClassifier.identify(metrics("notepad.exe", "KINGDOM HEARTS FINAL MIX notes")).isPresent())
            throw new AssertionError("A document title became a game");
        if (GameWindowClassifier.identify(metrics("chrome.exe", "Kingdom Hearts Final Mix - YouTube")).isPresent())
            throw new AssertionError("A browser title became a game");
        System.out.println("Game window regressions passed: KH detected; launcher, overlay, notes and browser ignored.");
    }
}
