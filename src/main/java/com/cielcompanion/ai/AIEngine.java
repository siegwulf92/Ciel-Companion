package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIEngine {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Gson gson = new Gson();
    
    private static final Pattern EMOTION_TAG_PATTERN = Pattern.compile("^\\[(.*?)\\]\\s*");
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("(?s)<think>.*?</think>");

    // --- NEW: Multi-turn Conversation Memory ---
    private static final LinkedList<JsonObject> conversationHistory = new LinkedList<>();
    private static final int MAX_HISTORY = 10; // Keep the last 10 messages (5 exchanges)

    private static synchronized void addHistory(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        conversationHistory.add(msg);
        if (conversationHistory.size() > MAX_HISTORY) {
            conversationHistory.removeFirst();
        }
    }
    // ------------------------------------------

    /**
     * Tier 1: PERSONALITY (Fast, GPU, Streamed)
     */
    public static void chatFast(String userMessage, String systemContext, Runnable onComplete) {
        System.out.println("Ciel Debug: Routing to Personality Core (Streamed)...");
        
        addHistory("user", userMessage);
        
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.PERSONALITY);
        JsonObject payload = buildPayloadWithHistory(ModelManager.ModelTier.PERSONALITY, systemContext, true);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        AtomicBoolean isFallbackTriggered = new AtomicBoolean(false);

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        triggerFallback(userMessage, systemContext, onComplete);
                        isFallbackTriggered.set(true);
                        return;
                    }

                    StringBuilder sentenceBuffer = new StringBuilder();
                    StringBuilder fullResponseBuffer = new StringBuilder(); // To save to history

                    response.body().forEach(line -> {
                        if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                            try {
                                JsonObject chunk = JsonParser.parseString(line.substring(6)).getAsJsonObject();
                                JsonArray choices = chunk.getAsJsonArray("choices");
                                if (choices.size() > 0) {
                                    JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                                    if (delta.has("content")) {
                                        String textDelta = delta.get("content").getAsString();
                                        sentenceBuffer.append(textDelta);
                                        fullResponseBuffer.append(textDelta);

                                        if (isSentenceBoundary(sentenceBuffer.toString())) {
                                            processAndSpeakChunk(sentenceBuffer.toString());
                                            sentenceBuffer.setLength(0);
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                    
                    if (sentenceBuffer.length() > 0 && sentenceBuffer.toString().trim().length() > 0) {
                        processAndSpeakChunk(sentenceBuffer.toString());
                    }
                    
                    addHistory("assistant", fullResponseBuffer.toString());
                })
                .exceptionally(e -> {
                    if (!isFallbackTriggered.get()) triggerFallback(userMessage, systemContext, onComplete);
                    return null;
                })
                .thenRun(() -> {
                    if (!isFallbackTriggered.get() && onComplete != null) onComplete.run();
                });
    }

    /**
     * Tier 2: EVALUATOR (Background, CPU, JSON)
     * Does NOT use conversation history to prevent context pollution.
     */
    public static CompletableFuture<JsonObject> evaluateBackground(String transcriptBuffer, String systemContext) {
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.EVALUATOR);
        JsonObject payload = ModelManager.buildPayload(ModelManager.ModelTier.EVALUATOR, systemContext, "TRANSCRIPT:\n" + transcriptBuffer, false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                        String content = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString();
                        return JsonParser.parseString(content).getAsJsonObject();
                    }
                    return null;
                });
    }

    /**
     * Tier 3: LOGIC CORE (Deep Reasoning, LM Studio Phi-4, Non-Streamed)
     */
    public static void reasonDeeply(String userMessage, String systemContext, Runnable onComplete) {
        System.out.println("Ciel Debug: Routing to Logic Core (Phi-4 Reasoning)...");
        SpeechService.speakPreformatted("[Focused] Initiating deep cognitive analysis. Please stand by.");

        addHistory("user", userMessage);

        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.LOGIC);
        JsonObject payload = buildPayloadWithHistory(ModelManager.ModelTier.LOGIC, systemContext, false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                        String rawContent = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString();
                        
                        String cleanContent = THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim();
                        
                        String[] sentences = cleanContent.split("(?<=[.!?])\\s+");
                        for (String s : sentences) {
                            processAndSpeakChunk(s);
                        }
                        
                        addHistory("assistant", cleanContent);
                    } else {
                        SpeechService.speakPreformatted("[Annoyed] Logic core returned an anomaly. Routing to fallback.");
                        triggerFallback(userMessage, systemContext, null);
                    }
                })
                .exceptionally(e -> {
                    System.err.println("Ciel AI Error: Logic core timeout. Ensure LM Studio is running.");
                    triggerFallback(userMessage, systemContext, null);
                    return null;
                })
                .thenRun(() -> {
                    if (onComplete != null) onComplete.run();
                });
    }

    private static void triggerFallback(String userMessage, String systemContext, Runnable onComplete) {
        String key = Settings.getLlmOnlineFallbackKey();
        if (key == null || key.isBlank()) {
            SpeechService.speakPreformatted("[Glitched] My cognitive matrix is offline. I cannot process that request.");
            if (onComplete != null) onComplete.run();
            return;
        }
        
        JsonObject payload = buildPayloadWithHistory(ModelManager.ModelTier.PERSONALITY, systemContext, false);
        payload.addProperty("model", "gpt-4o-mini"); 
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Settings.getLlmOnlineFallbackUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                        String content = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString();
                        
                        for (String s : content.split("(?<=[.!?])\\s+")) processAndSpeakChunk(s);
                        addHistory("assistant", content);
                    } else {
                        SpeechService.speakPreformatted("[Glitched] Fallback cognitive matrix also unavailable.");
                    }
                })
                .thenRun(() -> {
                    if (onComplete != null) onComplete.run();
                });
    }

    /**
     * Replaces the single-message payload builder for fast/logic chat to inject rolling memory.
     */
    private static JsonObject buildPayloadWithHistory(ModelManager.ModelTier tier, String systemContext, boolean stream) {
        JsonObject payload = new JsonObject();
        
        String modelName = switch (tier) {
            case PERSONALITY -> Settings.getLlmPersonalityModel();
            case EVALUATOR -> Settings.getLlmEvaluatorModel();
            case LOGIC -> Settings.getLlmLogicModel();
        };

        payload.addProperty("model", modelName);
        payload.addProperty("stream", stream);
        payload.addProperty("temperature", tier == ModelManager.ModelTier.LOGIC ? 0.3 : 0.7);

        JsonArray messages = new JsonArray();
        
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemContext);
        messages.add(sysMsg);

        // Inject rolling history
        synchronized (conversationHistory) {
            for (JsonObject historicMsg : conversationHistory) {
                messages.add(historicMsg);
            }
        }

        payload.add("messages", messages);
        return payload;
    }

    private static boolean isSentenceBoundary(String text) {
        String t = text.trim();
        return t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith("\n");
    }

    private static void processAndSpeakChunk(String chunk) {
        String cleanText = chunk.trim();
        if (cleanText.isEmpty()) return;

        Matcher matcher = EMOTION_TAG_PATTERN.matcher(cleanText);
        String emotionToTrigger = null;

        if (matcher.find()) {
            emotionToTrigger = matcher.group(1);
            cleanText = matcher.replaceFirst("").trim();
        }

        // Trigger emotion visually if found, and clear "Amused" or "Annoyed" tags to avoid TTS saying bracketed text.
        if (emotionToTrigger != null && !emotionToTrigger.isBlank()) {
            final String finalEmotion = emotionToTrigger;
            CielState.getEmotionManager().ifPresent(em -> {
                em.triggerEmotion(finalEmotion, 0.8, "Conversational Reaction");
            });
        } else {
            // Also attempt to strip loose tags like [laughs] or *sighs* just in case the AI hallucinates them
            cleanText = cleanText.replaceAll("\\[.*?\\]", "").replaceAll("\\*.*?\\*", "").trim();
        }

        if (!cleanText.isEmpty()) {
            SpeechService.speak(cleanText);
        }
    }
}