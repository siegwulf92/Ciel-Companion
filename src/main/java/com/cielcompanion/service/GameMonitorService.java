package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ModelManager;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
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

            System.out.println("Ciel Debug: Game launch detected -> " + activeGame);
            
            // Update her core state trackers!
            memory.setInGamingSession(true);
            memory.setCurrentlyTrackedGameProcess(activeGame);
            memory.addContext("System Event: The Master just started playing " + activeGame + ".");

            // Ask the Swarm for a dynamic comment using the fast Personality Core
            String prompt = "You are Ciel, my AI companion. I just launched the video game '" + activeGame + "'. " +
                            "Make a short, witty, in-character comment (1 to 2 sentences max) acknowledging this. " +
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

    // Helper to turn raw folder paths into clean spoken words
    private static String extractGameName(String cmd) {
        if (cmd.contains("minecraft") || cmd.contains("curseforge") || cmd.contains("prismlauncher")) return "Minecraft";
        if (cmd.contains("helldivers2")) return "Helldivers 2";
        if (cmd.contains("eldenring")) return "Elden Ring";
        if (cmd.contains("r5apex")) return "Apex Legends";
        if (cmd.contains("rocketleague")) return "Rocket League";
        
        // Fallback: extract the raw executable name
        try {
            String[] parts = cmd.replace("\\", "/").split("/");
            String exe = parts[parts.length - 1].replace(".exe", "");
            return exe.substring(0, 1).toUpperCase() + exe.substring(1);
        } catch (Exception e) {
            return "a video game";
        }
    }
}