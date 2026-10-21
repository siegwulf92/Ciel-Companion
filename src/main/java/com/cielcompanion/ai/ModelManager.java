package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.service.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Acts as the Orchestrator for multiple local and cloud LLMs.
 * Routes all requests through the OpenJarvis master node.
 */
public class ModelManager {

    public enum ModelTier {
        PERSONALITY, // Fast chat (GPU)
        EVALUATOR,   // Background observer (CPU -> GPU Dynamic)
        LOGIC,       // Deep D&D reasoning
        LOCAL_LOGIC_FALLBACK, // Deep reasoning (Fallback)
        TRANSLATOR   // Phonetic translation (GPU)
    }

    private static final String JARVIS_URL = "http://localhost:8000/v1/chat/completions";

    public static String getModelName(ModelTier tier) {
        return switch (tier) {
            case PERSONALITY -> Settings.getLlmPersonalityModel();
            case EVALUATOR -> Settings.getLlmEvaluatorModel();
            case LOGIC -> Settings.getLlmLogicModel();
            case LOCAL_LOGIC_FALLBACK -> Settings.getLlmLocalLogicFallbackModel();
            case TRANSLATOR -> Settings.getLlmPersonalityModel();
        };
    }

    public static JsonObject buildPayload(ModelTier tier, String systemContext, String userMessage, boolean stream) {
        JsonObject payload = new JsonObject();
        
        String modelName = getModelName(tier);

        payload.addProperty("model", modelName);
        payload.addProperty("stream", stream);
        
        // Critical: task_intent tells OpenJarvis which probabilistic chain to evaluate.
        String taskIntent = switch (tier) {
            case PERSONALITY -> "Conversational/Contextual Reasoning";
            case EVALUATOR -> "Background Emotion Polling";
            case LOGIC, LOCAL_LOGIC_FALLBACK -> "Conversation Context Analysis";
            case TRANSLATOR -> "Katakana Transliteration";
        };
        payload.addProperty("task_intent", taskIntent);
        
        if (tier == ModelTier.LOGIC || tier == ModelTier.LOCAL_LOGIC_FALLBACK) {
            payload.addProperty("temperature", 0.3);
        } else if (tier == ModelTier.TRANSLATOR) {
            payload.addProperty("temperature", 0.1);
        } else {
            payload.addProperty("temperature", 0.7);
        }

        JsonObject options = new JsonObject();
        
        // CRITICAL FIX: Dynamically apply GPU offloading to ALL tiers (including the Evaluator)
        // unless a heavy game is running, preventing CPU choke timeouts.
        boolean gameRunning = com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().isInGamingSession();
        if (gameRunning) {
            options.addProperty("num_gpu", 0);  
        } else if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            options.addProperty("num_gpu", 99); 
        } else {
            options.addProperty("num_gpu", 99); 
        }

        if (tier == ModelTier.EVALUATOR) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            payload.add("response_format", responseFormat);
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
        return JARVIS_URL;
    }

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

}
