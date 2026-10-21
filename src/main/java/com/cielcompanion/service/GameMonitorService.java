package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.GameWindowClassifier.Game;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns game sessions from the foreground game's PID through its actual exit. */
public final class GameMonitorService {
    private static ScheduledExecutorService scheduler;
    private static volatile Game activeGame;
    private static long sessionStartTime;
    private static long gameLostTimestamp;
    private static final long GRACE_PERIOD_MS = 30_000;

    private GameMonitorService() {}

    public static synchronized void initialize() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(GameMonitorService::checkGameState, 5, 10, TimeUnit.SECONDS);
        System.out.println("Ciel Debug: GameMonitorService initialized. Watching foreground game windows and tracked process exits.");
    }

    private static void checkGameState() {
        try {
            var metrics = SystemMonitor.getSystemMetrics();
            Optional<Game> foreground = GameWindowClassifier.identify(metrics);
            Game tracked = activeGame;
            if (foreground.isPresent()) {
                Game game = foreground.get();
                if (tracked == null || tracked.pid() != game.pid()) startSession(game);
                else gameLostTimestamp = 0;
                return;
            }
            if (tracked == null) return;
            if (ProcessHandle.of(tracked.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                gameLostTimestamp = 0; // Alt-tab or a transient launcher does not end the game.
                return;
            }
            if (gameLostTimestamp == 0) {
                gameLostTimestamp = System.currentTimeMillis();
                System.out.println("[Game Session] Tracked PID " + tracked.pid() + " exited; 30s close grace started.");
            } else if (System.currentTimeMillis() - gameLostTimestamp >= GRACE_PERIOD_MS) {
                endSession(tracked);
            }
        } catch (Exception error) {
            System.err.println("[Game Session] Poll failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static void startSession(Game game) {
        Game prior = activeGame;
        if (prior != null) {
            // Switching between actual games keeps the router in gaming mode.
            ShortTermMemoryService.getMemory().addContext("System Event: The Master switched from "
                    + prior.displayName() + " to " + game.displayName() + ".");
        }
        activeGame = game;
        sessionStartTime = System.currentTimeMillis();
        gameLostTimestamp = 0;
        ShortTermMemory memory = ShortTermMemoryService.getMemory();
        memory.setInGamingSession(true);
        memory.setCurrentlyTrackedGameProcess(game.process());
        memory.addContext("System Event: The Master started playing " + game.displayName() + ".");
        if (prior == null) AIEngine.setGamingMode(true);
        System.out.println("[Game Session] Started: " + game.displayName() + " (pid=" + game.pid()
                + ", process=" + game.process() + ", title/window verified).");

        String prompt = "Master Taylor just started playing " + game.displayName() + ". "
                + "Foreground executable: " + game.process() + ". "
                + "Give one concise, specific, spoiler-safe sentence as Manas: Ciel. "
                + "Address Master directly and start with one bracketed emotion tag. "
                + "Do not pretend to know gameplay events that were not observed.";
        AIEngine.generateSilentLogicWithModel(prompt, "You are Manas: Ciel.", null, 0.7, "Game Launch Commentary")
                .completeOnTimeout(null, 25, TimeUnit.SECONDS)
                .exceptionally(error -> null)
                .thenAccept(response -> {
                    Game current = activeGame;
                    if (current == null || current.pid() != game.pid()
                            || !ProcessHandle.of(game.pid()).map(ProcessHandle::isAlive).orElse(false)) return;
                    String line = response == null || response.isBlank() || response.contains("ABORT")
                            || response.contains("DATA_DEFICIT")
                            ? "[Observing] Master, " + game.displayName() + " is ready. I will keep the session in view while you play."
                            : response.trim();
                    if (!line.matches("(?s)^\\[[a-zA-Z]+\\].*")) line = "[Observing] " + line;
                    SpeechService.speakPreformatted(line, "game_launch", false, false);
                    System.out.println("[Game Session] Launch commentary queued for pid=" + game.pid()
                            + (line.equals(response) ? " via model." : " via timely fallback."));
                });
    }

    private static void endSession(Game game) {
        if (activeGame == null || activeGame.pid() != game.pid()) return;
        long minutes = Math.max(0, (System.currentTimeMillis() - sessionStartTime) / 60_000);
        System.out.println("[Game Session] Ended: " + game.displayName() + " (" + minutes + "m, pid=" + game.pid() + ").");
        ShortTermMemory memory = ShortTermMemoryService.getMemory();
        memory.addContext("System Event: The Master finished playing " + game.displayName() + " after " + minutes + " minutes.");
        memory.setInGamingSession(false);
        memory.setCurrentlyTrackedGameProcess(null);
        activeGame = null;
        sessionStartTime = 0;
        gameLostTimestamp = 0;
        AIEngine.setGamingMode(false);
    }
}
