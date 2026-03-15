package com.cielcompanion.ai;

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
        LOCAL_LOGIC_FALLBACK // Deep reasoning (Fallback)
    }

    // The universal endpoint for the OpenJarvis Swarm
    private static final String JARVIS_URL = "http://localhost:8000/v1/chat/completions";

    public static JsonObject buildPayload(ModelTier tier, String systemContext, String userMessage, boolean stream) {
        JsonObject payload = new JsonObject();
        
        // Define the exact models available in your OpenJarvis/Ollama ecosystem
        String modelName = switch (tier) {
            case PERSONALITY -> "qwen3:8b";
            case EVALUATOR -> "qwen3:8b";
            case LOGIC -> "deepseek-v3:671b-cloud";
            case LOCAL_LOGIC_FALLBACK -> "deepseek-v3:671b-cloud";
        };

        payload.addProperty("model", modelName);
        payload.addProperty("stream", stream);
        
        // Adjust creativity based on task
        if (tier == ModelTier.LOGIC || tier == ModelTier.LOCAL_LOGIC_FALLBACK) {
            payload.addProperty("temperature", 0.3); // High logic, low hallucination
        } else {
            payload.addProperty("temperature", 0.7); // Personality
        }

        // Force CPU isolation for the background observer so it doesn't interrupt gaming
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
}