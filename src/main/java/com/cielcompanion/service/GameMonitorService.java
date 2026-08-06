package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ModelManager;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.CielState;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GameMonitorService {
    private static ScheduledExecutorService scheduler;
    private static String activeGame = null;
    private static long sessionStartTime = 0;
    
    private static long gameLostTimestamp = 0; 
    private static final long GRACE_PERIOD_MS = 30000; // 30 seconds

    public static void initialize() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Poll every 15 seconds to catch game launches quickly
        scheduler.scheduleWithFixedDelay(GameMonitorService::checkGameState, 30, 15, TimeUnit.SECONDS);
        System.out.println("Ciel Debug: GameMonitorService initialized. Watching for gaming activity.");
    }

    private static void setPythonGamingMode(boolean isGaming) {
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL("http://localhost:8000/set_gaming_mode");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);
                String payload = "{\"is_gaming\": " + isGaming + "}";
                conn.getOutputStream().write(payload.getBytes());
                int rc = conn.getResponseCode();
                if (rc == 200) {
                    System.out.println("Ciel Debug: Synced gaming mode with Python Swarm -> " + isGaming);
                } else {
                    System.out.println("Ciel Warning: Python Swarm rejected gaming mode sync (HTTP " + rc + ")");
                }
            } catch (Exception e) {
                System.out.println("Ciel Warning: Failed to sync gaming mode with Python Swarm (Connection refused/timeout).");
            }
        });
    }

    private static boolean isBaseEmulatorWindow(String windowTitle) {
        if (windowTitle == null || windowTitle.isBlank()) return true;
        String lower = windowTitle.toLowerCase();
        // Catch PCSX2, RPCS3, Yuzu, Ryujinx base menus before a ROM is loaded
        return lower.matches("^(pcsx2|rpcs3|yuzu|ryujinx).*") && !lower.contains("-");
    }

    private static void checkGameState() {
        String rawCmd = ModelManager.getHeavyGameRunning();

        // Intercept background utilities that the system misclassifies as heavy games
        if (rawCmd != null && isBlacklistedUtility(rawCmd)) {
            rawCmd = null; // Override and ignore
        }

        ShortTermMemory memory = ShortTermMemoryService.getMemory();
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        String windowTitle = metrics.activeWindowTitle();

        // SCENARIO 1: A game is currently running
        if (rawCmd != null) {
            String detectedGame = extractGameName(rawCmd);

            // Prevent false positive commentary when only the emulator base shell has loaded
            if (isBaseEmulatorWindow(windowTitle)) {
                return; // Silently wait for the actual ROM/Game to load the window title
            }

            if (activeGame == null) {
                // A game just launched and no session was active
                activeGame = detectedGame;
                sessionStartTime = System.currentTimeMillis();
                gameLostTimestamp = 0; // Reset the grace period timer

                System.out.println("Ciel Debug: Game launch detected -> " + activeGame + " (Path: " + rawCmd + ", Title: " + windowTitle + ")");
                System.out.println("Ciel Debug: Purging VRAM and locking local inference...");
                
                // Minimize/Hide GUI to save GPU rendering overhead
                // Safe universal AWT method that bypasses direct Class casting
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    if (window.isShowing()) {
                        window.setVisible(false);
                    }
                }
                
                // Instantly notify Python to clear VRAM and lock out local models
                setPythonGamingMode(true);
                
                // Update her core state trackers
                memory.setInGamingSession(true);
                memory.setCurrentlyTrackedGameProcess(activeGame);
                memory.addContext("System Event: The Master just started playing " + activeGame + ".");

                // Ask the Swarm for a dynamic comment using the REAL window title
                String prompt = "You are Ciel, my AI companion. I just launched a video game.\n" +
                                "Process Executable: '" + rawCmd + "'\n" +
                                "Live Window Title: '" + windowTitle + "'\n" +
                                "System's Internal Classification: '" + activeGame + "'\n" +
                                "Use this information to deduce the actual name of the game I am playing. " +
                                "Make a short, witty, in-character comment (1 to 2 sentences max) acknowledging this game. " +
                                "Do not ask questions, just make an observation, offer encouragement, or make a meta-comment.";
                
                AIEngine.generateSilentLogic("[GAME_LAUNCH]", prompt).thenAccept(response -> {
                    if (response != null && !response.isBlank()) {
                        // Pass explicitly as "game_launch" so the SpeechService doesn't suppress it
                        SpeechService.speakPreformatted(response.trim(), "game_launch");
                    }
                });
            } else if (activeGame.equals(detectedGame)) {
                // The game is running, and it's the same one we are tracking.
                // If we were in a grace period, cancel it because the game came back.
                if (gameLostTimestamp > 0) {
                    System.out.println("Ciel Debug: Game " + activeGame + " resumed within grace period. Cancelling closure.");
                    gameLostTimestamp = 0; 
                }
            }
        }
        // SCENARIO 2: No game detected, but we are currently tracking an active session
        else if (activeGame != null) {
            if (gameLostTimestamp == 0) {
                // We JUST lost the game on this polling cycle. Start the grace period.
                gameLostTimestamp = System.currentTimeMillis();
                System.out.println("Ciel Debug: Game process lost. Starting 30s grace period...");
            } else if (System.currentTimeMillis() - gameLostTimestamp >= GRACE_PERIOD_MS) {
                // The grace period has fully expired. Safely end the session.
                long durationMinutes = (System.currentTimeMillis() - sessionStartTime) / 60000;
                System.out.println("Ciel Debug: Grace period expired. Game session ended -> " + activeGame + " (" + durationMinutes + " mins)");
                
                // Notify Python to re-enable local models
                setPythonGamingMode(false);
                
                // Restore GUI windows
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    window.setVisible(true);
                }
                
                // Revert state trackers and log the final playtime
                memory.setInGamingSession(false);
                memory.setCurrentlyTrackedGameProcess(null);
                memory.addContext("System Event: The Master finished playing " + activeGame + " after " + durationMinutes + " minutes.");
                
                activeGame = null;
                sessionStartTime = 0;
                gameLostTimestamp = 0;
            }
        }
    }

    // --- Blacklist Filter ---
    private static boolean isBlacklistedUtility(String cmd) {
        String lower = cmd.toLowerCase();
        // Ignore Razer software (Razer Cortex, Synapse, AppEngine)
        if (lower.contains("razer") || lower.contains("rzsynapse") || lower.contains("rzmonitor")) return true;
        // Ignore other common background/overlay apps
        if (lower.contains("discord") || lower.contains("obs64") || lower.contains("wallpaper")) return true;
        // Ignore storefront launchers
        if (lower.contains("steam.exe") || lower.contains("epicgameslauncher") || lower.contains("goggalaxy")) return true;
        
        return false;
    }

    // Helper to turn raw folder paths into clean spoken words for internal logging
    private static String extractGameName(String cmd) {
        String lowerCmd = cmd.toLowerCase();
        
        // Hardcoded overrides for launchers, Java wrappers, or obscure exes
        if (lowerCmd.contains("minecraft") || lowerCmd.contains("curseforge") || lowerCmd.contains("prismlauncher") || lowerCmd.contains("javaw")) return "Minecraft";
        if (lowerCmd.contains("helldivers2")) return "Helldivers 2";
        if (lowerCmd.contains("eldenring")) return "Elden Ring";
        if (lowerCmd.contains("r5apex")) return "Apex Legends";
        if (lowerCmd.contains("rocketleague")) return "Rocket League";
        if (lowerCmd.contains("brutallegend")) return "Brütal Legend";
        
        try {
            String normalizedCmd = cmd.replace("\\", "/");
            
            // Smart extraction: Look for common storefront folders and grab the game folder name right after it
            String[] commonPaths = {"/steamapps/common/", "/epic games/", "/xboxgames/"};
            for (String cp : commonPaths) {
                int idx = normalizedCmd.toLowerCase().indexOf(cp);
                if (idx != -1) {
                    String tail = normalizedCmd.substring(idx + cp.length());
                    String[] parts = tail.split("/");
                    if (parts.length > 0) {
                        // Return the folder name
                        return parts[0].replace("_", " ").trim(); 
                    }
                }
            }
            
            // Fallback: extract the raw executable name
            String[] parts = normalizedCmd.split("/");
            String exe = parts[parts.length - 1].replace(".exe", "");
            return exe.substring(0, 1).toUpperCase() + exe.substring(1);
        } catch (Exception e) {
            return "a video game";
        }
    }
}