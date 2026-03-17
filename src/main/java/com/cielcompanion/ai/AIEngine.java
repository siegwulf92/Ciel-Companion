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
import java.util.concurrent.ExecutorService;
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
    private static final ExecutorService translationExecutor = Executors.newSingleThreadExecutor();
    
    private static final Pattern EMOTION_TAG_PATTERN = Pattern.compile("\\[([a-zA-Z]+)\\]");
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("(?s)<think>.*?</think>");
    private static final Pattern ALPHA_NUM_PATTERN = Pattern.compile("[a-zA-Z0-9]");
    private static final Pattern ALPHA_PATTERN = Pattern.compile("[a-zA-Z]");

    private static final LinkedList<JsonObject> conversationHistory = new LinkedList<>();
    private static final int MAX_HISTORY = 10; 
    
    private static long lastInteractionTime = System.currentTimeMillis();
    private static ScheduledExecutorService memoryScheduler;

    static {
        memoryScheduler = Executors.newSingleThreadScheduledExecutor();
        memoryScheduler.scheduleWithFixedDelay(AIEngine::checkIdleMemoryDigestion, 60, 60, TimeUnit.SECONDS);
    }

    private static void addHistory(String role, String content) {
        lastInteractionTime = System.currentTimeMillis();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        
        synchronized (conversationHistory) {
            conversationHistory.add(msg);
            if (conversationHistory.size() > MAX_HISTORY) {
                conversationHistory.removeFirst();
            }
        }
    }

    // --- NEW: FORCES LM STUDIO & OLLAMA TO LOAD MODELS INTO VRAM ON BOOT ---
    public static void warmUpModels() {
        System.out.println("Ciel Debug: Sending silent pings to force-load AI models into VRAM...");
        CompletableFuture.runAsync(() -> {
            attemptTransliteration("Warmup ping."); 
        });
        CompletableFuture.runAsync(() -> {
            generateSilentLogic("Warmup ping.", "System warmup."); 
        });
    }

    // --- HIGH-SPEED TRANSLITERATION (PARALLEL LM STUDIO ROUTING) ---
    public static CompletableFuture<String> transliterateAsync(String englishText) {
        return CompletableFuture.supplyAsync(() -> {
            if (!ALPHA_NUM_PATTERN.matcher(englishText).find()) return englishText;

            // Route DIRECTLY to LM Studio (Phi-4) to avoid blocking Ollama's generation stream
            String fallbackResult = attemptTransliteration(englishText);
            
            // Validation: Reject if it output English letters. (Numbers are fine).
            if (fallbackResult != null && !ALPHA_PATTERN.matcher(fallbackResult).find()) {
                return fallbackResult; 
            }

            System.err.println("Ciel Warning: Phi-4 Transliteration failed. Returning raw English text.");
            return englishText; 
        }, translationExecutor);
    }

    private static String attemptTransliteration(String englishText) {
        // UPDATED: Now routes directly to our new Python OpenJarvis Katakana Agent!
        try {
            String url = "http://localhost:8000/transliterate";
            JsonObject payload = new JsonObject();
            payload.addProperty("text", englishText);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45)) // Give LM Studio plenty of time to warm up
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                if (jsonResponse.has("katakana")) {
                    String result = jsonResponse.get("katakana").getAsString();
                    if (!"エラー".equals(result)) return result;
                }
            }
        } catch (Exception e) {
            // Fail silently
        }
        return null;
    }

    public static String transliterateToKatakanaSync(String englishText) {
        try {
            return transliterateAsync(englishText).get(45, TimeUnit.SECONDS); 
        } catch (Exception e) {
            return englishText; 
        }
    }

    // --- THE SEMANTIC ROUTER (PRE-FRONTAL CORTEX) ---
    public static CommandAnalysis determineIntentSynchronously(String userMessage) {
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.EVALUATOR);
        String knownSkills = SkillManager.getAvailableSkillsString();
        
        String systemContext = "You are the NLU intent router for Ciel. Analyze the user's STT text, correct phonetic typos, and map it to an intent.\n" +
            "Available Intents:\n" +
            "GET_WEATHER : Ask about current weather\n" +
            "GET_TIME : Ask for time or date\n" +
            "GET_SYSTEM_STATUS : Ask for PC CPU/RAM status\n" +
            "EXECUTE_SKILL : User is asking to use a previously learned skill: [" + knownSkills + "]. The cleaned_text MUST be the exact name of the skill.\n" +
            "DYNAMIC_PC_CONTROL : User asks to write a NEW script, automate a task, or manipulate PC settings NOT in the skills list.\n" +
            "DND_ANALYZE_LORE : Deep D&D lore analysis\n" +
            "UNKNOWN : General chat, questions, or conversation.\n\n" +
            "MASTER'S PREFERENCES & INFERRED LOGIC:\n" +
            "The Master prefers quantified outputs to be even numbers or multiples of 5. If the user gives a vague command, infer a logical target value based on these preferences and provide it in the 'arguments' field.\n\n" +
            "Return strictly JSON: { \"intent\": \"THE_INTENT\", \"cleaned_text\": \"Corrected query or skill name\", \"arguments\": \"Inferred parameters separated by spaces, or empty string\" }";

        JsonObject payload = ModelManager.buildPayload(ModelManager.ModelTier.EVALUATOR, systemContext, userMessage, false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String content = ModelManager.extractMessageContent(response.body());
                if (content != null) {
                    JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
                    
                    String intentStr = parsed.get("intent").getAsString();
                    String cleanedText = parsed.has("cleaned_text") ? parsed.get("cleaned_text").getAsString() : userMessage;
                    String arguments = parsed.has("arguments") ? parsed.get("arguments").getAsString() : "";
                    
                    Intent mappedIntent;
                    try { mappedIntent = Intent.valueOf(intentStr); } catch (Exception e) { mappedIntent = Intent.UNKNOWN; }
                    
                    Map<String, String> entities = new HashMap<>();
                    entities.put("query", cleanedText); 
                    entities.put("arguments", arguments);
                    
                    System.out.println("Ciel Debug: Semantic Router -> Intent: [" + mappedIntent + "] | Skill/Query: '" + cleanedText + "' | Args: '" + arguments + "'");
                    return new CommandAnalysis(mappedIntent, entities);
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Semantic routing failed. Falling back to UNKNOWN.");
        }
        
        Map<String, String> entities = new HashMap<>();
        entities.put("query", userMessage);
        entities.put("arguments", "");
        return new CommandAnalysis(Intent.UNKNOWN, entities);
    }

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
                        String rawContent = ModelManager.extractMessageContent(response.body());
                        return rawContent != null ? THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim() : null;
                    }
                    return null;
                });
    }

    public static String generateDiaryEntrySync(String userMessage, String systemContext) {
        System.out.println("Ciel Debug: Generating diary entry synchronously (Fast Personality Tier)...");
        String url = ModelManager.getUrlForTier(ModelManager.ModelTier.PERSONALITY);
        
        JsonObject payload = new JsonObject();
        payload.addProperty("model", Settings.getLlmPersonalityModel()); 
        payload.addProperty("stream", false);
        payload.addProperty("temperature", 0.6); 

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
                .timeout(Duration.ofSeconds(15)) 
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String rawContent = ModelManager.extractMessageContent(response.body());
                return rawContent != null ? THINK_TAG_PATTERN.matcher(rawContent).replaceAll("").trim() : null;
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Synchronous diary generation failed or timed out.");
        }
        return null;
    }

    private static void checkIdleMemoryDigestion() {
        long idleTimeMs = System.currentTimeMillis() - lastInteractionTime;
        if (idleTimeMs > 5 * 60 * 1000) { 
            JsonArray historyArray = new JsonArray();
            
            synchronized (conversationHistory) {
                if (conversationHistory.isEmpty()) return;
                System.out.println("Ciel Debug: Conversation idle. Digesting short-term buffer into Long-Term Episodic Memory...");
                for (JsonObject obj : conversationHistory) historyArray.add(obj);
                conversationHistory.clear(); 
            }
            
            if (historyArray.size() > 0) {
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
                });
            }
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
                .whenComplete((res, ex) -> {
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
                        String content = ModelManager.extractMessageContent(response.body());
                        return content != null ? JsonParser.parseString(content).getAsJsonObject() : null;
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
        System.out.println("Ciel Debug: Routing to Local Fallback Logic Core (LM Studio: Phi-4)...");
        
        String url = Settings.getLlmLocalLogicFallbackUrl() + "/chat/completions";
        
        JsonObject payload = new JsonObject();
        payload.addProperty("model", "phi-4-reasoning-plus"); 
        payload.addProperty("temperature", 0.3); 
        payload.addProperty("stream", false);
        
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
        String rawContent = ModelManager.extractMessageContent(responseBody);
        if (rawContent == null) return;
        
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
        System.out.println("Ciel Debug: Triggering final fallback core (LM Studio: Phi-4)...");
        
        String url = Settings.getLlmLocalLogicFallbackUrl() + "/chat/completions";
        
        JsonObject payload = new JsonObject();
        payload.addProperty("model", "phi-4-reasoning-plus"); 
        payload.addProperty("temperature", 0.3); 
        payload.addProperty("stream", false);
        
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
                .timeout(Duration.ofSeconds(60))
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
                            int extraSeconds = (int) (durationMs / 1000) + 20;
                            com.cielcompanion.memory.stwm.ShortTermMemoryService.getMemory().setPrivilegedMode(true, extraSeconds);
                        }
                    } else {
                        SpeechService.speakPreformatted("[Glitched] Fallback cognitive matrix also unavailable.");
                    }
                })
                .whenComplete((res, ex) -> {
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
        
        String rawText = matcher.replaceAll("").trim();
        final String textToProcess = rawText.replaceAll("\\*.*?\\*", "").trim();

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
}