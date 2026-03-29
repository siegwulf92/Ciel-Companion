package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ModelManager;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GameMonitorService {
    private static ScheduledExecutorService scheduler;
    private static String activeGame = null;
    private static long sessionStartTime = 0;

    public static void initialize() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Poll every 15 seconds to catch game launches quickly
        scheduler.scheduleWithFixedDelay(GameMonitorService::checkGameState, 30, 15, TimeUnit.SECONDS);
        System.out.println("Ciel Debug: GameMonitorService initialized. Watching for gaming activity.");
    }

    private static void checkGameState() {
        String rawCmd = ModelManager.getHeavyGameRunning();
        ShortTermMemory memory = ShortTermMemoryService.getMemory();

        // SCENARIO 1: A game just launched
        if (rawCmd != null && activeGame == null) {
            activeGame = extractGameName(rawCmd);
            sessionStartTime = System.currentTimeMillis();

            // NEW: Grab the live window title to feed to the Swarm!
            SystemMetrics metrics = SystemMonitor.getSystemMetrics();
            String windowTitle = metrics.activeWindowTitle();

            System.out.println("Ciel Debug: Game launch detected -> " + activeGame + " (Path: " + rawCmd + ", Title: " + windowTitle + ")");
            
            // Update her core state trackers!
            memory.setInGamingSession(true);
            memory.setCurrentlyTrackedGameProcess(activeGame);
            memory.addContext("System Event: The Master just started playing " + activeGame + ".");

            // Ask the Swarm for a dynamic comment using BOTH the path, the Window Title, AND her internal guess!
            String prompt = "You are Ciel, my AI companion. I just launched a video game.\n" +
                            "Process Executable: '" + rawCmd + "'\n" +
                            "Live Window Title: '" + windowTitle + "'\n" +
                            "System's Internal Classification: '" + activeGame + "'\n" +
                            "Use this information to deduce the actual name of the game I am playing. " +
                            "Make a short, witty, in-character comment (1 to 2 sentences max) acknowledging this game. " +
                            "Do not ask questions, just make an observation, offer encouragement, or make a meta-comment.";
            
            AIEngine.generateSilentLogic("[GAME_LAUNCH]", prompt).thenAccept(response -> {
                if (response != null && !response.isBlank()) {
                    SpeechService.speakPreformatted(response.trim());
                }
            });
        }
        // SCENARIO 2: The game was closed
        else if (rawCmd == null && activeGame != null) {
            long durationMinutes = (System.currentTimeMillis() - sessionStartTime) / 60000;
            System.out.println("Ciel Debug: Game session ended -> " + activeGame + " (" + durationMinutes + " mins)");
            
            // Revert state trackers and log the final playtime
            memory.setInGamingSession(false);
            memory.setCurrentlyTrackedGameProcess(null);
            memory.addContext("System Event: The Master finished playing " + activeGame + " after " + durationMinutes + " minutes.");
            
            activeGame = null;
            sessionStartTime = 0;
        }
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