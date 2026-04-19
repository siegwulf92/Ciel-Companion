package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;

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

        if (currentCategory.equals("Media")) {
            String cleanTitle = cleanMediaTitle(activeTitle);
            if (!cleanTitle.isBlank() && cleanTitle.equals(currentMediaTitle)) {
                currentMediaConsecutiveMinutes++;
                if (currentMediaConsecutiveMinutes == 5 && !loggedMediaToday.contains(cleanTitle)) {
                    String memoryText = "Master actively engaged with the media content '" + cleanTitle + "' for over 5 minutes.";
                    MemoryService.addFact(new Fact("media_" + System.currentTimeMillis(), memoryText, System.currentTimeMillis(), "episodic_memory", "habit_tracking", 1));
                    loggedMediaToday.add(cleanTitle);
                    
                    if (ShortTermMemoryService.getMemory().getCurrentPhase() == 0) {
                        triggerConfidentMediaCommentary(cleanTitle, activeTitle);
                    } else {
                        System.out.println("Ciel Debug: Media threshold reached, but Master is physically Idle. Skipping commentary.");
                    }
                }
            } else {
                currentMediaTitle = cleanTitle;
                currentMediaConsecutiveMinutes = 1;
            }
        } else {
            currentMediaTitle = "";
            currentMediaConsecutiveMinutes = 0;
        }

        if (!currentCategory.equals("Idle") && !processCategoryCache.containsKey(activeProcess) && !IGNORED_PROCESSES.contains(activeProcess)) {
            processCategoryCache.put(activeProcess, currentCategory);
        }

        dailyHabits.put(currentCategory, dailyHabits.getOrDefault(currentCategory, 0L) + 1);
        evaluateEmotionalResonance();
    }

    private static void triggerConfidentMediaCommentary(String cleanTitle, String fullWindowTitle) {
        String platform = "Media";
        String querySuffix = "";
        String instruction = "Attempt to deduce the context of this video from its title. Use the provided WEB DATA to understand the topic. Generate a brief 1-2 sentence comment on the subject matter.";

        String lowerTitle = fullWindowTitle.toLowerCase();
        
        // DYNAMIC PLATFORM ROUTING: Prevents YouTube videos from being searched as Anime!
        if (lowerTitle.contains("crunchyroll")) {
            platform = "Crunchyroll";
            querySuffix = " anime series crunchyroll";
            instruction = "1. Use the WEB DATA to identify the EXACT parent anime series this episode belongs to. (Do not guess based on the episode name alone).\n" +
                          "2. ABSOLUTELY NO SPOILERS. Do not mention plot twists, reveals, or specific episode events.\n" +
                          "3. Generate a brief 1-2 sentence comment on the series' PREMISE, animation quality, or your anticipation of the show.";
        } else if (lowerTitle.contains("youtube")) {
            platform = "YouTube";
            querySuffix = " youtube video topic";
            instruction = "1. Use the WEB DATA to identify the topic, creator, or general context of this YouTube video.\n" +
                          "2. Generate a brief 1-2 sentence comment on the subject matter, showing interest or providing a slight analytical insight.";
        }

        String prompt = "[WEB_SEARCH] [QUERY: \"" + cleanTitle + "\"" + querySuffix + "] " +
            "Master Taylor has been watching a video on " + platform + " titled: '" + cleanTitle + "'. " +
            "You are Ciel, his highly intelligent, slightly smug, and deeply analytical Manas. " +
            instruction + "\n" +
            "4. Output ONLY your spoken dialogue starting with a bracketed emotion tag like [Amused], [Curious], or [Observing]. If you are unsure of the context, output EXACTLY: ABORT.";

        AIEngine.generateSilentLogic("Media Analysis", prompt).thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                String cleanResponse = response.trim();
                if (cleanResponse.equals("ABORT") || cleanResponse.contains("ABORT")) {
                    System.out.println("Ciel Debug: Media context unclear. Logged silently.");
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