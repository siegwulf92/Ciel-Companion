package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HabitTrackerService {

    private static ScheduledExecutorService habitScheduler;
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

    // --- PRE-FULLSCREEN CACHE SYSTEM ---
    // Caches the DOM and URL the moment a video starts, ensuring data is preserved even if the user goes fullscreen.
    private static String cachedActiveUrl = "";
    private static String cachedDomText = "";

    public static void initialize() {
        habitScheduler = Executors.newSingleThreadScheduledExecutor();
        habitScheduler.scheduleWithFixedDelay(HabitTrackerService::pollAndTrack, 1, 1, TimeUnit.MINUTES);
        System.out.println("Ciel Debug: Habit Tracker Service initialized. Monitoring behavior patterns.");
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
            conn.setReadTimeout(2500); 
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
            currentDate = LocalDate.now();
            proactiveTriggeredToday = false; 
        }

        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        String activeTitle = metrics.activeWindowTitle();
        String activeProcess = metrics.activeProcessName().toLowerCase();

        boolean isGaming = (activeProcess.contains("game") && !activeProcess.contains("razer") && !activeProcess.contains("epicgameslauncher")) || 
                           (activeProcess.contains("steam") && !activeProcess.contains("steamwebhelper")) || 
                           activeTitle.toLowerCase().contains("helldivers") || 
                           activeTitle.toLowerCase().contains("elden ring");

        boolean isMedia = activeTitle.toLowerCase().matches(".*(youtube|netflix|twitch|crunchyroll|hulu|prime video|disney\\+|max|peacock|paramount\\+|apple tv).*");

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
                    String pausePrompt = "[LOCAL_THOUGHT] Analyze the PC game '" + activeTitle + "'. Is it typically a single-player/offline game that CAN be paused via the ESC key (like Final Fantasy, Kingdom Hearts, Skyrim), or a live online/multiplayer game that CANNOT be paused (like Smite, Dead by Daylight, Marvel Rivals, Call of Duty)? Reply STRICTLY with a JSON object: { \"pausable\": true } or { \"pausable\": false }.";
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
                String prompt = "Analyze this active Windows application.\n" +
                                "Process Executable: " + activeProcess + "\n" +
                                "Window Title: " + activeTitle + "\n" +
                                "Classify it into EXACTLY ONE of these categories: 'Gaming', 'Media', 'Productivity', or 'Idle'. Reply strictly with a JSON object: { \"category\": \"Gaming\" }";
                AIEngine.evaluateBackground(prompt, "You are a PC activity classifier.").thenAccept(res -> {
                    if (res != null && res.has("category")) {
                        String cat = res.get("category").getAsString();
                        if (cat.equals("Gaming") || cat.equals("Media") || cat.equals("Productivity")) processCategoryCache.put(activeProcess, cat);
                        else processCategoryCache.put(activeProcess, "Idle");
                    } else processCategoryCache.put(activeProcess, "Idle");
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
                                    "Summarize this into a single, elegant, conversational sentence. Tell Master Taylor that while he was occupied, you processed these things and he should review your thought logs when convenient. " +
                                    "CRITICAL: Output ONLY your spoken dialogue starting with [Happy] or [Proud]. Do NOT list them one by one.";
                                    
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

        // --- MEDIA TRACKING & CACHING LOGIC ---
        if (currentCategory.equals("Media")) {
            String cleanTitle = cleanMediaTitle(activeTitle);
            if (!cleanTitle.isBlank() && cleanTitle.equals(currentMediaTitle)) {
                currentMediaConsecutiveMinutes++;
                
                // Cache Update Phase (Minutes 1-4)
                if (currentMediaConsecutiveMinutes <= 5) {
                    JsonObject mediaData = getActiveMediaData(activeTitle);
                    String tempUrl = mediaData.has("url") && !mediaData.get("url").isJsonNull() ? mediaData.get("url").getAsString() : null;
                    String tempDom = mediaData.has("dom") && !mediaData.get("dom").isJsonNull() ? mediaData.get("dom").getAsString() : null;

                    if (tempUrl != null && !tempUrl.isBlank() && !tempUrl.equals("null")) cachedActiveUrl = tempUrl;
                    if (tempDom != null && !tempDom.isBlank() && tempDom.length() > 10) cachedDomText = tempDom;
                }

                // Commentary Phase (Minute 5)
                if (currentMediaConsecutiveMinutes == 5 && !loggedMediaToday.contains(cleanTitle)) {
                    String memoryText = "Master actively engaged with the media content '" + cleanTitle + "' for over 5 minutes.";
                    MemoryService.addFact(new Fact("media_" + System.currentTimeMillis(), memoryText, System.currentTimeMillis(), "episodic_memory", "habit_tracking", 1));
                    loggedMediaToday.add(cleanTitle);
                    
                    if (ShortTermMemoryService.getMemory().getCurrentPhase() == 0) {
                        System.out.println("Ciel Debug: Pre-Fullscreen Cache utilized -> URL: " + cachedActiveUrl);
                        if (cachedDomText != null && !cachedDomText.isBlank()) {
                            System.out.println("Ciel Debug: Cached DOM Extracted: " + cachedDomText.substring(0, Math.min(cachedDomText.length(), 150)) + "...");
                        }
                        
                        triggerConfidentMediaCommentary(cleanTitle, activeTitle, cachedActiveUrl, cachedDomText);
                    } else {
                        System.out.println("Ciel Debug: Media threshold reached, but Master is physically Idle. Skipping commentary.");
                    }
                }
            } else {
                currentMediaTitle = cleanTitle;
                currentMediaConsecutiveMinutes = 1;
                
                // Reset cache on new media and instantly grab Minute 1 snapshot
                cachedActiveUrl = "";
                cachedDomText = "";
                JsonObject mediaData = getActiveMediaData(activeTitle);
                if (mediaData.has("url") && !mediaData.get("url").isJsonNull()) cachedActiveUrl = mediaData.get("url").getAsString();
                if (mediaData.has("dom") && !mediaData.get("dom").isJsonNull()) cachedDomText = mediaData.get("dom").getAsString();
            }
        } else {
            currentMediaTitle = "";
            currentMediaConsecutiveMinutes = 0;
            cachedActiveUrl = "";
            cachedDomText = "";
        }

        if (!currentCategory.equals("Idle") && !processCategoryCache.containsKey(activeProcess) && !IGNORED_PROCESSES.contains(activeProcess)) {
            processCategoryCache.put(activeProcess, currentCategory);
        }

        dailyHabits.put(currentCategory, dailyHabits.getOrDefault(currentCategory, 0L) + 1);
        evaluateEmotionalResonance();
    }

    private static void triggerConfidentMediaCommentary(String cleanTitle, String fullWindowTitle, String activeUrl, String domText) {
        String query = cleanTitle;
        String instruction = "";

        String domContext = (domText != null && !domText.isBlank()) ? "\n\nACCESSIBILITY DOM TEXT (Series Name is likely in here):\n" + domText : "";

        if (activeUrl != null && !activeUrl.isBlank() && activeUrl.startsWith("http")) {
            System.out.println("Ciel Debug: Using Media URL for context: " + activeUrl);
            query = query + "|||" + activeUrl;
            
            instruction = "1. Read the provided WEB DATA and the ACCESSIBILITY DOM TEXT. The DOM text contains the exact Series Name and Episode Title currently on screen.\n" +
                          "2. Identify the exact anime series or YouTube creator to avoid hallucinations (e.g., ATLA vs Tensura vs Hell's Paradise).\n" +
                          "3. Generate a brief, conversational comment (1-2 short lines) reacting to a specific detail in the synopsis, the creator, or the current events.\n" +
                          "4. CRITICAL: If the WEB DATA is blocked or fails, you MUST rely entirely on your internal training data to summarize the episode identified in the DOM text.\n" +
                          "5. Keep it EXTREMELY concise and natural. NO wordy, robotic essays.\n" +
                          "6. If both the WEB DATA and DOM text are completely missing/useless, output EXACTLY: ABORT.";
        } else {
            System.out.println("Ciel Debug: Media URL unavailable. Trusting LLM and raw DOM context.");
            instruction = "1. Use the WEB DATA and the ACCESSIBILITY DOM TEXT to identify the EXACT anime series/creator and plot.\n" +
                          "2. Generate a brief, conversational comment (1-2 short lines) reflecting on the CURRENT events.\n" +
                          "3. Keep it EXTREMELY concise and natural. NO wordy, robotic essays.\n" +
                          "4. CRITICAL ANTI-HALLUCINATION: If the WEB DATA is blocked, you MUST rely entirely on your internal training data using the Series Name from the DOM text and the Raw Window Title.\n" +
                          "5. If you are completely unsure and multiple anime match this exact criteria, output EXACTLY: ABORT.";
        }

        String prompt = "[WEB_SEARCH] [QUERY: " + query + "] " +
            "Master Taylor is watching media. The raw window title on his screen is: '" + fullWindowTitle + "'. " +
            "You are Ciel, his highly intelligent, slightly smug, and deeply analytical Manas.\n\n" +
            instruction + domContext + "\n" +
            "Output ONLY your spoken dialogue starting with a bracketed emotion tag like [Amused], [Curious], or [Observing].";

        AIEngine.generateSilentLogic("Media Analysis", prompt).thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                String cleanResponse = response.trim();
                if (cleanResponse.equals("ABORT") || cleanResponse.contains("ABORT")) {
                    System.out.println("Ciel Debug: Media context unclear or hallucination detected. Logged silently.");
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