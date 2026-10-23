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
        var remix = GameWindowClassifier.identify(metrics("KINGDOM HEARTS FINAL MIX.exe", "KINGDOM HEARTS -HD 1.5+2.5 ReMIX-"));
        if (remix.isEmpty() || !remix.get().displayName().equals("Kingdom Hearts Final Mix"))
            throw new AssertionError("Remix collection title hid the active Final Mix executable");
        var chain = GameWindowClassifier.identify(metrics("KINGDOM HEARTS Re Chain of Memories.exe", "KINGDOM HEARTS -HD 1.5+2.5 ReMIX-"));
        if (chain.isEmpty() || !chain.get().displayName().equals("Kingdom Hearts Re:Chain of Memories"))
            throw new AssertionError("Remix collection title hid Re:Chain of Memories");
        var kh2 = GameWindowClassifier.identify(metrics("KINGDOM HEARTS II FINAL MIX.exe", "KINGDOM HEARTS -HD 1.5+2.5 ReMIX-"));
        if (kh2.isEmpty() || !kh2.get().displayName().equals("Kingdom Hearts II Final Mix"))
            throw new AssertionError("Remix collection title hid Kingdom Hearts II");
        var days = GameWindowClassifier.identify(metrics("KINGDOM HEARTS 358-2 Days.exe", "KINGDOM HEARTS -HD 1.5+2.5 ReMIX-"));
        if (days.isEmpty() || !days.get().displayName().equals("Kingdom Hearts 358/2 Days"))
            throw new AssertionError("Remix collection title hid 358/2 Days");
        if (GameWindowClassifier.identify(metrics("WaitTitleProject.exe", "KINGDOM HEARTS -HD 1.5+2.5 ReMIX-")).isPresent())
            throw new AssertionError("Remix collection menu became a game");
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
