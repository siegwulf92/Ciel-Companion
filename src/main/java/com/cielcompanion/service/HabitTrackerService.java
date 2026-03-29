package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    // AI Dynamic Process Cache Failsafe
    private static final Map<String, String> processCategoryCache = new ConcurrentHashMap<>();
    
    private static final Set<String> IGNORED_PROCESSES = Set.of(
        "chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe", 
        "explorer.exe", "idle", "discord.exe", "cmd.exe", "powershell.exe", "pwsh.exe", "conhost.exe"
    );

    // --- NEW: Long-Form Media Tracking ---
    private static String currentMediaTitle = "";
    private static int currentMediaConsecutiveMinutes = 0;
    private static final Set<String> loggedMediaToday = new HashSet<>();

    public static void initialize() {
        habitScheduler = Executors.newSingleThreadScheduledExecutor();
        habitScheduler.scheduleWithFixedDelay(HabitTrackerService::pollAndTrack, 1, 1, TimeUnit.MINUTES);
        System.out.println("Ciel Debug: Habit Tracker Service initialized. Monitoring behavior patterns.");
    }

    public static String getProcessCategory(String processName) {
        if (processName == null) return "Idle";
        return processCategoryCache.getOrDefault(processName.toLowerCase(), "Idle");
    }

    private static String cleanMediaTitle(String rawTitle) {
        if (rawTitle == null) return "";
        // Strip out browser and website suffixes to get the pure content title
        return rawTitle.replaceAll("(?i)\\s*-\\s*(youtube|twitch|netflix|hulu|crunchyroll|google chrome|mozilla firefox|microsoft edge|brave|opera).*", "").trim();
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

        // 1. Fast Hardcoded Categorization
        if (activeProcess.contains("steam") && !activeProcess.contains("steamwebhelper") || activeProcess.contains("game") || activeTitle.toLowerCase().contains("helldivers") || activeTitle.toLowerCase().contains("elden ring")) {
            currentCategory = "Gaming";
        } else if (activeTitle.toLowerCase().contains("youtube") || activeTitle.toLowerCase().contains("netflix") || activeTitle.toLowerCase().contains("twitch")) {
            currentCategory = "Media";
        } else if (activeProcess.contains("code") || activeProcess.contains("idea") || activeProcess.contains("obsidian") || activeProcess.contains("word")) {
            currentCategory = "Productivity";
        } else {
            // 2. The AI Swarm Classifier Failsafe
            if (!activeProcess.isBlank() && !IGNORED_PROCESSES.contains(activeProcess) && !processCategoryCache.containsKey(activeProcess)) {
                processCategoryCache.put(activeProcess, "Analyzing..."); 
                
                String prompt = "Analyze this active Windows application.\n" +
                                "Process Executable: " + activeProcess + "\n" +
                                "Window Title: " + activeTitle + "\n" +
                                "Classify it into EXACTLY ONE of these categories: 'Gaming' (for video games), 'Media' (for video/audio players), 'Productivity' (for work/coding), or 'Idle' (for generic OS tasks or web browsing). " +
                                "Reply strictly with a JSON object: { \"category\": \"Gaming\" }";

                AIEngine.evaluateBackground(prompt, "You are a PC activity classifier.").thenAccept(res -> {
                    if (res != null && res.has("category")) {
                        String cat = res.get("category").getAsString();
                        if (cat.equals("Gaming") || cat.equals("Media") || cat.equals("Productivity")) {
                            processCategoryCache.put(activeProcess, cat);
                            System.out.println("Ciel Debug: AI Swarm Classified '" + activeProcess + "' as " + cat);
                        } else {
                            processCategoryCache.put(activeProcess, "Idle");
                        }
                    } else {
                        processCategoryCache.put(activeProcess, "Idle");
                    }
                });
                currentCategory = "Idle"; 
            } else {
                currentCategory = processCategoryCache.getOrDefault(activeProcess, "Idle");
                if ("Analyzing...".equals(currentCategory)) currentCategory = "Idle";
            }
        }

        // --- NEW: Media Engagement Tracking ---
        if (currentCategory.equals("Media")) {
            String cleanTitle = cleanMediaTitle(activeTitle);
            if (!cleanTitle.isBlank() && cleanTitle.equals(currentMediaTitle)) {
                currentMediaConsecutiveMinutes++;
                if (currentMediaConsecutiveMinutes == 10 && !loggedMediaToday.contains(cleanTitle)) {
                    String memoryText = "Master actively engaged with the media content '" + cleanTitle + "' for over 10 minutes.";
                    MemoryService.addFact(new Fact("media_" + System.currentTimeMillis(), memoryText, System.currentTimeMillis(), "episodic_memory", "habit_tracking", 1));
                    loggedMediaToday.add(cleanTitle);
                    System.out.println("Ciel Debug: Logged long-form media consumption to memory: " + cleanTitle);
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

    private static void evaluateEmotionalResonance() {
        long gamingMins = dailyHabits.getOrDefault("Gaming", 0L);
        long prodMins = dailyHabits.getOrDefault("Productivity", 0L);

        CielState.getEmotionManager().ifPresent(em -> {
            if (gamingMins > 180 && !em.getCurrentAttitude().equals("Concerned")) {
                em.triggerEmotion("Concerned", 0.9, "Habit: Excessive Gaming");
                System.out.println("Ciel Debug (Habit): Master has been gaming for 3+ hours. Mood shifted to Concerned.");
            } else if (prodMins > 120 && !em.getCurrentAttitude().equals("Happy")) {
                em.triggerEmotion("Happy", 0.8, "Habit: High Productivity");
                System.out.println("Ciel Debug (Habit): Master is highly productive today. Mood shifted to Happy.");
            }
        });
        
        if (gamingMins > 120) {
            triggerProactiveSkillGeneration();
        }
    }

    private static void triggerProactiveSkillGeneration() {
        if (proactiveTriggeredToday) return;

        for (AppProfilerService.AppProfile profile : AppProfilerService.getAllProfiles()) {
            if ("Game".equalsIgnoreCase(profile.category())) {
                String fuzzyName = profile.displayName().toLowerCase().replace(" ", "_");
                if (com.cielcompanion.ai.SkillManager.matchSkill(fuzzyName) == null) {
                    
                    System.out.println("Ciel Debug (Habit): Proactively generating launch skill for " + profile.displayName());
                    
                    String prompt = "Write a batch script to launch the game '" + profile.displayName() + "'. " +
                                    "The executable is '" + profile.processName() + "'. " +
                                    "The script must dynamically search drives C:\\, E:\\, I:\\, and J:\\ " +
                                    "to find the executable, start it, and exit.";
                    
                    SkillCrafterService.synthesizeNewSkill(prompt, true); // True = Silent Generation
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
        System.out.println("Ciel Debug: Daily habit log committed to memory core.");
    }
}