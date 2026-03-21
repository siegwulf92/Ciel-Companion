package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.service.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Acts as the Orchestrator for multiple local LLMs.
 * Routes all requests through the OpenJarvis master node.
 */
public class ModelManager {

    public enum ModelTier {
        PERSONALITY, // Fast chat (GPU)
        EVALUATOR,   // Background observer (CPU)
        LOGIC,       // Deep D&D reasoning (Primary: DeepSeek Cloud)
        LOCAL_LOGIC_FALLBACK, // Deep reasoning (Fallback)
        TRANSLATOR   // Phonetic translation (GPU)
    }

    // The universal endpoint for the OpenJarvis Swarm
    private static final String JARVIS_URL = "http://localhost:8000/v1/chat/completions";

    // Restored dynamic settings linkage
    public static String getModelName(ModelTier tier) {
        return switch (tier) {
            case PERSONALITY -> Settings.getLlmPersonalityModel();
            case EVALUATOR -> Settings.getLlmEvaluatorModel();
            case LOGIC -> Settings.getLlmLogicModel();
            case LOCAL_LOGIC_FALLBACK -> Settings.getLlmLocalLogicFallbackModel();
            case TRANSLATOR -> Settings.getLlmPersonalityModel(); // Reuse fast model for translation
        };
    }

    public static JsonObject buildPayload(ModelTier tier, String systemContext, String userMessage, boolean stream) {
        JsonObject payload = new JsonObject();
        
        String modelName = getModelName(tier);

        payload.addProperty("model", modelName);
        payload.addProperty("stream", stream);
        
        // Adjust creativity based on task
        if (tier == ModelTier.LOGIC || tier == ModelTier.LOCAL_LOGIC_FALLBACK) {
            payload.addProperty("temperature", 0.3); // High logic, low hallucination
        } else if (tier == ModelTier.TRANSLATOR) {
            payload.addProperty("temperature", 0.1); // Strict translation
        } else {
            payload.addProperty("temperature", 0.7); // Personality
        }

        // SMART VRAM ALLOCATION (5070 Ti 16GB)
        JsonObject options = new JsonObject();
        if (tier == ModelTier.EVALUATOR) {
            options.addProperty("num_gpu", 0);
            
            // Force JSON output for the evaluator
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            payload.add("response_format", responseFormat);
        } else {
            String gameCmd = getHeavyGameRunning();
            if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
                options.addProperty("num_gpu", 99); 
            } else if (gameCmd != null) {
                System.out.println("Ciel Debug: Game detected -> " + gameCmd + ". Offloading AI to System RAM.");
                options.addProperty("num_gpu", 0);  
            } else {
                options.addProperty("num_gpu", 99); 
            }
        }
        payload.add("options", options);

        JsonArray messages = new JsonArray();
        
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemContext);
        messages.add(sysMsg);

        JsonObject usrMsg = new JsonObject();
        usrMsg.addProperty("role", "user");
        usrMsg.addProperty("content", userMessage);
        messages.add(usrMsg);

        payload.add("messages", messages);
        return payload;
    }

    public static String getUrlForTier(ModelTier tier) {
        // All tiers now route to the OpenJarvis orchestrator
        return JARVIS_URL;
    }

    /**
     * Utility method to safely extract the standard OpenAI-compatible text response.
     */
    public static String extractMessageContent(String jsonBody) {
        try {
            JsonObject jsonResponse = JsonParser.parseString(jsonBody).getAsJsonObject();
            return jsonResponse.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to parse LLM JSON response.");
            return null;
        }
    }

    private static String getHeavyGameRunning() {
        for (ProcessHandle p : ProcessHandle.allProcesses().toList()) {
            String cmd = p.info().command().orElse("").toLowerCase();
            
            // 1. Check if the process is running from a known game storefront directory
            boolean inGameDir = cmd.contains("steamapps\\common") || 
                                cmd.contains("epic games") || 
                                cmd.contains("xboxgames");
                                
            // 2. Check for specific standalone game executables
            boolean isKnownGameExe = cmd.endsWith("helldivers2.exe") || 
                                     cmd.endsWith("eldenring.exe");

            // If it's not in a game folder and not a known game, skip it quickly
            if (!inGameDir && !isKnownGameExe) {
                continue;
            }

            // 3. COMPREHENSIVE WHITELIST: Ignore background tools, launchers, and helpers
            if (cmd.contains("voiceattack") || 
                cmd.contains("wallpaper") || 
                cmd.contains("soundpad") || 
                cmd.contains("epicgameslauncher") || 
                cmd.contains("epiconlineservices") || // Catches Epic background services
                cmd.contains("epicwebhelper") ||      // Catches Epic Web Helper
                cmd.contains("epic games\\launcher") || // Catches entire Epic Launcher directory
                cmd.contains("unrealcefsubprocess") ||// Catches Epic UI renderer
                cmd.contains("gamingservices") ||
                cmd.contains("steamwebhelper") ||
                cmd.contains("steam.exe") ||
                cmd.contains("steamclient") ||
                cmd.contains("steamservice") ||       // Catches Steam service
                cmd.contains("steamerrorreporter") || // Catches Steam error reporter
                cmd.contains("steamworks shared") ||  // Catches Steam redistributables
                cmd.contains("overlay") || 
                cmd.contains("eadesktop") ||
                cmd.contains("eabackgroundservice") ||// Catches EA App background task
                cmd.contains("battle.net") ||
                cmd.contains("agent.exe") ||          // Catches Blizzard update agent
                cmd.contains("gog galaxy") ||
                cmd.contains("galaxyclient") ||       // Catches GOG background service
                cmd.contains("crashreporter") ||
                cmd.contains("cefsubprocess")) {
                continue; // Safely ignore these background utilities
            }

            // If it survived the whitelist and is in a game directory, assume a heavy game is running
            return cmd;
        }
        return null;
    }
}