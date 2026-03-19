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
        EVALUATOR,   // Background observer/emotions (Local Gemma)
        LOGIC,       // Deep D&D reasoning/Orchestrator (Cloud DeepSeek 671b)
        LOCAL_LOGIC_FALLBACK, // Deep reasoning (Local Phi-4 via LM Studio)
        TRANSLATOR   // Phonetic translation (Cloud Qwen 480b)
    }

    // The universal endpoint for the OpenJarvis Swarm
    private static final String JARVIS_URL = "http://localhost:8000/v1/chat/completions";

    public static String getModelName(ModelTier tier) {
        return switch (tier) {
            case PERSONALITY -> "ollama/qwen3:8b";
            case EVALUATOR -> "ollama/gemma3:12b";
            case LOGIC -> "ollama/deepseek-v3.1:671b-cloud";
            case LOCAL_LOGIC_FALLBACK -> "openai/phi-4-reasoning-plus"; // Routes to LM Studio
            case TRANSLATOR -> "ollama/qwen3-coder:480b-cloud"; // Alibaba's massive model for flawless CJK Katakana
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
            payload.addProperty("temperature", 0.0); // Zero creativity for translations
        } else {
            payload.addProperty("temperature", 0.7); // Personality
        }

        // Force CPU isolation for the background observer so it doesn't interrupt gaming
        if (tier == ModelTier.EVALUATOR) {
            JsonObject options = new JsonObject();
            options.addProperty("num_gpu", 0); // Applies to local Ollama only
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