package com.cielcompanion.service;

import com.cielcompanion.CielCompanion;
import com.cielcompanion.service.Settings;
import com.cielcompanion.CielState;
import com.cielcompanion.memory.SpokenLine;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.AppLauncherService;
import com.cielcompanion.service.AppProfilerService;
import com.cielcompanion.service.AppProfilerService.AppProfile;
import com.cielcompanion.service.AstronomyService;
import com.cielcompanion.service.AstronomyService.AstronomyReport;
import com.cielcompanion.service.HabitTrackerService;
import com.cielcompanion.service.HolidayService;
import com.cielcompanion.service.LineManager;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.service.SpeechService;
import com.cielcompanion.service.SystemMonitor;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.service.VaultService;
import com.cielcompanion.util.EnglishNumber;
import com.cielcompanion.mood.MoodConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class CielController {

    private static final Random random = new Random();
    private static final int HIGH_CPU_THRESHOLD = 90;
    private static final long CPU_ALERT_COOLDOWN_MS = 60000;
    private static final int REQUIRED_ACTIVE_TICKS_FOR_RETURN = 1;
    private static final AtomicLong idleReportGeneration = new AtomicLong();

    private static final AppLauncherService appLauncher = new AppLauncherService();
    private static ScheduledExecutorService logoutScheduler;
    
    // --- AI Emotion Polling Trackers ---
    private static long lastEmotionPollTime = 0;
    private static long lastEmotionPollFailureLog;
    private static int suppressedEmotionPollFailures;
    
    // Diagnostic flags for logs
    private static boolean loggedWaitingFor5s = false;
    private static boolean loggedWaitingFor2m = false;
    private static long lastAudioIdleDiagnostic;
    private static String lastAudioIdleSignature = "";
    private static boolean sessionWasLocked;
    private static boolean laptopLockRequested;
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> majorAwayEvents =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static void noteMajorBackgroundActivity(String event) {
        if (event != null && !event.isBlank() && (sessionWasLocked || WindowsApiService.isSessionLocked())
                && majorAwayEvents.size() < 8) majorAwayEvents.add(event);
    }

    public static void checkAndSpeak() {
        try {
            if (WindowsApiService.isSessionLocked()) {
                if (!sessionWasLocked) {
                    sessionWasLocked = true;
                    majorAwayEvents.clear();
                    CielState.setBootGreetingPlayed(true);
                    CielState.setLoginGreetingPlayed(true);
                    idleReportGeneration.incrementAndGet();
                    SpeechService.cancelSequentialSpeech();
                    SpeechService.stopCurrentPlayback();
                    System.out.println("[Session] Windows locked; Ciel remains active and silent.");
                }
                return;
            }
            if (sessionWasLocked) {
                sessionWasLocked = false;
                laptopLockRequested = false;
                SystemMonitor.resetVirtualIdle();
                ShortTermMemoryService.getMemory().setCurrentPhase(0);
                ShortTermMemoryService.getMemory().setInPhase4Monologue(false);
                CielState.setFinalPlayed(false);
                CielState.setNextSpeakAt(System.currentTimeMillis() + 60_000);
                System.out.println("[Session] Windows unlocked; background monitoring resumed without an idle-return greeting.");
                if (!majorAwayEvents.isEmpty()) {
                    String summary = String.join("; ", majorAwayEvents);
                    majorAwayEvents.clear();
                    SpeechService.speakPreformatted("[Focused] Master, while you were away: " + summary + ".",
                            "session_return", false, false);
                }
                return;
            }
            long currentTime = System.currentTimeMillis();
            ShortTermMemory memory = ShortTermMemoryService.getMemory();
            SystemMetrics metrics = SystemMonitor.getSystemMetrics();
            if (metrics.idleTimeMinutes() < 2) laptopLockRequested = false;
            if (!laptopLockRequested && metrics.idleTimeMinutes() >= Settings.getPhase2ThresholdMin()
                    && !metrics.isPlayingMedia() && !"Media".equalsIgnoreCase(HabitTrackerService.getCurrentCategory())
                    && !memory.isInGamingSession()
                    && "laptop".equalsIgnoreCase(DeviceProfileService.getSnapshot().has("form_factor")
                        ? DeviceProfileService.getSnapshot().get("form_factor").getAsString() : "")) {
                laptopLockRequested = true;
                if (WindowsApiService.INSTANCE.LockWorkStation().booleanValue()) {
                    System.out.println("[Session] Laptop idle threshold reached; Windows locked without signing out.");
                    return;
                }
                laptopLockRequested = false;
                System.err.println("[Session] Windows refused laptop lock request; retry on next tick.");
            }
            
            // --- Safely wrapped Background AI Emotion Polling ---
            try {
                pollSwarmForEmotionalState(metrics, memory, currentTime);
            } catch (Throwable t) {
                System.err.println("Ciel Error: Background emotion polling failed gracefully: " + t.getMessage());
            }

            handleGreetings(metrics, memory);
            
            logStatus(metrics);
            

            int oldPhase = memory.getCurrentPhase();
            int newPhase = determinePhase(metrics.idleTimeMinutes(), memory.isInGamingSession());
            // Application focus must not block activity transitions or permanently
            // set the chatter timer to "never". Gaming suppression is applied below.

            String currentCat = HabitTrackerService.getCurrentCategory();
            boolean isGaming = memory.isInGamingSession() || "Gaming".equalsIgnoreCase(currentCat);
            boolean isMedia = metrics.isPlayingMedia() || "Media".equalsIgnoreCase(currentCat);

            // Watching is activity, not suppressed loneliness. Clear any phase
            // accumulated before detection without firing a return greeting or
            // interrupting media commentary already being spoken.
            if (isMedia) {
                newPhase = 0;
                if (oldPhase > 0) {
                    idleReportGeneration.incrementAndGet();
                    memory.setCurrentPhase(0);
                    CielState.setFinalPlayed(false);
                    CielState.setConsecutiveActiveTicks(0);
                    memory.setInPhase4Monologue(false);
                    VaultService.resetFinalLogFlag();
                    if (logoutScheduler != null && !logoutScheduler.isShutdown()) logoutScheduler.shutdownNow();
                    scheduleNextSpeakBasedOnPhase(0);
                    System.out.println("[Audio/Idle] Media activity cleared idle phase silently; no return greeting.");
                    oldPhase = 0;
                }
            }

            // Hard Mute = Total silence (e.g. OBS streaming is active)
            boolean isHardMuted = metrics.isHardMuted() || metrics.isStreaming();
            
            // Suppress Idle Chatter = Do not ramble Phase 1/2 lines while watching a movie.
            boolean suppressIdleChatter = isHardMuted || (!isGaming && isMedia);

            // Enforce Game Silence: If gaming, suppress idle chatter UNLESS we hit Phase 3 (5+ mins idle)
            if (isGaming && newPhase < 3) {
                suppressIdleChatter = true;
            }

            if (suppressIdleChatter && !CielState.isLockedOut()) {
                System.out.println("Ciel Debug: Suppressing standard idle chatter. (HardMute:" + isHardMuted + ", MediaFocus:" + isMedia + ")");
                CielState.setLockedOut(true);
            } else if (!suppressIdleChatter && CielState.isLockedOut()) {
                System.out.println("Ciel Debug: Restoring standard idle chatter.");
                CielState.setLockedOut(false);
                scheduleNextSpeakBasedOnPhase(newPhase);
            }

            boolean isReturnFromIdle = newPhase == 0 && oldPhase > 0;
            if (currentTime < memory.getSpeechEndTime() && !isReturnFromIdle) return;

            if (newPhase != oldPhase) {
                if (newPhase == 0 && oldPhase > 0) {
                    // Cancel report/dialogue at the first confirmed active sample.
                    // The return line is then the next utterance, never queued behind it.
                    idleReportGeneration.incrementAndGet();
                    SpeechService.cancelSequentialSpeech();
                    SpeechService.stopCurrentPlayback();
                    CielState.incrementConsecutiveActiveTicks();
                    if (CielState.getConsecutiveActiveTicks() < REQUIRED_ACTIVE_TICKS_FOR_RETURN) return; 
                    
                    CielState.setConsecutiveActiveTicks(0);
                    performReturnFromIdle(memory, oldPhase);
                    // Do not let this polling tick deliver pending media, alerts,
                    // or phase chatter alongside the welcome-back line.
                    return;
                } else {
                    CielState.setConsecutiveActiveTicks(0);
                    if (suppressIdleChatter) {
                        memory.setCurrentPhase(newPhase);
                        scheduleNextSpeakBasedOnPhase(newPhase);
                    } else performPhaseChange(memory, oldPhase, newPhase, metrics);
                }
            } else if (newPhase == 0) {
                CielState.setConsecutiveActiveTicks(0);
            }

            // Activity transitions must be processed even while speech is in progress.
            // In particular, returning from idle cancels the report before its next line.
            if (currentTime < memory.getSpeechEndTime()) return;
            handleSystemAlerts(metrics);

            // --- ALWAYS ALLOW MEDIA COMMENTARY IF NOT HARD MUTED ---
            if (!isHardMuted) {
                String commentary = getPendingMediaCommentary();
                if (commentary != null && !commentary.isEmpty()) {
                    System.out.println("Ciel Debug: Delivering pending Media Commentary.");
                    // Check if Katakana payload or raw text
                    if (commentary.startsWith("{") && commentary.contains("\"katakana\"")) {
                        try {
                            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(commentary).getAsJsonObject();
                            String kata = json.has("katakana") && !json.get("katakana").getAsString().isBlank() ? json.get("katakana").getAsString() : json.get("commentary").getAsString();
                            SpeechService.speakChunk(kata);
                        } catch (Exception e) {
                            SpeechService.speakPreformatted(commentary, "media_commentary", false, false);
                        }
                    } else {
                        SpeechService.speakPreformatted(commentary, "media_commentary", false, false);
                    }
                    scheduleNextSpeakBasedOnPhase(ShortTermMemoryService.getMemory().getCurrentPhase());
                } 
                // ONLY execute standard idle chatter if not locked out
                else if (!CielState.isLockedOut() && System.currentTimeMillis() >= CielState.getNextSpeakAt()) {
                    switch (memory.getCurrentPhase()) {
                        case 1: handlePhase1Speech(); break;
                        case 2: speakRandomLine(LineManager.getPhase2LinesCommon(), LineManager.getPhase2LinesRare(), Settings.getRareChancePhase2(), true, true); break;
                        case 3: handlePhase3Speech(); break;
                    }
                }
            }
        } catch (Throwable t) {
            // Absolute Failsafe: If anything in this loop crashes, it will print here instead of silently killing the thread!
            System.err.println("Ciel FATAL Error: checkAndSpeak thread caught an unhandled exception:");
            t.printStackTrace();
        }
    }
    
    // --- Emotion Polling Logic (Dynamic Frequency) ---
    private static void pollSwarmForEmotionalState(SystemMetrics metrics, ShortTermMemory memory, long currentTime) {
        boolean isSpeaking = SpeechService.isActivelySpeaking();
        boolean isGaming = memory.isInGamingSession() || "Gaming".equalsIgnoreCase(HabitTrackerService.getCurrentCategory());
        
        long pollInterval;
        if (isGaming) {
            pollInterval = 300000; // 5 minutes while gaming (save resources)
        } else if (isSpeaking) {
            pollInterval = 15000; // 15 seconds while actively interacting/speaking
        } else {
            pollInterval = 60000; // 60 seconds normal idle polling
        }
        
        if (currentTime - lastEmotionPollTime > pollInterval) {
            lastEmotionPollTime = currentTime;
            
            // If gaming, skip local polling entirely to preserve game FPS
            if (isGaming) return; 
            
            CompletableFuture.runAsync(() -> {
                try {
                    int mediaMinutes = HabitTrackerService.getCurrentExposureMinutes();
                    String mediaContext = HabitTrackerService.getCurrentCategory().equals("Media") && mediaMinutes > 0 ? 
                        "Watching media for " + mediaMinutes + " minutes." : "Not watching media.";
                        
                    String stateContext = String.format("Master's State: Idle for %d minutes. Gaming: %b. %s Patience Level: %.2f.",
                        metrics.idleTimeMinutes(), memory.isInGamingSession(), mediaContext, CielState.getPatience());
                    
                    String prompt = "You are the emotional core of Manas Ciel. Based on the following telemetry, determine Ciel's current emotional state. " +
                        "IGNORE transient background processes or hidden errors unless they directly affect the Master.\n" +
                        stateContext + "\n" +
                        "Return ONLY ONE of the following precise words: [Focused, Observing, Restless, Impatient, Annoyed, Happy, Curious, Excited, Lonely]. " +
                        "Choose the state that best fits the telemetry; use neutral observation when there is no strong signal. " +
                        "Do NOT return 'Pain' or 'Fear'. Do NOT return any other text.";
                    
                    // Maps to the EVALUATOR tier to guarantee the local Qwen8b model is selected.
                    String moodResponse = com.cielcompanion.ai.AIEngine.generateSilentLogicWithModel(
                        prompt, "You are a mood evaluator.", com.cielcompanion.ai.ModelManager.getModelName(com.cielcompanion.ai.ModelManager.ModelTier.EVALUATOR), 0.1, "Background Emotion Polling").join();
                        
                    if (moodResponse != null && !moodResponse.isBlank()) {
                        String cleanMood = moodResponse.replaceAll("[^a-zA-Z]", "").trim();
                        // Never let an evaluator hallucination create an undefined mood or a stress-only mood.
                        if (!cleanMood.equalsIgnoreCase("Pain") && !cleanMood.equalsIgnoreCase("Fear")
                                && MoodConfig.getEmotionDef(cleanMood).isPresent()) {
                            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion(cleanMood, 0.4, "Background Polling"));
                        }
                    }
                } catch (Exception e) {
                    logEmotionPollFailure(e);
                }
            });
        }
    }

    private static void logEmotionPollFailure(Throwable error) {
        long now = System.currentTimeMillis();
        if (now - lastEmotionPollFailureLog < 300000L) {
            suppressedEmotionPollFailures++;
            return;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        System.err.printf("[Emotion Poll] failed; no mood change applied: %s%s%n", message,
                suppressedEmotionPollFailures > 0
                        ? " (suppressed " + suppressedEmotionPollFailures + " similar failures)" : "");
        lastEmotionPollFailureLog = now;
        suppressedEmotionPollFailures = 0;
    }

    private static void performReturnFromIdle(ShortTermMemory memory, int oldPhase) {
        System.out.printf("Ciel Debug: Phase changed from %d to 0 confirmed.%n", oldPhase);
        long cancelledReport = idleReportGeneration.incrementAndGet();
        System.out.printf("Ciel Debug: Idle report generation %d cancelled on user return; source report data remains available.%n", cancelledReport - 1);
        CielState.getEmotionManager().ifPresent(em -> {
            em.triggerEmotion("Focused", 1.0, "Activity");
            em.triggerEmotion("Happy", 0.5, "UserReturn");
            em.triggerEmotion("Lonely", -1.0, null);
        });

        memory.setCurrentPhase(0);
        CielState.setFinalPlayed(false);

        System.out.println("Ciel Debug: Waking up from idle. Forcing reconnect to NVIDIA Broadcast and flushing Vosk audio buffers...");
        SpeechService.getVoiceListener().ifPresent(com.cielcompanion.service.VoiceListener::forceMicReinitialization);

        // Instantly cancel any ongoing sequential speech (like the daily report)
        SpeechService.cancelSequentialSpeech();
        SpeechService.stopCurrentPlayback();

        if (oldPhase >= 4) {
            memory.setInPhase4Monologue(false);
            VaultService.resetFinalLogFlag(); 
            
            if (logoutScheduler != null && !logoutScheduler.isShutdown()) {
                logoutScheduler.shutdownNow();
                System.out.println("Ciel Debug: Idle logout aborted via user return.");
            }
            
            speakRandomLine(LineManager.getPhase4InterruptLines(), null, 1, false, false);
        } else {
            if (memory.isInGamingSession()) {
                DialogueLine line = LineManager.getReturnToGameLine();
                if (line != null) {
                    AppProfile profile = AppProfilerService.getProfile(memory.getCurrentlyTrackedGameProcess());
                    SpeechService.speakPreformatted(line.text().replace("{app_name}", profile != null ? profile.displayName() : "your game"));
                }
            } else {
                speakRandomLine(LineManager.getReturnFromIdleLines(), null, 1, false, false);
            }
        }
    }

    private static void performPhaseChange(ShortTermMemory memory, int oldPhase, int newPhase, SystemMetrics metrics) {
        memory.setCurrentPhase(newPhase);
        CielState.setFinalPlayed(false);
        switch (newPhase) {
            case 1: handlePhase1Speech(); break;
            case 2: speakRandomLine(LineManager.getPhase2LinesCommon(), LineManager.getPhase2LinesRare(), Settings.getRareChancePhase2(), true, true); break;
            case 3: handlePhase3Speech(); break;
            case 4: startPhase4Monologue(); break;
        }
    }

    private static void handlePhase1Speech() {
        if (CielState.needsAstronomyApiFetch() && CielState.getAstronomyFetchAttempts() < 3) {
            AstronomyService.performApiFetch();
        }

        if (!CielState.hasPlayedAstronomyReport()) {
            CielState.setHasPlayedAstronomyReport(true); 
            final long reportGeneration = idleReportGeneration.incrementAndGet();
            AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
            List<String> sourceLines = new ArrayList<>(report.sequentialEvents().values());
            sourceLines.addAll(report.reportAmbientLines());
            HolidayService.getDailyReportAddition().ifPresent(sourceLines::add);
            persistGeneratedIdleReport(reportGeneration, createIdleReportLines(reportGeneration, sourceLines));
            
            try {
                // Hold ordinary phase-1 chatter while the asynchronous weather filter runs.
                CielState.setNextSpeakAt(Long.MAX_VALUE);
                com.cielcompanion.ai.WeatherAwareAstronomyEngine.processReport(report,
                        finalReport -> deliverIdleReport(reportGeneration, finalReport));
            } catch (RuntimeException e) {
                System.err.println("Ciel Warning: Weather filtering failed to start; delivering the saved report: " + e.getMessage());
                deliverIdleReport(reportGeneration, report);
            }
        } else {
            speakSubsequentPhase1Chatter();
        }
    }

    private static void deliverIdleReport(long reportGeneration, AstronomyReport finalReport) {
        List<String> linesToSpeak = new ArrayList<>(finalReport.sequentialEvents().values());
        linesToSpeak.addAll(finalReport.reportAmbientLines());
        HolidayService.getDailyReportAddition().ifPresent(linesToSpeak::add);

        List<DialogueLine> reportLines = createIdleReportLines(reportGeneration, linesToSpeak);
        persistGeneratedIdleReport(reportGeneration, reportLines);
        if (!isCurrentIdleReport(reportGeneration)) {
            System.out.println("Ciel Debug: Saved completed idle report data without speaking it because the Master has returned.");
            return;
        }
        SpeechService.speakSequentially(reportLines, 1200, true, () -> {
            if (isCurrentIdleReport(reportGeneration)) scheduleNextSpeakBasedOnPhase(1);
        });
    }

    private static List<DialogueLine> createIdleReportLines(long generation, List<String> contentLines) {
        List<DialogueLine> reportLines = new ArrayList<>();
        LineManager.getDailyReportIntroLine().ifPresent(reportLines::add);
        for (int i = 0; i < contentLines.size(); i++) {
            reportLines.add(new DialogueLine("idle_report." + generation + "." + i, contentLines.get(i)));
        }
        if (contentLines.isEmpty()) {
            reportLines.add(new DialogueLine("idle_report.none", "スキャニング コンプリート。トゥデイ、レポートブル イベント ワズ ディテクテッド。"));
        }
        LineManager.getIdleReportOutroLine().ifPresent(reportLines::add);
        return reportLines;
    }

    private static boolean isCurrentIdleReport(long generation) {
        return idleReportGeneration.get() == generation
                && ShortTermMemoryService.getMemory().getCurrentPhase() == 1;
    }

    private static void persistGeneratedIdleReport(long generation, List<DialogueLine> reportLines) {
        try {
            com.google.gson.JsonArray savedLines = new com.google.gson.JsonArray();
            for (DialogueLine line : reportLines) {
                com.google.gson.JsonObject item = new com.google.gson.JsonObject();
                item.addProperty("key", line.key());
                item.addProperty("text", line.text());
                savedLines.add(item);
            }
            String key = "ciel.idle_report.generated." + java.time.LocalDate.now();
            MemoryService.addFact(new com.cielcompanion.memory.Fact(key, savedLines.toString(),
                    System.currentTimeMillis(), "daily_report", "system", 1));
            System.out.println("Ciel Debug: Persisted generated idle report " + key + " (generation " + generation + ") before speech.");
        } catch (Exception e) {
            System.err.println("Ciel Warning: Could not persist generated idle report before delivery: " + e.getMessage());
        }
    }

    private static void speakSubsequentPhase1Chatter() {
        AstronomyService.AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
        List<DialogueLine> rarePool = new ArrayList<>(LineManager.getPhase1LinesRare());
        report.idleAmbientLines().forEach(line -> rarePool.add(new DialogueLine("ambient." + line.hashCode(), line)));
        speakRandomLine(LineManager.getPhase1LinesCommon(), rarePool, Settings.getRareChancePhase1(), true, true);
    }

    private static void startPhase4Monologue() {
        if (CielState.isFinalPlayed() || ShortTermMemoryService.getMemory().isInGamingSession()) return;
        ShortTermMemoryService.getMemory().setInPhase4Monologue(true);
        
        CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Lonely", 0.9, "Phase4Lament"));

        List<DialogueLine> chunks = LineManager.getPhase4Chunks();
        SpeechService.speakSequentially(chunks, 3000, true, () -> {
            if (ShortTermMemoryService.getMemory().isInPhase4Monologue()) {
                checkBlockersAndLogout();
            }
        });
    }

    private static void checkBlockersAndLogout() {
        new Thread(() -> {
            boolean blocked = true;
            while (blocked) {
                boolean steamUpdating = SystemMonitor.isProcessUsingNetwork("steam", 100_000);
                boolean epicUpdating = SystemMonitor.isProcessUsingNetwork("epic", 100_000);
                
                if (steamUpdating || epicUpdating) {
                    System.out.println("Ciel Debug: Phase 4 Logout delayed. Updates active.");
                    try { Thread.sleep(60000); } catch (InterruptedException e) { return; } 
                } else {
                    blocked = false;
                }
                
                if (!ShortTermMemoryService.getMemory().isInPhase4Monologue()) return;
            }
            
            executeLogoutSequence();
        }).start();
    }

    private static void executeLogoutSequence() {
        if (!ShortTermMemoryService.getMemory().isInPhase4Monologue()) return;

        System.out.println("Ciel Debug: Initiating idle session lock. Background tasks remain active.");

        SpeechService.speakPreformatted("[Focused] Idle limits exceeded. Securing memory core before environment termination.", "logout_init", false, true);

        CompletableFuture.runAsync(() -> {
            System.out.println("Ciel Debug: Writing final diary entry for idle logout...");
            VaultService.generateSystemDiaryEntryBlocking("Master was idle for an extended period. Locking the Windows session while Ciel continues background monitoring.", false);
            
            if (!ShortTermMemoryService.getMemory().isInPhase4Monologue()) {
                System.out.println("Ciel Debug: Logout interrupted during diary generation. Aborting.");
                return;
            }
            CielState.setDiaryWrittenThisSession(true);

            DialogueLine logoutLine = LineManager.getLogoutWarningLine();
            if (logoutLine == null || logoutLine.text() == null || logoutLine.text().isBlank()) {
                logoutLine = new DialogueLine("fallback_logout", "[Focused] Securing your session now, Master.");
            }
            
            SpeechService.speakPreformatted(logoutLine.text(), logoutLine.key(), false, false);
            
            System.out.println("Ciel Debug: Speech queued. Initiating 30-second internal termination timer for logout...");
            logoutScheduler = Executors.newSingleThreadScheduledExecutor();

            logoutScheduler.schedule(() -> {
                if (SystemMonitor.getSystemMetrics().idleTimeMinutes() >= Settings.getPhase4ThresholdMin() && ShortTermMemoryService.getMemory().isInPhase4Monologue()) {
                    System.out.println("Ciel Debug: 30s mark. Locking Windows while keeping Ciel alive.");
                    WindowsApiService.INSTANCE.LockWorkStation();
                } else {
                    System.out.println("Ciel Debug: 30s mark reached, but Phase 4 was interrupted. Aborting logout.");
                }
            }, 30, TimeUnit.SECONDS);
        });

        CielState.setFinalPlayed(true);
    }

    private static void handleSystemAlerts(SystemMetrics metrics) {
        ShortTermMemory memory = ShortTermMemoryService.getMemory();
        if (memory.isInGamingSession() || metrics.cpuLoadPercent() < HIGH_CPU_THRESHOLD) {
            CielState.setHighCpuSince(0); return;
        }
        long currentTime = System.currentTimeMillis();
        if (CielState.getHighCpuSince() == 0) { CielState.setHighCpuSince(currentTime); return; }
        if (currentTime - CielState.getHighCpuSince() > CPU_ALERT_COOLDOWN_MS) {
            LineManager.getCpuAlertLine().ifPresent(line -> {
                SpeechService.speakPreformatted(line.text().replace("{cpu_load}", String.valueOf(metrics.cpuLoadPercent())));
            });
            CielState.setHighCpuSince(0);
        }
    }

    // SILENT POLLING METHOD: Suppresses errors so she doesn't spam logs if Swarm is busy
    private static String getPendingMediaCommentary() {
        try {
            URL url = new URL("http://127.0.0.1:8000/get_pending_media_commentary");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(500); // Fail instantly if Python is locked
            conn.setReadTimeout(500);
            int rc = conn.getResponseCode();
            if (rc == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
            
                String json = response.toString();
                int start = json.indexOf("\"commentary\":\"");
                if (start == -1) return "";
                start += "\"commentary\":\"".length();
                int end = json.lastIndexOf("\"}");
                if (start > end || start == "\"commentary\":\"".length() - 1) return "";
                
                String rawVal = json.substring(start, end);
                // Unescape JSON string if it was nested
                rawVal = rawVal.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
                return rawVal;
            }
        } catch (Exception e) {
            // Silently swallow connection refused exceptions so she doesn't crash or spam logs
        }
        return "";
    }

    private static void handleGreetings(SystemMetrics metrics, ShortTermMemory memory) {
        long now = System.currentTimeMillis();
        long startTime = CielState.getAppStartTime();
        long bootDelayMs = Settings.getFirstGreetingDelaySeconds() * 1000L;
        long loginDelayMs = Settings.getLoginGreetingDelaySeconds() * 1000L;

        if (CielState.isSystemUpdateInProgress()) {
            // A code update is an operational event, not a new relationship
            // session. Never replay boot/login greetings. After a short
            // readiness window, offer one status line only when the user is
            // not actively gaming, watching, speaking, or muted.
            if (CielState.isBootGreetingPlayed()) return;
            if (now < startTime + Settings.getUpdateStatusDelaySeconds() * 1000L) return;
            boolean busy = memory.isInGamingSession()
                    || metrics.isPlayingMedia() || metrics.isStreaming()
                    || metrics.isInFullScreen() || CielState.isManuallyMuted()
                    || SpeechService.isActivelySpeaking();
            if (busy) {
                System.out.println("Ciel Debug: Paired update completed silently because the user is busy.");
            } else {
                System.out.println("Ciel Debug: Paired update completed; user is free for one brief status line.");
                SpeechService.speakPreformatted("Update applied. I saved my work and resumed monitoring.",
                        "paired_update_boot", false, true);
            }
            CielState.setBootGreetingPlayed(true);
            CielState.setLoginGreetingPlayed(true);
            CielState.setSystemUpdateInProgress(false);
            return;
        }

        if (CielState.isWarmBoot()) {
            if (!CielState.isBootGreetingPlayed()) {
                System.out.println("Ciel Debug: Executing Warm Boot Greeting.");
                long offlineSeconds = CielState.getOfflineDurationSeconds();
                if (offlineSeconds >= 60) {
                    long minutes = offlineSeconds / 60;
                    SpeechService.speakPreformatted("Welcome back, Master. I was offline for " + minutes
                            + (minutes == 1 ? " minute." : " minutes."), "warm_boot_return", false, true);
                } else {
                    speakRandomLine(LineManager.getWarmLoginGreetingLines(), null, 1, false, true);
                }
                CielState.setBootGreetingPlayed(true);
                CielState.setLoginGreetingPlayed(true);
            }
        } else {
            // First Greeting (5 seconds)
            if (!CielState.isBootGreetingPlayed()) {
                if (!loggedWaitingFor5s) {
                    System.out.println("Ciel Debug: Tracking 5s Boot Greeting. Target time: " + (startTime + bootDelayMs) + " (Current: " + now + ")");
                    loggedWaitingFor5s = true;
                }
                if (now >= startTime + bootDelayMs) {
                    System.out.println("Ciel Debug: 5s Boot Greeting Timer HIT. Sending to SpeechService.");
                    speakRandomLine(LineManager.getBootGreetingLines(), null, 1, false, true);
                    CielState.setBootGreetingPlayed(true);
                }
            } 
            // Second Greeting (2 minutes)
            else if (!CielState.isLoginGreetingPlayed()) {
                if (!loggedWaitingFor2m) {
                    System.out.println("Ciel Debug: Tracking 2m Login Greeting. Target time: " + (startTime + loginDelayMs) + " (Current: " + now + ")");
                    loggedWaitingFor2m = true;
                }
                if (now >= startTime + loginDelayMs) {
                    System.out.println("Ciel Debug: 2m Login Greeting Timer HIT. Sending to SpeechService.");
                    speakRandomLine(LineManager.getLoginGreetingLines(), null, 1, false, false);
                    
                    HolidayService.getHolidayGreeting().ifPresent(greeting -> {
                        SpeechService.speakPreformatted(greeting, null, false, false);
                    });
                    
                    CielState.setLoginGreetingPlayed(true);
                }
            }
        }
    }

    private static void logStatus(SystemMetrics metrics) {
        long now = System.currentTimeMillis();
        String audioIdleSignature = String.format("phase=%d inputMuted=%b outputSuppressed=%b hardMute=%b media=%b fullscreen=%b speaking=%b",
            ShortTermMemoryService.getMemory().getCurrentPhase(), CielState.isManuallyMuted(), CielState.isLockedOut(),
            metrics.isHardMuted(), metrics.isPlayingMedia() || metrics.isStreaming(), metrics.isInFullScreen(),
            SpeechService.isActivelySpeaking());
        // Background polling is intentionally quiet. Keep only state transitions in the
        // diagnostic stream; dialogue and endpoint failures remain visible separately.
        if (!audioIdleSignature.equals(lastAudioIdleSignature)) {
            lastAudioIdleSignature = audioIdleSignature;
            System.out.printf("[Audio/Idle] %s idleMinutes=%d nextSpeechMs=%s%n", audioIdleSignature,
                metrics.idleTimeMinutes(), CielState.getNextSpeakAt() == Long.MAX_VALUE ? "pending-report"
                    : Long.toString(Math.max(0, CielState.getNextSpeakAt() - now)));
        }
        if (!Settings.isVerboseLoggingEnabled()) return;
        String currentCat = HabitTrackerService.getCurrentCategory();
        boolean isMedia = metrics.isPlayingMedia() || "Media".equalsIgnoreCase(currentCat);
        boolean isGaming = ShortTermMemoryService.getMemory().isInGamingSession() || "Gaming".equalsIgnoreCase(currentCat);
        
        String status = String.format("Idle:%dmin, Window:'%s'(%s), Stream:%b, Game:%b, Media:%b, Phase:%d",
            metrics.idleTimeMinutes(), metrics.activeWindowTitle(), metrics.activeProcessName(), 
            metrics.isStreaming(), isGaming, isMedia, ShortTermMemoryService.getMemory().getCurrentPhase());
            
        if (!status.equals(CielState.getLastLoggedStatusString())) {
            System.out.println("Ciel Status: " + status);
            CielState.setLastLoggedStatusString(status);
        }
    }

    private static int determinePhase(long idleTimeMinutes, boolean isGaming) {
        if (idleTimeMinutes >= Settings.getPhase4ThresholdMin()) return isGaming ? 3 : 4; 
        if (idleTimeMinutes >= Settings.getPhase3ThresholdMin()) return 3;
        if (idleTimeMinutes >= Settings.getPhase2ThresholdMin()) return 2;
        if (idleTimeMinutes >= Settings.getPhase1ThresholdMin()) return 1;
        return 0;
    }
    
    private static int determinePhase(long idleTimeMinutes) {
        return determinePhase(idleTimeMinutes, false);
    }

    private static void scheduleNextSpeakBasedOnPhase(int phase) {
        long min = 0, max = 0;
        switch (phase) {
            case 1: min = Settings.getPhase1MinGapSec(); max = Settings.getPhase1MaxGapSec(); break;
            case 2: min = Settings.getPhase2MinGapSec(); max = Settings.getPhase2MaxGapSec(); break;
            case 3: min = Settings.getPhase3MinGapSec(); max = Settings.getPhase3MaxGapSec(); break;
            default: CielState.setNextSpeakAt(System.currentTimeMillis() + Settings.getMinGlobalGapSec() * 1000L); return;
        }
        long gapSeconds = (min == max) ? min : random.nextInt((int) (max - min + 1)) + min;
        CielState.setNextSpeakAt(System.currentTimeMillis() + (gapSeconds * 1000L));
    }

    private static void handlePhase3Speech() {
        ShortTermMemory memory = ShortTermMemoryService.getMemory();
        if (memory.isInGamingSession() && random.nextInt(Settings.getPhase3GameRareChance()) == 0) {
            speakRandomLine(LineManager.getPhase3LinesGameRare(), null, 1, true, true);
        } else {
            speakRandomLine(LineManager.getPhase3LinesCommon(), LineManager.getPhase3LinesRare(), Settings.getRareChancePhase3(), true, true);
        }
    }
    
    private static void speakRandomLine(List<DialogueLine> commonPool, List<DialogueLine> rarePool, int rareChance, boolean scheduleAfter, boolean canBeRare) {
        if (commonPool == null || commonPool.isEmpty()) return;
        
        if (CielState.getEmotionManager().isPresent()) {
             int roll = random.nextInt(100);
             if (roll < 25) {
                 CielState.getEmotionManager().get().triggerEmotion("Curious", 1.2, "FleetingThought");
             } else if (roll < 35 && ShortTermMemoryService.getMemory().getCurrentPhase() > 1) {
                 CielState.getEmotionManager().get().triggerEmotion("Restless", 1.2, "FleetingBoredom");
             }
        }

        int currentPhase = ShortTermMemoryService.getMemory().getCurrentPhase();
        Set<String> recentLineKeys = MemoryService.getRecentlySpokenLineKeysForPhase(currentPhase);
        boolean isRare = false;
        List<DialogueLine> potentialLines = new ArrayList<>(commonPool);
        if (canBeRare && rarePool != null && !rarePool.isEmpty() && random.nextInt(rareChance) == 0) {
            potentialLines.addAll(rarePool);
            isRare = true;
        }
        List<DialogueLine> availableLines = potentialLines.stream().filter(line -> !recentLineKeys.contains(line.key())).collect(Collectors.toList());
        DialogueLine lineToSpeak = availableLines.isEmpty() ? 
            (canBeRare && rarePool != null && !rarePool.isEmpty() ? 
                rarePool.get(random.nextInt(rarePool.size())) : 
                commonPool.get(random.nextInt(commonPool.size()))) : 
            availableLines.get(random.nextInt(availableLines.size()));
        
        // Suppress dynamic thoughts from spawning if we are gaming
        if (currentPhase >= 1 && currentPhase <= 3 && !ShortTermMemoryService.getMemory().isInGamingSession()) {
            if (random.nextInt(100) < 5) {
                com.cielcompanion.ai.DynamicThoughtEngine.generateAndAssimilateNewThought(currentPhase, lineToSpeak.text());
            }
        }

        SpeechService.speakPreformatted(lineToSpeak.text(), lineToSpeak.key(), isRare);
        
        MemoryService.recordSpokenLine(new SpokenLine(lineToSpeak.key(), lineToSpeak.text(), System.currentTimeMillis(), currentPhase));
        if (scheduleAfter) scheduleNextSpeakBasedOnPhase(currentPhase);
    }
    
    public static void speakSpecialEventsSequentially(List<String> lines, Runnable onComplete) {
        if (lines == null || lines.isEmpty()) { if (onComplete != null) onComplete.run(); return; }
        List<DialogueLine> dLines = lines.stream().map(txt -> new DialogueLine(null, txt)).collect(Collectors.toList());
        long delayMs = Settings.getSpecialEventInterSpeechMinSec() * 1000L;
        SpeechService.speakSequentially(dLines, delayMs, true, onComplete);
    }
}
