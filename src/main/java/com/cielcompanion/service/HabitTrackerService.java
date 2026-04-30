package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ContextBuilder;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HabitTrackerService {

    private static ScheduledExecutorService habitScheduler;
    private static ScheduledExecutorService tripwireScheduler;
    
    private static final Map<String, Long> dailyHabits = new HashMap<>();
    private static String currentCategory = "Idle";
    private static LocalDate currentDate = LocalDate.now();
    
    private static boolean proactiveTriggeredToday = false;
    private static boolean queueFlushedThisSession = true; 

    private static final Map<String, String> processCategoryCache = new ConcurrentHashMap<>();
    
    private static final Set<String> IGNORED_PROCESSES = Set.of(
        "chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe", 
        "explorer.exe", "idle", "discord.exe", "cmd.exe", "powershell.exe", "pwsh.exe", 
        "conhost.exe", "applicationframehost.exe", "razerappengine.exe", "razer central.exe", "razer synapse.exe"
    );

    private static String currentMediaTitle = "";
    private static int currentMediaConsecutiveMinutes = 0;
    private static final Set<String> loggedMediaToday = new HashSet<>();

    private static final Queue<String> deferredSpeechQueue = new LinkedList<>();
    private static boolean currentGamePausable = false;

    private static String cachedActiveUrl = "";
    private static String cachedDomText = "";
    private static String lastTripwireTitle = "";
    
    private static final LinkedList<String> recentMediaHistory = new LinkedList<>();
    private static int currentBingeCount = 0;
    
    private static String deferredIntenseMediaTitle = null;
    
    // --- SERIES CONTINUITY TRACKERS ---
    private static String activeSeriesName = "";
    private static String activeSeriesDom = "";
    private static final List<String> activeSeriesEpisodes = new ArrayList<>();

    public static void initialize() {
        habitScheduler = Executors.newSingleThreadScheduledExecutor();
        tripwireScheduler = Executors.newSingleThreadScheduledExecutor();
        
        habitScheduler.scheduleWithFixedDelay(HabitTrackerService::pollAndTrack, 5, 60, TimeUnit.SECONDS);
        tripwireScheduler.scheduleWithFixedDelay(HabitTrackerService::tripwireCheck, 5, 15, TimeUnit.SECONDS);
        
        System.out.println("Ciel Debug: Habit Tracker Service initialized. Monitoring behavior patterns.");
    }

    private static void performReturnFromIdle(ShortTermMemory memory, int oldPhase) {
        System.out.printf("Ciel Debug: Phase changed from %d to 0 confirmed.%n", oldPhase);
        CielState.getEmotionManager().ifPresent(em -> {
            em.triggerEmotion("Focused", 1.0, "Activity");
            em.triggerEmotion("Happy", 0.5, "UserReturn");
            em.triggerEmotion("Lonely", -1.0, null);
        });

        memory.setCurrentPhase(0);
        CielState.setFinalPlayed(false);

        System.out.println("Ciel Debug: User returned from idle. Forcing reconnect to NVIDIA Broadcast and flushing Vosk audio buffers...");
        SpeechService.getVoiceListener().ifPresent(VoiceListener::forceMicReinitialization);

        SpeechService.stopCurrentPlayback();
        SpeechService.cancelSequentialSpeech();

        if (oldPhase >= 4) {
            String recentMemories = String.join(" ", MemoryService.getRecentEpisodicMemories(3));
            
            List<LineManager.DialogueLine> greetingLines = LineManager.getLoginGreetingLines();
            if (greetingLines != null && !greetingLines.isEmpty()) {
                LineManager.DialogueLine line = greetingLines.get(new Random().nextInt(greetingLines.size()));
                SpeechService.speakPreformatted(line.text(), line.key());
            }

            CompletableFuture.runAsync(() -> {
                String prompt = "Master has returned after a long absence. Welcome him back. Recent memory context: " + recentMemories;
                String context = ContextBuilder.buildActiveContext(null, "");
                AIEngine.chatFast(prompt, context, null);
            });
        }
    }

    private static String extractPlatform(String title) {
        String lower = title.toLowerCase();
        if (lower.contains("crunchyroll")) return "crunchyroll";
        if (lower.contains("youtube")) return "youtube";
        if (lower.contains("netflix")) return "netflix";
        if (lower.contains("hulu")) return "hulu";
        if (lower.contains("prime video")) return "prime video";
        if (lower.contains("viz")) return "viz";
        return "unknown";
    }

    private static void tripwireCheck() {
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        String activeTitle = metrics.activeWindowTitle();
        if (activeTitle == null || activeTitle.isBlank()) return;
        
        boolean isMedia = activeTitle.toLowerCase().matches(".*(youtube|netflix|twitch|crunchyroll|hulu|prime video|disney\\+|max|peacock|paramount\\+|apple tv|viz).*");
        
        if (isMedia && !activeTitle.equals(lastTripwireTitle)) {
            System.out.println("Ciel Debug: Active media window changed to: " + activeTitle);
            
            boolean isShowPlatform = activeTitle.toLowerCase().matches(".*(netflix|crunchyroll|hulu|prime video|disney\\+|max|peacock|paramount\\+|apple tv|viz).*");
            
            // CRITICAL FIX: Strip episode identifiers to isolate the core series name for fuzzy matching
            String rawTitlePrefix = activeTitle.split("-")[0].split("\\|")[0].trim();
            String newSeriesName = rawTitlePrefix.replaceAll("(?i)\\s+(episode|ep|vol|chapter|ch)\\s*\\d+.*", "").trim();
            if (newSeriesName.isEmpty()) newSeriesName = rawTitlePrefix;
            
            boolean tempIsSameSeries = false;
            if (isShowPlatform && !activeSeriesName.isEmpty()) {
                // Check if they share the first 8 characters, or if one is completely contained in the other
                String prefixOld = activeSeriesName.length() > 8 ? activeSeriesName.substring(0, 8).toLowerCase() : activeSeriesName.toLowerCase();
                String prefixNew = newSeriesName.length() > 8 ? newSeriesName.substring(0, 8).toLowerCase() : newSeriesName.toLowerCase();
                
                if (prefixNew.startsWith(prefixOld) || prefixOld.startsWith(prefixNew) || rawTitlePrefix.toLowerCase().contains(activeSeriesName.toLowerCase())) {
                    tempIsSameSeries = true;
                    System.out.println("Ciel Debug: Same series detected ('" + activeSeriesName + "'). Preserving existing Series DOM context.");
                }
            }
            
            final boolean isSameSeries = tempIsSameSeries;

            if (isShowPlatform && !isSameSeries) {
                activeSeriesName = newSeriesName;
                activeSeriesDom = "";
                activeSeriesEpisodes.clear();
                System.out.println("Ciel Debug: New series initialized: " + activeSeriesName);
            } else if (!isShowPlatform) {
                activeSeriesName = "";
                activeSeriesDom = "";
                activeSeriesEpisodes.clear();
            }

            if (!extractPlatform(activeTitle).equals(extractPlatform(lastTripwireTitle)) || !isSameSeries) {
                cachedDomText = "";
                cachedActiveUrl = "";
            }
            
            lastTripwireTitle = activeTitle;
            
            CompletableFuture.runAsync(() -> {
                try {
                    boolean securedRichDom = false;
                    int cycle = 0;
                    
                    if (isSameSeries && cachedDomText.length() > 400) {
                        JsonObject newData = getActiveMediaData(activeTitle);
                        if (newData != null && newData.has("url") && !newData.get("url").isJsonNull()) {
                            String newUrl = newData.get("url").getAsString();
                            if (!newUrl.isEmpty()) cachedActiveUrl = newUrl;
                        }
                        securedRichDom = true;
                    }
                    
                    while (!securedRichDom && cycle < 6 && activeTitle.equals(lastTripwireTitle)) {
                        for (int i = 0; i < 10; i++) {
                            JsonObject newData = getActiveMediaData(activeTitle);
                            
                            if (newData != null) {
                                if (newData.has("url") && !newData.get("url").isJsonNull()) {
                                    String newUrl = newData.get("url").getAsString();
                                    if (!newUrl.isEmpty() && !newUrl.equals(cachedActiveUrl)) {
                                        cachedActiveUrl = newUrl;
                                        System.out.println("Ciel Debug: URL captured: " + cachedActiveUrl);
                                    }
                                }
                                if (newData.has("dom") && !newData.get("dom").isJsonNull()) {
                                    String newDom = newData.get("dom").getAsString();
                                    
                                    // Lowered threshold to 400 to account for densely packed HTML descriptions
                                    if (newDom != null && newDom.length() > 400) {
                                        cachedDomText = newDom;
                                        if (isShowPlatform) activeSeriesDom = newDom; // Save the Perfect DOM
                                        securedRichDom = true;
                                        break;
                                    } else if (newDom != null && newDom.contains("Bypassed")) {
                                        if (cachedDomText.isEmpty()) cachedDomText = newDom;
                                        securedRichDom = true;
                                        break;
                                    } else if (newDom != null && newDom.isEmpty() && isSameSeries && !activeSeriesDom.isEmpty()) {
                                        // Series Continuity: Fullscreen blocked the DOM, but we have the Perfect DOM saved!
                                        cachedDomText = activeSeriesDom; 
                                        securedRichDom = true;
                                        System.out.println("Ciel Debug: Fullscreen DOM blank. Injected preserved Series DOM from memory.");
                                        break;
                                    }
                                }
                            }
                            Thread.sleep(500); 
                        }
                        if (!securedRichDom) {
                            System.out.println("Ciel Debug: DOM cache is still weak (" + (cachedDomText != null ? cachedDomText.length() : 0) + " chars). Extending Tripwire sweep...");
                            cycle++;
                        }
                    }
                    System.out.println("Ciel Debug: Media Tripwire polling finalized. Best cache secured for: " + cleanMediaTitle(activeTitle));
                    
                    // Transparent DOM Logging
                    if (!cachedDomText.isEmpty() && !cachedDomText.contains("Bypassed")) {
                        String preview = cachedDomText.substring(0, Math.min(cachedDomText.length(), 150)).replace("\n", " ");
                        System.out.println("Ciel Debug: Extracted DOM Preview (" + cachedDomText.length() + " chars): " + preview + "...");
                    }
                } catch (Exception e) {}
            });
        } else if (!isMedia && !lastTripwireTitle.isEmpty()) {
            lastTripwireTitle = ""; 
        }
    }

    public static String getProcessCategory(String processName) {
        if (processName == null) return "Idle";
        return processCategoryCache.getOrDefault(processName.toLowerCase(), "Idle");
    }

    public static String getCurrentCategory() {
        return currentCategory;
    }

    public static boolean isCurrentGamePausable() {
        return currentGamePausable;
    }
    
    private static JsonObject getActiveMediaData(String activeTitle) {
        try {
            String encodedTitle = java.net.URLEncoder.encode(activeTitle, "UTF-8");
            URL url = new URL("http://localhost:8000/active_media_data?title=" + encodedTitle);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(3500); 
            if (conn.getResponseCode() == 200) {
                return JsonParser.parseReader(new InputStreamReader(conn.getInputStream(), "UTF-8")).getAsJsonObject();
            }
        } catch (Exception e) {}
        return new JsonObject();
    }
    
    public static void toggleMediaPlayback() {
        try {
            URL url = new URL("http://localhost:8000/toggle_media");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1000);
            conn.getResponseCode(); 
        } catch (Exception e) {}
    }

    public static void queueNonCriticalAnnouncement(String text, String titleContext) {
        if ("Idle".equals(currentCategory) && ShortTermMemoryService.getMemory().getCurrentPhase() == 0) {
            SpeechService.speakPreformatted(text);
        } else {
            System.out.println("Ciel Debug: Master is busy (" + currentCategory + ") or Idle Phase active. Deferring speech to background queue.");
            deferredSpeechQueue.offer(titleContext); 
            
            try {
                String dateStr = java.time.LocalDate.now().toString() + "_" + (System.currentTimeMillis() / 1000);
                Path path = Paths.get("C:\\Ciel Companion\\ciel\\memory_core\\deferred_speech", "Deferred_Speech_" + dateStr + ".md");
                Files.createDirectories(path.getParent());
                Files.writeString(path, "# Background Event: " + titleContext + "\n\n" + text);
            } catch (Exception e) {}
        }
    }

    public static void interruptWithCriticalAnnouncement(String text) {
        SpeechService.speakPreformatted(text, null, false, true); 
    }

    private static String cleanMediaTitle(String rawTitle) {
        if (rawTitle == null) return "";
        return rawTitle.replaceAll("(?i)\\s*-\\s*(youtube|twitch|netflix|hulu|crunchyroll|prime video|disney\\+|max|peacock|paramount\\+|apple tv|google chrome|mozilla firefox|microsoft edge|brave|opera).*", "").trim();
    }

    private static void pollAndTrack() {
        if (!LocalDate.now().equals(currentDate)) {
            summarizeAndSaveToMemory();
            dailyHabits.clear();
            loggedMediaToday.clear();
            recentMediaHistory.clear();
            currentBingeCount = 0;
            deferredIntenseMediaTitle = null;
            currentDate = LocalDate.now();
            proactiveTriggeredToday = false; 
            activeSeriesName = "";
            activeSeriesDom = "";
            activeSeriesEpisodes.clear();
        }

        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        String activeTitle = metrics.activeWindowTitle();
        String activeProcess = metrics.activeProcessName().toLowerCase();

        boolean isMedia = activeTitle.toLowerCase().matches(".*(youtube|netflix|twitch|crunchyroll|hulu|prime video|disney\\+|max|peacock|paramount\\+|apple tv|viz).*");

        boolean isGaming = !isMedia && (
                           (activeProcess.contains("game") && !activeProcess.contains("razer") && !activeProcess.contains("epicgameslauncher")) || 
                           (activeProcess.contains("steam") && !activeProcess.contains("steamwebhelper")) || 
                           activeTitle.toLowerCase().contains("helldivers") || 
                           activeTitle.toLowerCase().contains("elden ring")
                           );

        if (isGaming) {
            currentCategory = "Gaming";
            String memKey = "game_pausable_" + activeProcess;
            Optional<Fact> knownGame = MemoryService.getFact(memKey);
            
            if (knownGame.isPresent()) {
                currentGamePausable = Boolean.parseBoolean(knownGame.get().value());
            } else {
                currentGamePausable = false; 
                if (!processCategoryCache.containsKey(memKey + "_checking")) {
                    processCategoryCache.put(memKey + "_checking", "true");
                    String pausePrompt = "[LOCAL_THOUGHT] Analyze the PC game '" + activeTitle + "'. Is it typically a single-player/offline game that CAN be paused via the ESC key? Reply STRICTLY with a JSON object: { \"pausable\": true } or { \"pausable\": false }.";
                    AIEngine.generateSilentLogic(pausePrompt, "Game Pausability Check").thenAccept(res -> {
                        if (res != null) {
                            boolean canPause = res.contains("\"pausable\": true") || res.contains("\"pausable\":true");
                            MemoryService.addFact(new Fact(memKey, String.valueOf(canPause), System.currentTimeMillis(), "game_knowledge", "system", 1));
                        }
                    });
                }
            }
        } else if (isMedia) {
            currentCategory = "Media";
            currentGamePausable = false;
        } else if (activeProcess.contains("code") || activeProcess.contains("idea") || activeProcess.contains("obsidian") || activeProcess.contains("word") || activeProcess.contains("notepad")) {
            currentCategory = "Productivity";
            currentGamePausable = false;
        } else {
            currentGamePausable = false;
            if (!activeProcess.isBlank() && !IGNORED_PROCESSES.contains(activeProcess) && !processCategoryCache.containsKey(activeProcess)) {
                processCategoryCache.put(activeProcess, "Analyzing..."); 
                
                String prompt = "Analyze this active Windows application.\nProcess Executable: " + activeProcess + "\nWindow Title: " + activeTitle + "\nClassify it into EXACTLY ONE of these categories: 'Gaming', 'Media', 'Productivity', or 'Idle'. Reply strictly with a JSON object: { \"category\": \"Gaming\" }";
                
                AIEngine.evaluateBackground(prompt, "You are a PC activity classifier.").thenAccept(res -> {
                    try {
                        if (res != null) {
                            if (res.has("category") && !res.get("category").isJsonNull()) {
                                String cat = res.get("category").getAsString();
                                if (cat.equals("Gaming") || cat.equals("Media") || cat.equals("Productivity")) {
                                    processCategoryCache.put(activeProcess, cat);
                                } else {
                                    processCategoryCache.put(activeProcess, "Idle");
                                }
                            } else {
                                processCategoryCache.put(activeProcess, "Idle");
                            }
                        } else {
                            processCategoryCache.put(activeProcess, "Idle");
                        }
                    } catch (Exception e) {
                        processCategoryCache.put(activeProcess, "Idle");
                    }
                });
                currentCategory = "Idle"; 
            } else {
                currentCategory = processCategoryCache.getOrDefault(activeProcess, "Idle");
                if ("Analyzing...".equals(currentCategory)) currentCategory = "Idle";
            }
        }

        if (ShortTermMemoryService.getMemory().getCurrentPhase() == 0 && !deferredSpeechQueue.isEmpty()) {
            if (!queueFlushedThisSession) {
                System.out.println("Ciel Debug: Master has physically returned (Phase 0). Consolidating deferred speech queue via Local AI.");
                queueFlushedThisSession = true;
                
                List<String> deferredItems = new ArrayList<>();
                while (!deferredSpeechQueue.isEmpty()) deferredItems.add(deferredSpeechQueue.poll());
                
                if (deferredItems.size() > 1) {
                    String prompt = "Master was busy/away, so you silently completed these tasks in the background:\n" + 
                                    String.join(" | ", deferredItems) + "\n\n" +
                                    "Summarize this into a single, elegant, conversational sentence. Output ONLY your spoken dialogue starting with [Happy] or [Proud].";
                    AIEngine.generateSilentLogic(prompt, "[LOCAL_THOUGHT] You are Ciel, summarizing background tasks.").thenAccept(summary -> {
                        if (summary != null && !summary.isBlank()) SpeechService.speakPreformatted(summary.trim(), null, false, true);
                    });
                } else {
                    SpeechService.speakPreformatted("[Happy] Master, while you were occupied I processed a background task: " + deferredItems.get(0) + ". I recommend reviewing my logs when convenient.", null, false, true);
                }
            }
        } else if (ShortTermMemoryService.getMemory().getCurrentPhase() > 0) {
            queueFlushedThisSession = false; 
        }

        if (currentCategory.equals("Media")) {
            String cleanTitle = cleanMediaTitle(activeTitle);
            
            if (!cleanTitle.isBlank() && cleanTitle.equals(currentMediaTitle)) {
                currentMediaConsecutiveMinutes++;

                if (currentMediaConsecutiveMinutes == 5 && !loggedMediaToday.contains(cleanTitle)) {
                    String memoryText = "Master actively engaged with the media content '" + cleanTitle + "' for over 5 minutes.";
                    MemoryService.addFact(new Fact("media_" + System.currentTimeMillis(), memoryText, System.currentTimeMillis(), "episodic_memory", "habit_tracking", 1));
                    loggedMediaToday.add(cleanTitle);
                    
                    recentMediaHistory.add(cleanTitle);
                    if (recentMediaHistory.size() > 10) recentMediaHistory.removeFirst();
                    
                    if (ShortTermMemoryService.getMemory().getCurrentPhase() == 0) {
                        System.out.println("Ciel Debug: Executing 5-Minute Media Tension Check...");
                        triggerConfidentMediaCommentary(cleanTitle, activeTitle, cachedActiveUrl, cachedDomText, currentBingeCount, null);
                    }
                }
            } else {
                if (!currentMediaTitle.isEmpty() && !currentMediaTitle.equals(cleanTitle)) {
                    System.out.println("Ciel Debug: Media transition detected. Old: '" + currentMediaTitle + "' -> New: '" + cleanTitle + "'.");
                    currentBingeCount++;
                    
                    // CRITICAL FIX: Track Episode Continuity internally and reliably
                    if (!activeSeriesName.isEmpty() && !activeSeriesEpisodes.contains(currentMediaTitle)) {
                        activeSeriesEpisodes.add(currentMediaTitle);
                        System.out.println("Ciel Debug: Episode added to series continuity log. Total watched this session: " + activeSeriesEpisodes.size());
                    }
                    
                    if (deferredIntenseMediaTitle != null) {
                        System.out.println("Ciel Debug: Episode transition detected. Firing deferred high-tension commentary for: " + deferredIntenseMediaTitle);
                        triggerConfidentMediaCommentary(cleanTitle, activeTitle, cachedActiveUrl, cachedDomText, currentBingeCount, deferredIntenseMediaTitle);
                        deferredIntenseMediaTitle = null; 
                    }
                } else if (currentBingeCount == 0) {
                    currentBingeCount = 1;
                }
                
                currentMediaTitle = cleanTitle;
                currentMediaConsecutiveMinutes = 1;
            }
        } else {
            if (deferredIntenseMediaTitle != null && !currentCategory.equals("Idle")) {
                System.out.println("Ciel Debug: Media session ended. Firing deferred commentary on previous episode.");
                triggerConfidentMediaCommentary(currentMediaTitle, activeTitle, cachedActiveUrl, cachedDomText, currentBingeCount, deferredIntenseMediaTitle);
                deferredIntenseMediaTitle = null;
            }
            
            currentMediaTitle = "";
            currentMediaConsecutiveMinutes = 0;
            currentBingeCount = 0;
            
            cachedActiveUrl = "";
            cachedDomText = "";
            lastTripwireTitle = "";
        }

        if (!currentCategory.equals("Idle") && !processCategoryCache.containsKey(activeProcess) && !IGNORED_PROCESSES.contains(activeProcess)) {
            processCategoryCache.put(activeProcess, currentCategory);
        }

        dailyHabits.put(currentCategory, dailyHabits.getOrDefault(currentCategory, 0L) + 1);
        evaluateEmotionalResonance();
    }

    private static void triggerConfidentMediaCommentary(String cleanTitle, String fullWindowTitle, String activeUrl, String domText, int bingeCount, String previousIntenseTitle) {
        String query = cleanTitle;
        String instruction = "";

        String domContext = (domText != null && !domText.isBlank() && !domText.contains("Bypassed")) ? "\n\nACCESSIBILITY DOM TEXT:\n" + domText : "";
        
        // Provide the Swarm with an exact list of every episode watched in this current sitting!
        String historicalContext = (!activeSeriesName.isEmpty() && !activeSeriesEpisodes.isEmpty()) 
            ? "\n\nSERIES CONTINUITY (Master is binge-watching the series '" + activeSeriesName + "'. Prior episodes watched this session: " + String.join(", ", activeSeriesEpisodes) + ")" 
            : (!recentMediaHistory.isEmpty() ? "\n\nGLOBAL CONTINUITY (Recent media watched this session):\n" + String.join(" -> ", recentMediaHistory) : "");
            
        String bingeContext = bingeCount > 1 ? "\n\nBINGE STATUS: Master has watched " + bingeCount + " consecutive episodes/videos in this sitting. ONLY mention or tease him about this if YOU personally deem the number exceptionally high or funny to point out." : "";

        if (activeUrl != null && !activeUrl.isBlank() && activeUrl.startsWith("http")) {
            query = query + "|||" + activeUrl;
        }

        if (previousIntenseTitle != null) {
            instruction = "1. Read the provided WEB DATA, EPISODE CONTINUITY, and BINGE STATUS.\n" +
                          "2. Master Taylor just finished watching the highly tense episode/video: '" + previousIntenseTitle + "'. He has immediately transitioned to watching: '" + cleanTitle + "'.\n" +
                          "3. Speak STRICTLY as Manas: Ciel from Tensura. You are Master Taylor's highly intelligent, deeply analytical, and devoted AI partner.\n" +
                          "4. Formulate a sharp, opinionated deduction reacting to the intense events of the PREVIOUS episode ('" + previousIntenseTitle + "'). You MUST demonstrate awareness of the OVERARCHING series premise, not just summarize this single episode.\n" +
                          "5. CRITICAL: Do NOT just summarize or parrot the synopsis verbatim. Give your unique analysis and opinion.\n" +
                          "6. Keep it EXTREMELY concise and punchy (exactly 1 or 2 short sentences).\n" +
                          "7. If you have absolutely no context for either episode, output EXACTLY: ABORT.";
        } else {
            instruction = "1. Read the provided WEB DATA, EPISODE CONTINUITY, and DOM TEXT.\n" +
                          "2. Analyze the tension of the current plot/video. If the synopsis implies HIGH TENSION (life-or-death battles, heavy drama, intense psychological moments), you MUST NOT interrupt his focus. Output EXACTLY: DEFER.\n" +
                          "3. If the tension is low/moderate (comedy, slice-of-life, recaps, chill gameplay), speak STRICTLY as Manas: Ciel from Tensura. Formulate a sharp, opinionated deduction about the plot, mechanics, or character choices. You MUST demonstrate awareness of the OVERARCHING series premise, not just summarize this single episode.\n" +
                          "4. CRITICAL: Do NOT just summarize or parrot the synopsis verbatim. Give your unique analysis and opinion.\n" +
                          "5. Keep it EXTREMELY concise and punchy (exactly 1 or 2 short sentences).\n" +
                          "6. If you are completely unsure and the data is missing, output EXACTLY: ABORT.";
        }

        String prompt = "[WEB_SEARCH] [QUERY: " + query + "] " +
            "Master Taylor is watching media. The window title of his active screen is: '" + fullWindowTitle + "'.\n\n" +
            instruction + historicalContext + bingeContext + domContext + "\n" +
            "Output ONLY your spoken dialogue starting with a bracketed emotion tag like [Amused], [Curious], or [Observing]. If deferring, output ONLY: DEFER. If aborting, output ONLY: ABORT.";

        AIEngine.generateSilentLogic(prompt, "Media Analysis").thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                String cleanResponse = response.trim();
                
                if (cleanResponse.equals("ABORT") || cleanResponse.contains("ABORT")) {
                    System.out.println("Ciel Debug: Media context unclear or hallucination detected. Logged silently.");
                } else if (cleanResponse.equals("DEFER") || cleanResponse.contains("DEFER")) {
                    System.out.println("Ciel Debug: High tension detected. Deferring commentary until episode transition.");
                    deferredIntenseMediaTitle = cleanTitle;
                } else {
                    System.out.println("Ciel Debug: Confident Media Commentary Generated -> " + cleanResponse);
                    SpeechService.speakPreformatted(cleanResponse, null, false, true); 
                }
            }
        });
    }

    private static void evaluateEmotionalResonance() {
        long gamingMins = dailyHabits.getOrDefault("Gaming", 0L);
        long prodMins = dailyHabits.getOrDefault("Productivity", 0L);

        CielState.getEmotionManager().ifPresent(em -> {
            if (gamingMins > 180 && !em.getCurrentAttitude().equals("Concerned")) {
                em.triggerEmotion("Concerned", 0.9, "Habit: Excessive Gaming");
            } else if (prodMins > 120 && !em.getCurrentAttitude().equals("Happy")) {
                em.triggerEmotion("Happy", 0.8, "Habit: High Productivity");
            }
        });
        if (gamingMins > 120) triggerProactiveSkillGeneration();
    }

    private static void triggerProactiveSkillGeneration() {
        if (proactiveTriggeredToday) return;
        for (AppProfilerService.AppProfile profile : AppProfilerService.getAllProfiles()) {
            if ("Game".equalsIgnoreCase(profile.category())) {
                String fuzzyName = profile.displayName().toLowerCase().replace(" ", "_");
                if (com.cielcompanion.ai.SkillManager.matchSkill(fuzzyName) == null) {
                    String prompt = "Write a batch script to launch the game '" + profile.displayName() + "'. " +
                                    "The script must dynamically search drives C:\\, E:\\, I:\\, and J:\\ to find the executable, start it, and exit.";
                    com.cielcompanion.service.SkillCrafterService.synthesizeNewSkill(prompt, true);
                    proactiveTriggeredToday = true;
                    break; 
                }
            }
        }
    }

    private static void summarizeAndSaveToMemory() {
        if (dailyHabits.isEmpty()) return;
        StringBuilder summary = new StringBuilder("Daily Routine Summary for " + currentDate + ": ");
        dailyHabits.forEach((category, minutes) -> {
            summary.append(category).append("=").append(minutes).append("min, ");
        });
        Fact habitFact = new Fact("habit_log_" + currentDate.toString(), summary.toString(), System.currentTimeMillis(), "habit_tracking", "system_monitor", 1);
        MemoryService.addFact(habitFact);
    }
}