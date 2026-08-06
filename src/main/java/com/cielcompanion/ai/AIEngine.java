package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIEngine {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofHours(24))
            .build();
    private static final Gson gson = new Gson();
    private static final ExecutorService translationExecutor = Executors.newSingleThreadExecutor();

    private static final Pattern EMOTION_TAG_PATTERN = Pattern.compile("\\[([a-zA-Z]+)\\]");
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("(?s)}\\s*");
    private static final Pattern ALPHA_NUM_PATTERN = Pattern.compile("[a-zA-Z0-9]");
    private static final Pattern ALPHA_PATTERN = Pattern.compile("[a-zA-Z]");

    private static final LinkedList<JsonObject> conversationHistory = new LinkedList<>();
    private static final int MAX_HISTORY = 10;

    private static final AtomicInteger activeSwarmTasks = new AtomicInteger();

    private static long lastInteractionTime = System.currentTimeMillis();
    private static ScheduledExecutorService memoryScheduler;

    static {
        memoryScheduler = Executors.newSingleThreadScheduledExecutor();
        memoryScheduler.scheduleWithFixedDelay(AIEngine::checkIdleMemoryDigestion, 60, 60, TimeUnit.SECONDS);
    }

    public static void keepLocalModelsAlive() {
        if (com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().isInGamingSession()) return;
        
        CompletableFuture.runAsync(() -> {
            try {
                String model = ModelManager.getModelName(ModelManager.ModelTier.TRANSLATOR).replace("ollama/", "");
                String url = "http://localhost:11434/api/generate";
                JsonObject payload = new JsonObject();
                payload.addProperty("model", model);
                payload.addProperty("keep_alive", -1); 
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                    .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // Silently drop heartbeat failures
            }
        });
    }

    public static int getActiveTaskCount() {
        return activeSwarmTasks.get();
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

    private static void ensureLiteLlmProvider(JsonObject payload) {
        if (payload != null && payload.has("model")) {
            String model = payload.get("model").getAsString();
            if (!model.contains("/")) {
                payload.addProperty("model", "ollama/" + model);
            }
        }
    }

    public static void warmUpModels() {
        System.out.println("Ciel Debug: Sending lightweight silent ping to wake local Translator...");
        CompletableFuture.runAsync(() -> {
            attemptTransliteration("Warmup ping.");
        });
    }

    public static CompletableFuture<String> transliterateAsync(String englishText) {
        return CompletableFuture.supplyAsync(() -> {
            if (!ALPHA_NUM_PATTERN.matcher(englishText).find()) return englishText;

            String fallbackResult = attemptTransliteration(englishText);
            if (fallbackResult != null && !ALPHA_PATTERN.matcher(fallbackResult).find()) {
                return fallbackResult; 
            }

            System.err.println("Ciel Warning: Swarm Transliteration failed. Returning raw English text.");
            return englishText; 
        }, translationExecutor);
    }

    public static String transliterateToKatakanaSync(String englishText) {
        try {
            // Lowered timeout to 10 seconds. We shouldn't hang the GUI forever if Ollama drops.
            return transliterateAsync(englishText).get(120, TimeUnit.SECONDS); 
        } catch (Exception e) {
            return englishText; 
        }
    }

    private static String attemptTransliteration(String englishText) {
        activeSwarmTasks.incrementAndGet();
        try {
            // DIRECT ROUTING: Hit the native FastAPI /katakana endpoint in openjarvis.py
            // Bypasses the ChatCompletion queue entirely to prevent deadlocks
            String url = "http://localhost:8000/katakana";
            
            JsonObject payload = new JsonObject();
            payload.addProperty("text", englishText);
            
            // Auto-Retry Loop if OpenJarvis is busy starting up or network blips
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(120)) 
                            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (json.has("katakana")) {
                            String result = json.get("katakana").getAsString();
                            if (!result.contains("エラー")) return result;
                        }
                    } else if (attempt == 3) {
                        System.err.println("Ciel Katakana Error: HTTP Status " + response.statusCode());
                    }
                } catch (Exception e) {
                    if (attempt == 3) {
                        System.err.println("Ciel Katakana Network Error: " + e.getMessage());
                    } else {
                        // Silent wait before retry
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                }
            }
        } finally {
            activeSwarmTasks.decrementAndGet();
        }
        return null;
    }

    public static CompletableFuture<String> generateSilentLogic(String userMessage, String systemContext) {
        double temp = systemContext.toLowerCase().contains("worker") ||
                      systemContext.toLowerCase().contains("lore") ? 0.3 : 0.1;
        return generateSilentLogicWithModel(userMessage, systemContext, null, temp);
    }

    public static CompletableFuture<String> generateSilentLogicWithModel(
            String userMessage,
            String systemContext,
            String forcedModel,
            double temperature) {

        activeSwarmTasks.incrementAndGet();
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.LOGIC);

        JsonObject payload = (forcedModel == null)
                ? ModelManager.buildPayload(ModelManager.ModelTier.LOGIC, systemContext, userMessage, false)
                : buildForcedPayload(systemContext, userMessage, forcedModel, temperature);

        ensureLiteLlmProvider(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(2)) // CRITICAL FIX: Dropped from 24 Hours to 2 Minutes to prevent queue lockup
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        String rawContent = ModelManager.extractMessageContent(response.body());
                        return rawContent != null ? THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim() : null;
                    }
                    return null;
                })
                .whenComplete((res, ex) -> activeSwarmTasks.decrementAndGet());
    }

    private static JsonObject buildForcedPayload(String systemContext, String userMessage,
                                                 String forcedModel, double temperature) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", forcedModel != null ? forcedModel : "ollama-cloud/deepseek-v3.1:671b");
        payload.addProperty("stream", false);
        payload.addProperty("temperature", temperature);

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

    public static String generateDiaryEntrySync(String userMessage, String systemContext) {
        activeSwarmTasks.incrementAndGet();
        try {
            String url = ModelManager.getUrlForTier(ModelManager.ModelTier.PERSONALITY);
            JsonObject payload = ModelManager.buildPayload(ModelManager.ModelTier.PERSONALITY, systemContext, userMessage, false);
            ensureLiteLlmProvider(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofHours(1))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String rawContent = ModelManager.extractMessageContent(response.body());
                return rawContent != null ? THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim() : null;
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Synchronous diary generation failed or timed out.");
        } finally {
            activeSwarmTasks.decrementAndGet();
        }
        return null;
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
                            "\nExtract any meaningful facts, preferences, or narrative conclusions into a concise 1-sentence summary. " +
                            "CRITICAL: Write the summary from Ciel's internal perspective. You MUST refer to the human strictly as 'Master' or 'Master Taylor', NEVER as 'the user'. " +
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
        System.out.println("Ciel Debug: Routing to Personality Core (Local Dialogue Race -> Manager Audit)...");
        
        activeSwarmTasks.incrementAndGet();
        addHistory("user", userMessage);
        
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.PERSONALITY);
        
        // Force stream=false to get the final audited block at once
        JsonObject payload = buildPayloadWithHistory(ModelManager.ModelTier.PERSONALITY, systemContext, false);
        // Ensure "model" is set to "local" so openjarvis routes to the race
        payload.addProperty("model", "local");
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(15)) 
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        triggerFallback(userMessage, systemContext, onComplete);
                        return;
                    }
                    
                    String rawContent = ModelManager.extractMessageContent(response.body());
                    if (rawContent != null) {
                        String cleanContent = THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim();
                        // Speak it in one chunk since the stream is disabled for the audit
                        processAndSpeakChunk(cleanContent);
                        
                        addHistory("assistant", cleanContent);
                        long durationMs = SpeechService.estimateSpeechDuration(cleanContent);
                        int extraSeconds = (int) (durationMs / 1000) + 15;
                        com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, extraSeconds);
                    }
                })
                .exceptionally(e -> {
                    triggerFallback(userMessage, systemContext, onComplete);
                    return null;
                })
                .whenComplete((res, ex) -> {
                    activeSwarmTasks.decrementAndGet();
                    if (onComplete != null) onComplete.run();
                });
    }

    public static CompletableFuture<JsonObject> evaluateBackground(String transcriptBuffer, String systemContext) {
        activeSwarmTasks.incrementAndGet();
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.EVALUATOR);
        JsonObject payload = ModelManager.buildPayload(ModelManager.ModelTier.EVALUATOR, systemContext, "TRANSCRIPT:\n" + transcriptBuffer, false);
        ensureLiteLlmProvider(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(15)) 
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        String content = ModelManager.extractMessageContent(response.body());
                        return content != null ? JsonParser.parseString(content).getAsJsonObject() : null;
                    }
                    return null;
                })
                .whenComplete((res, ex) -> activeSwarmTasks.decrementAndGet());
    }

    public static void reasonDeeply(String userMessage, String systemContext, Runnable onComplete) {
        System.out.println("Ciel Debug: Routing to Primary Logic Core (DeepSeek)...");
        SpeechService.speakPreformatted("[Focused] Initiating deep cognitive analysis. Please stand by.");

        activeSwarmTasks.incrementAndGet();
        addHistory("user", userMessage);

        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.LOGIC);
        JsonObject payload = buildPayloadWithHistory(ModelManager.ModelTier.LOGIC, systemContext, false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(15))
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
                })
                .whenComplete((res, ex) -> activeSwarmTasks.decrementAndGet());
    }

    private static void reasonDeeplyLocalFallback(String userMessage, String systemContext, Runnable onComplete) {
        System.out.println("Ciel Debug: Routing to Local Fallback Logic Core (LM Studio: Phi-4)...");
        activeSwarmTasks.incrementAndGet();
        
        String url = Settings.getLlmLocalLogicFallbackUrl() + "/chat/completions";
        
        JsonObject payload = new JsonObject();
        payload.addProperty("model", "phi-4-reasoning-plus"); 
        payload.addProperty("temperature", 0.3); 
        payload.addProperty("stream", false);
        ensureLiteLlmProvider(payload);
        
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
                .timeout(Duration.ofMinutes(15))
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
                })
                .whenComplete((res, ex) -> activeSwarmTasks.decrementAndGet());
    }

    private static void processLogicResponse(String responseBody, Runnable onComplete) {
        String rawContent = ModelManager.extractMessageContent(responseBody);
        if (rawContent == null) return;
        
        String cleanContent = THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim();
        
        String[] sentences = cleanContent.split("(?<=[.!?])\\s+");
        for (String s : sentences) {
            processAndSpeakChunk(s);
        }
        
        addHistory("assistant", cleanContent);

        long durationMs = SpeechService.estimateSpeechDuration(cleanContent);
        int extraSeconds = (int) (durationMs / 1000) + 15;
        com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, extraSeconds);
        
        if (onComplete != null) onComplete.run();
    }

    private static void triggerFallback(String userMessage, String systemContext, Runnable onComplete) {
        System.out.println("Ciel Debug: Triggering final fallback core (LM Studio: Phi-4)...");
        activeSwarmTasks.incrementAndGet();
        
        String url = Settings.getLlmLocalLogicFallbackUrl() + "/chat/completions";
        
        JsonObject payload = new JsonObject();
        payload.addProperty("model", "phi-4-reasoning-plus"); 
        payload.addProperty("temperature", 0.3); 
        payload.addProperty("stream", false);
        ensureLiteLlmProvider(payload);
        
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
                .timeout(Duration.ofMinutes(15))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        String content = ModelManager.extractMessageContent(response.body());
                        if (content != null) {
                            String cleanContent = THINK_TAG_PATTERN.matcher(content).replaceAll("").trim();
                            for (String s : cleanContent.split("(?<=[.!?])\\s+")) processAndSpeakChunk(s);
                            
                            addHistory("assistant", cleanContent);

                            long durationMs = SpeechService.estimateSpeechDuration(cleanContent);
                            int extraSeconds = (int) (durationMs / 1000) + 15;
                            com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, extraSeconds);
                        }
                    } else {
                        SpeechService.speakPreformatted("[Glitched] Fallback cognitive matrix also unavailable.");
                    }
                })
                .whenComplete((res, ex) -> {
                    activeSwarmTasks.decrementAndGet();
                    if (onComplete != null) onComplete.run();
                });
    }

    private static JsonObject buildPayloadWithHistory(ModelManager.ModelTier tier, String systemContext, boolean stream) {
        JsonObject payload = new JsonObject();
        
        payload.addProperty("model", "local"); 
        payload.addProperty("stream", stream);
        payload.addProperty("temperature", (tier == ModelManager.ModelTier.LOGIC || tier == ModelManager.ModelTier.LOCAL_LOGIC_FALLBACK) ? 0.3 : 0.7);

        ensureLiteLlmProvider(payload);

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

    private static void processAndSpeakChunk(String chunk) {
        String cleanText = chunk.trim();
        if (cleanText.isEmpty()) return;

        cleanText = cleanText.replaceAll("\\[[A-Z_]+\\]", "").trim();

        Matcher matcher = EMOTION_TAG_PATTERN.matcher(cleanText);
        String emotionToTrigger = null;

        while (matcher.find()) {
            emotionToTrigger = matcher.group(1);
        }
        
        String rawText = matcher.replaceAll("").trim();
        final String textToProcess = rawText.replaceAll("\\*.*?\\*", "").trim();

        System.out.println("[Ciel Swarm Output]: " + textToProcess);

        if (emotionToTrigger != null && !emotionToTrigger.isBlank()) {
            final String finalEmotion = emotionToTrigger;
            CielState.getEmotionManager().ifPresent(em -> {
                em.triggerEmotion(finalEmotion, 0.8, "Conversational Reaction");
            });
        }

        transliterateAsync(textToProcess).thenAccept(katakana -> {
            SpeechService.speakChunk(katakana); 
        });
    }

    public static String determineIntentSynchronously(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String systemContext = "You are an intent classification system. Your task is to classify the user's input into one of the following intents: " +
                "INITIATE_SHUTDOWN, INITIATE_REBOOT, CANCEL_SHUTDOWN, UPDATE_SYSTEM, GET_TIME, GET_WEATHER, GET_WEATHER_FORECAST, GET_SYSTEM_STATUS, " +
                "GET_DAILY_REPORT, GET_TOP_MEMORY_PROCESS, GET_TOP_CPU_PROCESS, RECALL_FACT, DND_GET_RULE, DND_API_SEARCH, GET_MOON_PHASE, " +
                "GET_VISIBLE_PLANETS, GET_CONSTELLATIONS, GET_ECLIPSES, DYNAMIC_PC_CONTROL, EXECUTE_SKILL, FIND_APP_PATH, SCAN_FOR_APPS, " +
                "TERMINATE_PROCESS, TERMINATE_PROCESS_FORCE, REMEMBER_FACT, REMEMBER_FACT_SIMPLE, OPEN_APPLICATION, START_ROUTINE, " +
                "SET_MODE_ATTENTIVE, SET_MODE_DND, SET_MODE_INTEGRATED, LEARN_PHONETIC, DND_RUN_AUDIT, DND_RECORD_MASTERY, DND_REPORT_SURGE, " +
                "OPEN_CHEAT_SHEET, TENSURA_ENTER_WORLD, TENSURA_CONFIRM_COPY, DND_ROLL_DICE, DND_PLAY_SOUND, DND_CREATE_SESSION_NOTE, " +
                "DND_ADD_TO_SESSION_NOTE, DND_RECALL_SESSION_NOTE, DND_LINK_SESSION_NOTE, DND_RECALL_SESSION_LINKS, DND_REVEAL_LORE, " +
                "DND_ANALYZE_LORE, TOGGLE_LISTENING, EASTER_EGG, UNKNOWN." +
                "\n\nRespond with ONLY the intent name (exactly as above) that best matches the user's input. If none match, respond with UNKNOWN.";

        try {
            String result = AIEngine.generateSilentLogic(text, systemContext).get(120, TimeUnit.SECONDS);
            if (result != null) {
                String[] parts = result.trim().split("\\s+");
                if (parts.length > 0) {
                    String intentStr = parts[0].trim().toUpperCase();
                    intentStr = intentStr.replaceAll("[^A-Z0-9_]", "");
                    if (!intentStr.isEmpty()) {
                        return intentStr;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Intent classification failed: " + e.getMessage());
        }
        return null;
    }
}
