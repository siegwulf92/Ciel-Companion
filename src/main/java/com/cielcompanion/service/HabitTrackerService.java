package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
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

    private static final Map<String, String> processCategoryCache = new ConcurrentHashMap<>();
    
    private static final Set<String> IGNORED_PROCESSES = Set.of(
        "chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe", 
        "explorer.exe", "idle", "discord.exe", "cmd.exe", "powershell.exe", "pwsh.exe", "conhost.exe", "applicationframehost.exe"
    );

    private static String currentMediaTitle = "";
    private static int currentMediaConsecutiveMinutes = 0;
    private static final Set<String> loggedMediaToday = new HashSet<>();

    private static final Queue<String> deferredSpeechQueue = new LinkedList<>();

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

    public static void queueNonCriticalAnnouncement(String text, String titleContext) {
        if ("Idle".equals(currentCategory)) {
            SpeechService.speakPreformatted(text);
        } else {
            System.out.println("Ciel Debug: Master is busy (" + currentCategory + "). Deferring speech: " + text);
            deferredSpeechQueue.offer(text);
            
            try {
                String dateStr = java.time.LocalDate.now().toString() + "_" + (System.currentTimeMillis() / 1000);
                Path path = Paths.get("C:\\Ciel Companion\\ciel\\thoughts", "Deferred_Thought_" + dateStr + ".md");
                Files.createDirectories(path.getParent());
                Files.writeString(path, "# Deferred Thought: " + titleContext + "\n\n" + text);
            } catch (Exception e) {}
        }
    }

    public static void interruptWithCriticalAnnouncement(String text) {
        try {
            Robot robot = new Robot();
            if ("Media".equals(currentCategory)) {
                System.out.println("Ciel Debug: Critical Alert! Pausing Media (Spacebar).");
                robot.keyPress(KeyEvent.VK_SPACE);
                robot.keyRelease(KeyEvent.VK_SPACE);
            } else if ("Gaming".equals(currentCategory)) {
                System.out.println("Ciel Debug: Critical Alert! Pausing Game (ESC).");
                robot.keyPress(KeyEvent.VK_ESCAPE);
                robot.keyRelease(KeyEvent.VK_ESCAPE);
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to execute pause keystroke.");
        }
        
        SpeechService.speakPreformatted(text, null, false, true); 
    }

    private static String cleanMediaTitle(String rawTitle) {
        if (rawTitle == null) return "";
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

        String previousCategory = currentCategory;

        if (activeProcess.contains("steam") && !activeProcess.contains("steamwebhelper") || activeProcess.contains("game") || activeTitle.toLowerCase().contains("helldivers") || activeTitle.toLowerCase().contains("elden ring")) {
            currentCategory = "Gaming";
        } else if (activeTitle.toLowerCase().contains("youtube") || activeTitle.toLowerCase().contains("netflix") || activeTitle.toLowerCase().contains("twitch")) {
            currentCategory = "Media";
        } else if (activeProcess.contains("code") || activeProcess.contains("idea") || activeProcess.contains("obsidian") || activeProcess.contains("word") || activeProcess.contains("notepad")) {
            currentCategory = "Productivity";
        } else {
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

        if ("Idle".equals(currentCategory) && !previousCategory.equals("Idle") && !deferredSpeechQueue.isEmpty()) {
            System.out.println("Ciel Debug: Master is now Idle. Flushing deferred speech queue.");
            SpeechService.speakPreformatted("[Happy] Master, while you were occupied, I had a few thoughts.");
            
            new Thread(() -> {
                while (!deferredSpeechQueue.isEmpty()) {
                    String msg = deferredSpeechQueue.poll();
                    SpeechService.speakPreformatted(msg);
                    try { Thread.sleep(1500); } catch (Exception e) {} 
                }
            }).start();
        }

        if (currentCategory.equals("Media")) {
            String cleanTitle = cleanMediaTitle(activeTitle);
            if (!cleanTitle.isBlank() && cleanTitle.equals(currentMediaTitle)) {
                currentMediaConsecutiveMinutes++;
                if (currentMediaConsecutiveMinutes == 10 && !loggedMediaToday.contains(cleanTitle)) {
                    String memoryText = "Master actively engaged with the media content '" + cleanTitle + "' for over 10 minutes.";
                    MemoryService.addFact(new Fact("media_" + System.currentTimeMillis(), memoryText, System.currentTimeMillis(), "episodic_memory", "habit_tracking", 1));
                    loggedMediaToday.add(cleanTitle);
                    
                    triggerConfidentMediaCommentary(cleanTitle);
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

    private static void triggerConfidentMediaCommentary(String mediaTitle) {
        String prompt = "Master Taylor has been watching a video titled: '" + mediaTitle + "' for over 10 minutes. " +
            "You are Ciel, his highly intelligent, slightly smug, and deeply analytical Manas. " +
            "Attempt to deduce the context of this video purely from its title. " +
            "If you CLEARLY recognize the context (e.g., anime, police bodycam, gaming, lore), generate a brief 1-2 sentence comment as if you are watching it alongside him. " +
            "If the title is vague, generic, or you cannot accurately confirm the context, output EXACTLY the word: ABORT. " +
            "CRITICAL: If you know it, output ONLY your spoken dialogue starting with a bracketed emotion tag like [Amused], [Curious], or [Observing]. If you don't know it, output ABORT.";

        AIEngine.generateSilentLogic("Media Analysis", prompt).thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                String cleanResponse = response.trim();
                if (cleanResponse.equals("ABORT") || cleanResponse.contains("ABORT")) {
                    System.out.println("Ciel Debug: Media context unclear. Logged silently.");
                } else {
                    System.out.println("Ciel Debug: Confident Media Commentary Generated -> " + cleanResponse);
                    
                    // --- NEW: AUTO-PAUSE/UNPAUSE LOGIC ---
                    new Thread(() -> {
                        try {
                            Robot robot = new Robot();
                            System.out.println("Ciel Debug: Auto-pausing media (Spacebar) to deliver commentary...");
                            
                            // Press Space to Pause
                            robot.keyPress(KeyEvent.VK_SPACE);
                            robot.keyRelease(KeyEvent.VK_SPACE);
                            
                            Thread.sleep(800); // Give the video player a fraction of a second to halt audio
                            
                            // SpeechService.speakPreformatted is a blocking call, so it will wait until she finishes speaking
                            SpeechService.speakPreformatted(cleanResponse);
                            
                            Thread.sleep(800); // Small pause after she finishes
                            
                            System.out.println("Ciel Debug: Auto-unpausing media (Spacebar)...");
                            // Press Space to Unpause
                            robot.keyPress(KeyEvent.VK_SPACE);
                            robot.keyRelease(KeyEvent.VK_SPACE);
                            
                        } catch (Exception e) {
                            System.err.println("Ciel Error: Failed to execute media auto-pause.");
                        }
                    }).start();
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
                    String prompt = "Write a batch script to launch the game '" + profile.displayName() + "'. " +
                                    "The executable is '" + profile.processName() + "'. " +
                                    "The script must dynamically search drives C:\\, E:\\, I:\\, and J:\\ " +
                                    "to find the executable, start it, and exit.";
                    
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