package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HabitTrackerService {

    private static ScheduledExecutorService habitScheduler;
    private static final Map<String, Long> dailyHabits = new HashMap<>();
    private static String currentCategory = "Idle";
    private static LocalDate currentDate = LocalDate.now();

    public static void initialize() {
        habitScheduler = Executors.newSingleThreadScheduledExecutor();
        // Polling every 1 minute to calculate time spent
        habitScheduler.scheduleWithFixedDelay(HabitTrackerService::pollAndTrack, 1, 1, TimeUnit.MINUTES);
        System.out.println("Ciel Debug: Habit Tracker Service initialized. Monitoring behavior patterns.");
    }

    private static void pollAndTrack() {
        // Reset at midnight
        if (!LocalDate.now().equals(currentDate)) {
            summarizeAndSaveToMemory();
            dailyHabits.clear();
            currentDate = LocalDate.now();
        }

        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        String activeTitle = metrics.activeWindowTitle().toLowerCase();
        String activeProcess = metrics.activeProcessName().toLowerCase();

        // Categorize Activity
        if (activeProcess.contains("steam") || activeProcess.contains("game") || activeTitle.contains("helldivers") || activeTitle.contains("elden ring")) {
            currentCategory = "Gaming";
        } else if (activeTitle.contains("youtube") || activeTitle.contains("netflix") || activeTitle.contains("twitch")) {
            currentCategory = "Media";
        } else if (activeProcess.contains("code") || activeProcess.contains("idea") || activeProcess.contains("obsidian") || activeProcess.contains("word")) {
            currentCategory = "Productivity";
        } else {
            currentCategory = "Idle";
        }

        // Add 1 minute to the category
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