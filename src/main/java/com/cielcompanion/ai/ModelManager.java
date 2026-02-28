package com.cielcompanion.ai;

import com.cielcompanion.service.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Acts as the Orchestrator for multiple local LLMs.
 * Routes requests to the correct model and enforces CPU/GPU isolation.
 */
public class ModelManager {

    public enum ModelTier {
        PERSONALITY, // Fast chat (GPU)
        EVALUATOR,   // Background observer (CPU)
        LOGIC        // Deep D&D reasoning (CPU/LM Studio)
    }

    public static JsonObject buildPayload(ModelTier tier, String systemContext, String userMessage, boolean stream) {
        JsonObject payload = new JsonObject();
        
        String modelName = switch (tier) {
            case PERSONALITY -> Settings.getLlmPersonalityModel();
            case EVALUATOR -> Settings.getLlmEvaluatorModel();
            case LOGIC -> Settings.getLlmLogicModel();
        };

        payload.addProperty("model", modelName);
        payload.addProperty("stream", stream);
        
        // Adjust creativity based on task
        if (tier == ModelTier.LOGIC) {
            payload.addProperty("temperature", 0.3); // High logic, low hallucination
        } else {
            payload.addProperty("temperature", 0.7); // Personality
        }

        // CRITICAL: If this is the background evaluator running on Ollama, force it to use CPU only
        // so it does not interrupt the primary GPU (Gemma/Game).
        if (tier == ModelTier.EVALUATOR) {
            JsonObject options = new JsonObject();
            options.addProperty("num_gpu", 0);
            payload.add("options", options);
            
            // Force JSON output for the evaluator
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            payload.add("response_format", responseFormat);
        }

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
        return switch (tier) {
            case PERSONALITY -> Settings.getLlmPersonalityUrl() + "/chat/completions";
            case EVALUATOR -> Settings.getLlmEvaluatorUrl() + "/chat/completions";
            case LOGIC -> Settings.getLlmLogicUrl() + "/chat/completions";
        };
    }
}