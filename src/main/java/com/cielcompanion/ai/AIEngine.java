package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;
import com.cielcompanion.service.nlu.CommandAnalysis;
import com.cielcompanion.service.nlu.Intent;
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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIEngine {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Gson gson = new Gson();
    
    private static final Pattern EMOTION_TAG_PATTERN = Pattern.compile("\\[([a-zA-Z]+)\\]");
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("(?s)<think>.*?</think>");

    private static final LinkedList<JsonObject> conversationHistory = new LinkedList<>();
    private static final int MAX_HISTORY = 10; 
    
    private static long lastInteractionTime = System.currentTimeMillis();
    private static ScheduledExecutorService memoryScheduler;

    static {
        memoryScheduler = Executors.newSingleThreadScheduledExecutor();
        memoryScheduler.scheduleWithFixedDelay(AIEngine::checkIdleMemoryDigestion, 60, 60, TimeUnit.SECONDS);
    }

    private static synchronized void addHistory(String role, String content) {
        lastInteractionTime = System.currentTimeMillis();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        conversationHistory.add(msg);
        if (conversationHistory.size() > MAX_HISTORY) {
            conversationHistory.removeFirst();
        }
    }

    // --- NEW: THE SEMANTIC ROUTER (PRE-FRONTAL CORTEX) ---
    // Synchronously evaluates fuzzy user inputs to determine their true intent and clean up STT typos.
    public static CommandAnalysis determineIntentSynchronously(String userMessage) {
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.EVALUATOR);
        
        String systemContext = "You are the NLU intent router for Ciel. Analyze the user's STT text, correct phonetic typos, and map it to an intent.\n" +
            "Available Intents:\n" +
            "GET_WEATHER : Ask about current weather\n" +
            "GET_WEATHER_FORECAST : Ask about future weather\n" +
            "GET_TIME : Ask for time or date\n" +
            "GET_SYSTEM_STATUS : Ask for PC CPU/RAM status\n" +
            "DYNAMIC_PC_CONTROL : User asks to write a script, automate a task, create folders, or manipulate PC files.\n" +
            "DND_ANALYZE_LORE : Deep D&D lore analysis\n" +
            "GET_MOON_PHASE : Moon phase\n" +
            "GET_VISIBLE_PLANETS : Visible planets\n" +
            "GET_ECLIPSES : Eclipses\n" +
            "UNKNOWN : General chat, questions, conversation, or anything else not listed above.\n\n" +
            "Return strictly JSON: { \"intent\": \"THE_INTENT\", \"cleaned_text\": \"Corrected user query without filler words\" }";

        JsonObject payload = ModelManager.buildPayload(ModelManager.ModelTier.EVALUATOR, systemContext, userMessage, false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                String content = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
                JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
                
                String intentStr = parsed.get("intent").getAsString();
                String cleanedText = parsed.has("cleaned_text") ? parsed.get("cleaned_text").getAsString() : userMessage;
                
                Intent mappedIntent;
                try {
                    mappedIntent = Intent.valueOf(intentStr);
                } catch (Exception e) {
                    mappedIntent = Intent.UNKNOWN;
                }
                
                Map<String, String> entities = new HashMap<>();
                entities.put("query", cleanedText); 
                
                System.out.println("Ciel Debug: Semantic Router classified intent as [" + mappedIntent + "] (Cleaned: '" + cleanedText + "')");
                return new CommandAnalysis(mappedIntent, entities);
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Semantic routing failed. Falling back to UNKNOWN.");
        }
        
        Map<String, String> entities = new HashMap<>();
        entities.put("query", userMessage);
        return new CommandAnalysis(Intent.UNKNOWN, entities);
    }
    // -----------------------------------------------------

    public static CompletableFuture<String> generateSilentLogic(String userMessage, String systemContext) {
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.LOGIC);
        JsonObject payload = new JsonObject();
        payload.addProperty("model", Settings.getLlmLogicModel());
        payload.addProperty("stream", false);
        payload.addProperty("temperature", 0.1); 

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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                        String rawContent = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString();
                        return THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim();
                    }
                    return null;
                });
    }

    private static synchronized void checkIdleMemoryDigestion() {
        if (conversationHistory.isEmpty()) return;
        
        long idleTimeMs = System.currentTimeMillis() - lastInteractionTime;
        if (idleTimeMs > 5 * 60 * 1000) { 
            System.out.println("Ciel Debug: Conversation idle. Digesting short-term buffer into Long-Term Episodic Memory...");
            
            JsonArray historyArray = new JsonArray();
            for (JsonObject obj : conversationHistory) historyArray.add(obj);
            
            String prompt = "You are the memory core of Ciel. Review this conversation history array:\n" + 
                            gson.toJson(historyArray) + 
                            "\nExtract any meaningful facts, user preferences, or narrative conclusions into a concise 1-sentence summary. " +
                            "Reply strictly in JSON: { \"actionable\": true/false, \"summary\": \"the extracted fact\" }. " +
                            "If it was just casual greetings or small talk, set actionable to false.";
                            
            evaluateBackground(prompt, "You are a memory extraction sub-process.").thenAccept(result -> {
                if (result != null && result.has("actionable") && result.get("actionable").getAsBoolean()) {
                    String summary = result.get("summary").getAsString();
                    System.out.println("Ciel Debug: Memory Digested -> " + summary);
                    String memoryKey = "Memory_" + System.currentTimeMillis();
                    MemoryService.addFact(new Fact(memoryKey, summary, System.currentTimeMillis(), "episodic_memory", "auto-digestion", 1));
                }
                conversationHistory.clear(); 
            });
        }
    }

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
                    StringBuilder fullResponseBuffer = new StringBuilder(); 

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

                    long durationMs = SpeechService.estimateSpeechDuration(fullResponseBuffer.toString());
                    int extraSeconds = (int) (durationMs / 1000) + 20;
                    com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, extraSeconds);
                    
                })
                .exceptionally(e -> {
                    if (!isFallbackTriggered.get()) triggerFallback(userMessage, systemContext, onComplete);
                    return null;
                })
                .thenRun(() -> {
                    if (!isFallbackTriggered.get() && onComplete != null) onComplete.run();
                });
    }

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

    public static void reasonDeeply(String userMessage, String systemContext, Runnable onComplete) {
        System.out.println("Ciel Debug: Routing to Primary Logic Core (DeepSeek)...");
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
                        processLogicResponse(response.body(), onComplete);
                    } else {
                        System.err.println("Ciel AI Error: Primary Logic Core returned " + response.statusCode() + ". Falling back to Local Phi-4.");
                        reasonDeeplyLocalFallback(userMessage, systemContext, onComplete);
                    }
                })
                .exceptionally(e -> {
                    System.err.println("Ciel AI Error: Primary Logic Core unreachable. Falling back to Local Phi-4.");
                    reasonDeeplyLocalFallback(userMessage, systemContext, onComplete);
                    return null;
                });
    }

    private static void reasonDeeplyLocalFallback(String userMessage, String systemContext, Runnable onComplete) {
        System.out.println("Ciel Debug: Routing to Local Fallback Logic Core (Phi-4)...");
        
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.LOCAL_LOGIC_FALLBACK);
        JsonObject payload = buildPayloadWithHistory(ModelManager.ModelTier.LOCAL_LOGIC_FALLBACK, systemContext, false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        processLogicResponse(response.body(), onComplete);
                    } else {
                        SpeechService.speakPreformatted("[Annoyed] Both Logic cores returned an anomaly. Routing to online fallback.");
                        triggerFallback(userMessage, systemContext, onComplete);
                    }
                })
                .exceptionally(e -> {
                    System.err.println("Ciel AI Error: Local Logic core timeout. Ensure LM Studio is running.");
                    triggerFallback(userMessage, systemContext, onComplete);
                    return null;
                });
    }

    private static void processLogicResponse(String responseBody, Runnable onComplete) {
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
        String rawContent = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
        
        String cleanContent = THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim();
        
        String[] sentences = cleanContent.split("(?<=[.!?])\\s+");
        for (String s : sentences) {
            processAndSpeakChunk(s);
        }
        
        addHistory("assistant", cleanContent);

        long durationMs = SpeechService.estimateSpeechDuration(cleanContent);
        int extraSeconds = (int) (durationMs / 1000) + 20;
        com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, extraSeconds);
        
        if (onComplete != null) onComplete.run();
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

                        long durationMs = SpeechService.estimateSpeechDuration(content);
                        int extraSeconds = (int) (durationMs / 1000) + 20;
                        com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, extraSeconds);
                    } else {
                        SpeechService.speakPreformatted("[Glitched] Fallback cognitive matrix also unavailable.");
                    }
                })
                .thenRun(() -> {
                    if (onComplete != null) onComplete.run();
                });
    }

    private static JsonObject buildPayloadWithHistory(ModelManager.ModelTier tier, String systemContext, boolean stream) {
        JsonObject payload = new JsonObject();
        
        String modelName = switch (tier) {
            case PERSONALITY -> Settings.getLlmPersonalityModel();
            case EVALUATOR -> Settings.getLlmEvaluatorModel();
            case LOGIC -> Settings.getLlmLogicModel();
            case LOCAL_LOGIC_FALLBACK -> Settings.getLlmLocalLogicFallbackModel();
        };

        payload.addProperty("model", modelName);
        payload.addProperty("stream", stream);
        payload.addProperty("temperature", (tier == ModelManager.ModelTier.LOGIC || tier == ModelManager.ModelTier.LOCAL_LOGIC_FALLBACK) ? 0.3 : 0.7);

        JsonArray messages = new JsonArray();
        
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemContext);
        messages.add(sysMsg);

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
        return t.endsWith("。") || t.endsWith("？") || t.endsWith("！") || t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith("\n");
    }

    private static void processAndSpeakChunk(String chunk) {
        String cleanText = chunk.trim();
        if (cleanText.isEmpty()) return;

        Matcher matcher = EMOTION_TAG_PATTERN.matcher(cleanText);
        String emotionToTrigger = null;

        while (matcher.find()) {
            emotionToTrigger = matcher.group(1);
        }
        
        cleanText = matcher.replaceAll("").trim();
        cleanText = cleanText.replaceAll("\\*.*?\\*", "").trim();

        if (emotionToTrigger != null && !emotionToTrigger.isBlank()) {
            final String finalEmotion = emotionToTrigger;
            CielState.getEmotionManager().ifPresent(em -> {
                em.triggerEmotion(finalEmotion, 0.8, "Conversational Reaction");
            });
        }

        if (!cleanText.isEmpty()) {
            SpeechService.speak(cleanText);
        }
    }
}