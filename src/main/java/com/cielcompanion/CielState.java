package com.cielcompanion;

import com.cielcompanion.mood.EmotionManager;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.ui.CielGui;
import java.util.Optional;

public class CielState {

    // --- Core State ---
    private static long nextSpeakAt = 0;
    private static long lastSpeakAt = 0;
    private static boolean lockedOut = false;
    private static boolean finalPlayed = false;
    private static volatile boolean bootGreetingPlayed = false;
    private static volatile boolean loginGreetingPlayed = false;
    private static long appStartTime = 0;
    private static OperatingMode currentMode = OperatingMode.INTEGRATED;
    private static boolean manuallyMuted = false;
    private static long muteConditionSince = 0; 
    private static boolean isWarmBoot = false; 
    private static boolean isPerformingColdShutdown = false;
    
    // DEBOUNCE STATE
    private static int consecutiveActiveTicks = 0; // NEW: Tracks sustained activity

    // --- Alert Tracking ---
    private static long highCpuSince = 0;
    
    // --- Logging & History ---
    private static String lastLoggedStatusString = "";

    // --- Astronomy State ---
    private static boolean hasPlayedAstronomyReport = false;
    private static boolean needsAstronomyApiFetch = true;
    private static int astronomyFetchAttempts = 0;

    // --- Emotion State ---
    private static double patience = 0.5; // Starts at a neutral 50%
    private static final double PATIENCE_DECAY_RATE_PER_SECOND = 0.0005; // Very slow decay

    // --- Service Hub ---
    private static CielGui cielGui;
    private static EmotionManager emotionManager;

    public static void initialize(boolean warmBoot) {
        appStartTime = System.currentTimeMillis();
        isWarmBoot = warmBoot;
        System.out.println("Ciel Debug: CielState initialized. App start time recorded. Warm Boot: " + isWarmBoot);

        if (emotionManager != null) {
            emotionManager.triggerEmotion("Focused", 1.0, "Initialization");
        }
    }

    // --- Getters ---
    public static long getNextSpeakAt() { return nextSpeakAt; }
    public static boolean isLockedOut() { return lockedOut; }
    public static boolean isFinalPlayed() { return finalPlayed; }
    public static boolean isBootGreetingPlayed() { return bootGreetingPlayed; }
    public static boolean isLoginGreetingPlayed() { return loginGreetingPlayed; }
    public static long getAppStartTime() { return appStartTime; }
    public static long getHighCpuSince() { return highCpuSince; }
    public static String getLastLoggedStatusString() { return lastLoggedStatusString; }
    public static Optional<CielGui> getCielGui() { return Optional.ofNullable(cielGui); }
    public static boolean hasPlayedAstronomyReport() { return hasPlayedAstronomyReport; }
    public static boolean needsAstronomyApiFetch() { return needsAstronomyApiFetch; }
    public static int getAstronomyFetchAttempts() { return astronomyFetchAttempts; }
    public static Optional<EmotionManager> getEmotionManager() { return Optional.ofNullable(emotionManager); }
    public static OperatingMode getCurrentMode() { return currentMode; }
    public static boolean isManuallyMuted() { return manuallyMuted; }
    public static long getMuteConditionSince() { return muteConditionSince; }
    public static boolean isWarmBoot() { return isWarmBoot; }
    public static boolean isPerformingColdShutdown() { return isPerformingColdShutdown; }
    public static int getConsecutiveActiveTicks() { return consecutiveActiveTicks; }
    public static double getPatience() { return patience; }

    // --- Setters ---
    public static void setNextSpeakAt(long timestamp) { nextSpeakAt = timestamp; }
    public static void setLastSpeakAt(long timestamp) { lastSpeakAt = timestamp; }
    public static void setLockedOut(boolean isLocked) { lockedOut = isLocked; }
    public static void setFinalPlayed(boolean hasPlayed) { finalPlayed = hasPlayed; }
    public static void setBootGreetingPlayed(boolean hasPlayed) { bootGreetingPlayed = hasPlayed; }
    public static void setLoginGreetingPlayed(boolean hasPlayed) { loginGreetingPlayed = hasPlayed; }
    public static void setHighCpuSince(long timestamp) { highCpuSince = timestamp; }
    public static void setLastLoggedStatusString(String status) { lastLoggedStatusString = status; }
    public static void setCielGui(CielGui gui) { cielGui = gui; }
    public static void setHasPlayedAstronomyReport(boolean hasPlayed) { hasPlayedAstronomyReport = hasPlayed; }
    public static void setNeedsAstronomyApiFetch(boolean needsFetch) { needsAstronomyApiFetch = needsFetch; }
    public static void incrementAstronomyFetchAttempts() { astronomyFetchAttempts++; }
    public static void resetAstronomyFetchAttempts() { astronomyFetchAttempts = 0; }
    public static void setEmotionManager(EmotionManager em) { emotionManager = em; }
    public static void setCurrentMode(OperatingMode mode) { currentMode = mode; }
    public static void setManuallyMuted(boolean muted) { manuallyMuted = muted; }
    public static void setMuteConditionSince(long timestamp) { muteConditionSince = timestamp; }
    public static void setPerformingColdShutdown(boolean isCold) { isPerformingColdShutdown = isCold; }
    public static void setConsecutiveActiveTicks(int ticks) { consecutiveActiveTicks = ticks; }
    public static void incrementConsecutiveActiveTicks() { consecutiveActiveTicks++; }
    public static void setPatience(double p) { patience = Math.max(0, Math.min(1.0, p)); }
    
    public static void increasePatience(double amount) {
        patience = Math.min(1.0, patience + amount);
        System.out.printf("Ciel Debug (Emotion): Patience increased to %.2f%n", patience);
    }

    public static void updatePatience(long deltaTimeMillis) {
        if (deltaTimeMillis <= 0) return;
        double decayAmount = (deltaTimeMillis / 1000.0) * PATIENCE_DECAY_RATE_PER_SECOND;
        patience = Math.max(0, patience - decayAmount);
    }
}