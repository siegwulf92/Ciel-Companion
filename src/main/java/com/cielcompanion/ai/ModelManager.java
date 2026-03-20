package com.cielcompanion.ai;

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
        PERSONALITY, 
        EVALUATOR,   
        LOGIC,       
        LOCAL_LOGIC_FALLBACK, 
        TRANSLATOR   
    }

    private static final String JARVIS_URL = "http://localhost:8000/v1/chat/completions";

    public static String getModelName(ModelTier tier) {
        return switch (tier) {
            case PERSONALITY -> Settings.getLlmPersonalityModel();
            case EVALUATOR -> Settings.getLlmEvaluatorModel();
            case LOGIC -> Settings.getLlmLogicModel();
            case LOCAL_LOGIC_FALLBACK -> Settings.getLlmLocalLogicFallbackModel();
            case TRANSLATOR -> Settings.getLlmPersonalityModel(); // Map translator to the fast local personality model
        };
    }

    // Ensures LiteLLM always has a provider prefix (e.g., ollama/, openai/, deepseek/)
    private static String applyProviderPrefix(String modelName) {
        if (modelName != null && !modelName.isBlank() && !modelName.contains("/")) {
            return "ollama/" + modelName;
        }
        return modelName;
    }

    public static JsonObject buildPayload(ModelTier tier, String systemContext, String userMessage, boolean stream) {
        JsonObject payload = new JsonObject();
        
        String rawModelName = getModelName(tier);
        String routedModelName = applyProviderPrefix(rawModelName);

        payload.addProperty("model", routedModelName);
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
            
            // Standard JSON enforcement compatible with both Ollama and OpenAI specs
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