package com.cielcompanion.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Acts as the Orchestrator for multiple local and cloud LLMs.
 * Routes all requests through the OpenJarvis master node.
 */
public class ModelManager {

    public enum ModelTier {
        PERSONALITY, // Fast chat (Local Qwen)
        EVALUATOR,   // Background observer/emotions (Local Qwen)
        LOGIC,       // Deep reasoning/Orchestrator (Cloud DeepSeek)
        LOCAL_LOGIC_FALLBACK, // Deep reasoning (Local Phi-4)
        TRANSLATOR   // Phonetic translation (Local Qwen to guarantee zero timeout)
    }

    private static final String JARVIS_URL = "http://localhost:8000/v1/chat/completions";

    public static String getModelName(ModelTier tier) {
        return switch (tier) {
            case PERSONALITY -> "ollama/qwen3:8b";
            case EVALUATOR -> "ollama/qwen3:8b";
            case LOGIC -> "ollama/deepseek-v3.1:671b-cloud"; 
            case LOCAL_LOGIC_FALLBACK -> "openai/phi-4-reasoning-plus"; 
            case TRANSLATOR -> "ollama/qwen3:8b"; // Switched back to local to prevent 15-second cloud timeouts
        };
    }

    public static JsonObject buildPayload(ModelTier tier, String systemContext, String userMessage, boolean stream) {
        JsonObject payload = new JsonObject();
        
        String modelName = getModelName(tier);

        payload.addProperty("model", modelName);
        payload.addProperty("stream", stream);
        
        // Adjust creativity based on task
        if (tier == ModelTier.LOGIC || tier == ModelTier.LOCAL_LOGIC_FALLBACK) {
            payload.addProperty("temperature", 0.3); 
        } else if (tier == ModelTier.TRANSLATOR) {
            payload.addProperty("temperature", 0.1); 
        } else {
            payload.addProperty("temperature", 0.7); 
        }

        // Force CPU isolation for the background observer
        if (tier == ModelTier.EVALUATOR) {
            JsonObject options = new JsonObject();
            options.addProperty("num_gpu", 0); 
            payload.add("options", options);
            
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