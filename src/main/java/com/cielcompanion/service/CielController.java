package com.cielcompanion.service;

import com.cielcompanion.CielCompanion;
import com.cielcompanion.CielState;
import com.cielcompanion.memory.SpokenLine;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.AppProfilerService.AppProfile;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.service.SystemMonitor.SystemMetrics; 
import com.cielcompanion.util.EnglishNumber;
import com.cielcompanion.service.AstronomyService.AstronomyReport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class CielController {

    private static final Random random = new Random();
    private static final int GAME_SESSION_GRACE_PERIOD_MS = 10000;
    private static final Set<String> GAME_AWARENESS_EXCLUSIONS = Set.of("steamwebhelper", "copilot", "steam");
    private static final int HIGH_CPU_THRESHOLD = 90;
    private static final long CPU_ALERT_COOLDOWN_MS = 60000;
    private static final int REQUIRED_ACTIVE_TICKS_FOR_RETURN = 3;

    private static final AppLauncherService appLauncher = new AppLauncherService();
    private static ScheduledExecutorService logoutScheduler;

    public static void checkAndSpeak() {
        long currentTime = System.currentTimeMillis();
        ShortTermMemory memory = ShortTermMemoryService.getMemory();
        
        if (currentTime < memory.getSpeechEndTime()) return;

        handleGreetings();
        
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        logStatus(metrics);
        
        if (handleApplicationAwareness(metrics)) {
            CielState.setNextSpeakAt(Long.MAX_VALUE);
            return;
        }
        
        handleSystemAlerts(metrics);

        int oldPhase = memory.getCurrentPhase();
        int newPhase = determinePhase(metrics.idleTimeMinutes(), memory.isInGamingSession());

        String currentCat = HabitTrackerService.getCurrentCategory();
        boolean isGaming = memory.isInGamingSession() || "Gaming".equalsIgnoreCase(currentCat);
        boolean isMedia = metrics.isPlayingMedia() || "Media".equalsIgnoreCase(currentCat);

        boolean shouldBeMuted = metrics.isHardMuted() || metrics.isStreaming() || 
                                (!isGaming && isMedia) || 
                                (!isGaming && metrics.isInFullScreen() && !metrics.isBrowserActive());

        if (shouldBeMuted && !CielState.isLockedOut()) {
            System.out.println("Ciel Debug: Muting. (Stream:" + metrics.isStreaming() + ", Media:" + isMedia + ", FS:" + metrics.isInFullScreen() + ")");
            CielState.setLockedOut(true);
        } else if (!shouldBeMuted && CielState.isLockedOut()) {
            System.out.println("Ciel Debug: Unmuting. Media conditions cleared.");
            CielState.setLockedOut(false);
        }

        if (newPhase != oldPhase) {
            if (newPhase == 0 && oldPhase > 0) {
                if (CielState.isLockedOut()) {
                    CielState.setConsecutiveActiveTicks(0);
                    return; 
                }
                CielState.incrementConsecutiveActiveTicks();
                if (CielState.getConsecutiveActiveTicks() < REQUIRED_ACTIVE_TICKS_FOR_RETURN) return; 
                
                CielState.setConsecutiveActiveTicks(0);
                performReturnFromIdle(memory, oldPhase);
            } else {
                CielState.setConsecutiveActiveTicks(0);
                performPhaseChange(memory, oldPhase, newPhase, metrics);
            }
        } else if (newPhase == 0) {
            CielState.setConsecutiveActiveTicks(0);
        }

        if (!CielState.isLockedOut() && System.currentTimeMillis() >= CielState.getNextSpeakAt()) {
            switch (memory.getCurrentPhase()) {
                case 1: handlePhase1Speech(); break;
                case 2: speakRandomLine(LineManager.getPhase2LinesCommon(), LineManager.getPhase2LinesRare(), Settings.getRareChancePhase2(), true, true); break;
                case 3: handlePhase3Speech(); break;
            }
        }
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

        System.out.println("Ciel Debug: Waking up from idle. Forcing reconnect to NVIDIA Broadcast and flushing Vosk audio buffers...");
        SpeechService.getVoiceListener().ifPresent(VoiceListener::forceMicReinitialization);

        SpeechService.stopCurrentPlayback();
        SpeechService.cancelSequentialSpeech();

        if (oldPhase >= 4) {
            memory.setInPhase4Monologue(false);
            VaultService.resetFinalLogFlag(); 
            
            if (logoutScheduler != null && !logoutScheduler.isShutdown()) {
                logoutScheduler.shutdownNow();
                System.out.println("Ciel Debug: Idle logout aborted via user return.");
            }
            
            // Note: CRITICAL FIX. All manual pauseMediaIfPlaying() calls have been eradicated. 
            // SpeechService handles it flawlessly in the background now.
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

    private static boolean handleApplicationAwareness(SystemMetrics metrics) {
        ShortTermMemory memory = ShortTermMemoryService.getMemory();
        String currentlyTrackedGame = memory.getCurrentlyTrackedGameProcess();

        if (currentlyTrackedGame != null && !metrics.runningProcesses().contains(currentlyTrackedGame)) {
            if (memory.getGameSessionGracePeriodEnd() == 0) {
                System.out.println("Ciel Debug: Tracked game '" + currentlyTrackedGame + "' process disappeared. Starting grace period.");
                memory.setGameSessionGracePeriodEnd(System.currentTimeMillis() + GAME_SESSION_GRACE_PERIOD_MS);
            } else if (System.currentTimeMillis() > memory.getGameSessionGracePeriodEnd()) {
                System.out.println("Ciel Debug: Grace period ended for '" + currentlyTrackedGame + "'. Ending session.");
                memory.setCurrentlyTrackedGameProcess(null);
                memory.setGameSessionGracePeriodEnd(0);
                memory.setInGamingSession(false);
                memory.setHighCpuAlertCountInSession(0);
                
                SpeechService.getVoiceListener().ifPresent(VoiceListener::refresh);
            }
            return true;
        }
        
        AppProfile profile = AppProfilerService.identifyActiveApp(metrics.activeProcessName(), metrics.activeWindowTitle());

        if (profile != null && "Game".equalsIgnoreCase(profile.category())) {
            String procNameLower = profile.processName().toLowerCase();
            
            if (GAME_AWARENESS_EXCLUSIONS.contains(procNameLower.replace(".exe", ""))) {
                return memory.isInGamingSession();
            }
            
            if (currentlyTrackedGame == null || !currentlyTrackedGame.equals(procNameLower)) {
                System.out.println("Ciel Debug: New game session detected: " + profile.displayName());
                memory.setInGamingSession(true);
                CielState.getEmotionManager().ifPresent(em -> em.triggerSpecialEvent("GAME_START"));
                
                String nameToUse = (profile.shortName() != null && !profile.shortName().isBlank()) ? profile.shortName() : profile.displayName();
                DialogueLine line = LineManager.getAppAwarenessLine(profile.category());
                if (line != null) {
                    SpeechService.speakPreformatted(line.text().replace("{app_name}", nameToUse));
                    CielState.setLastSpeakAt(System.currentTimeMillis());
                }
                memory.setCurrentlyTrackedGameProcess(procNameLower);
            }
            return true;
        }
        return memory.isInGamingSession();
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
            AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
            
            com.cielcompanion.ai.WeatherAwareAstronomyEngine.processReport(report, finalReport -> {
                List<String> linesToSpeak = new ArrayList<>(finalReport.sequentialEvents().values());
                linesToSpeak.addAll(finalReport.reportAmbientLines()); 

                HolidayService.getDailyReportAddition().ifPresent(linesToSpeak::add);

                if (!linesToSpeak.isEmpty()) {
                    speakSpecialEventsSequentially(linesToSpeak, () -> speakSubsequentPhase1Chatter());
                } else {
                    SpeechService.speakPreformatted("Scanning celestial data. No significant events detected in your sector today.");
                }
            });
        } else {
            speakSubsequentPhase1Chatter();
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

        System.out.println("Ciel Debug: Initiating fast idle-logout sequence. Bypassing active tasks.");

        SpeechService.speakPreformatted("[Focused] Idle limits exceeded. Securing memory core before environment termination.", "logout_init", false, true);

        CompletableFuture.runAsync(() -> {
            System.out.println("Ciel Debug: Writing final diary entry for idle logout...");
            VaultService.generateSystemDiaryEntryBlocking("Master was idle for an extended period. Executing graceful OS logout to secure the environment.", false);
            
            if (!ShortTermMemoryService.getMemory().isInPhase4Monologue()) {
                System.out.println("Ciel Debug: Logout interrupted during diary generation. Aborting.");
                return;
            }

            DialogueLine logoutLine = LineManager.getLogoutWarningLine();
            if (logoutLine == null || logoutLine.text() == null || logoutLine.text().isBlank()) {
                logoutLine = new DialogueLine("fallback_logout", "[Proud] メモリー コア セキュアード。 トリガリング オーエス ログアウト イン サーティー セカンズ。");
            }
            
            SpeechService.speakPreformatted(logoutLine.text(), logoutLine.key(), false, false);
            
            System.out.println("Ciel Debug: Speech queued. Initiating 30-second internal termination timer for logout...");
            logoutScheduler = Executors.newSingleThreadScheduledExecutor();

            logoutScheduler.schedule(() -> {
                System.out.println("Ciel Debug: 1s mark. Initiating graceful Swarm VRAM purge...");
                CielCompanion.killJarvis();
            }, 1, TimeUnit.SECONDS);

            logoutScheduler.schedule(() -> {
                System.out.println("Ciel Debug: 25s mark. Force-killing AI processes to prepare for clean OS logout...");
                try {
                    Runtime.getRuntime().exec("taskkill /F /IM ollama_llama_server.exe /T");
                    Runtime.getRuntime().exec("taskkill /F /IM ollama.exe /T");
                    Runtime.getRuntime().exec("taskkill /F /IM lmstudio-server.exe /T");
                    Runtime.getRuntime().exec("taskkill /F /IM python.exe /T");
                } catch (Exception ignored) {}
            }, 25, TimeUnit.SECONDS);

            logoutScheduler.schedule(() -> {
                if (SystemMonitor.getSystemMetrics().idleTimeMinutes() >= Settings.getPhase4ThresholdMin() && ShortTermMemoryService.getMemory().isInPhase4Monologue()) {
                    System.out.println("Ciel Debug: 30s mark. Executing OS logout command.");
                    try {
                        Runtime.getRuntime().exec("shutdown -l");
                    } catch (IOException e) { e.printStackTrace(); }
                    System.exit(0);
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

    private static void handleGreetings() {
        if (CielState.isWarmBoot()) {
            if (!CielState.isBootGreetingPlayed()) {
                speakRandomLine(LineManager.getWarmLoginGreetingLines(), null, 1, false, true);
                CielState.setBootGreetingPlayed(true);
                CielState.setLoginGreetingPlayed(true);
            }
        } else {
            long now = System.currentTimeMillis();
            long startTime = CielState.getAppStartTime();
            if (!CielState.isBootGreetingPlayed() && now >= startTime + (Settings.getFirstGreetingDelaySeconds() * 1000L)) {
                speakRandomLine(LineManager.getBootGreetingLines(), null, 1, false, true);
                CielState.setBootGreetingPlayed(true);
            } else if (!CielState.isLoginGreetingPlayed() && now >= startTime + (Settings.getLoginGreetingDelaySeconds() * 1000L)) {
                speakRandomLine(LineManager.getLoginGreetingLines(), null, 1, false, false);
                
                HolidayService.getHolidayGreeting().ifPresent(greeting -> {
                    SpeechService.speakPreformatted(greeting, null, false, false);
                });
                
                CielState.setLoginGreetingPlayed(true);
            }
        }
    }

    public static boolean handleDailyReportCommand(String userText) {
        if (CielState.needsAstronomyApiFetch()) {
            AstronomyService.performApiFetch();
        }
        AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
        List<String> linesToSpeak = new ArrayList<>();
        
        HolidayService.getDailyReportAddition().ifPresent(linesToSpeak::add);
        
        linesToSpeak.addAll(report.sequentialEvents().values());
        linesToSpeak.addAll(report.reportAmbientLines());
        
        String data = String.join(" ", linesToSpeak);
        if (data.isBlank()) data = "No significant daily events detected.";
        
        String financeData = FinanceService.getDailyFinanceReport();
        if (financeData != null && !financeData.isBlank()) {
            data = data + "\n\n[FINANCIAL REPORT]\n" + financeData;
        }
        
        String context = com.cielcompanion.ai.ContextBuilder.buildActiveContext(null, userText) + 
            "\n\n[SYSTEM DATA REPOSITORY]\n" + "Daily Report Data: " + data + 
            "\n\nINSTRUCTION: Formulate a natural, conversational answer to the user's query using the SYSTEM DATA provided above.";
        
        ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
        com.cielcompanion.ai.AIEngine.chatFast(userText, context, () -> {});
        return false; 
    }

    private static void logStatus(SystemMetrics metrics) {
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
        DialogueLine lineToSpeak = availableLines.isEmpty() ? potentialLines.get(random.nextInt(potentialLines.size())) : availableLines.get(random.nextInt(availableLines.size()));
        
        if (currentPhase >= 1 && currentPhase <= 3) {
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