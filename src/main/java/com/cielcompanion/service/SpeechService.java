package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.ui.CielGui;
import com.cielcompanion.service.AzureSpeechService;
import com.cielcompanion.service.CielVoiceManager;
import com.cielcompanion.service.VoiceListener;
import com.cielcompanion.service.TranslationService;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.CielState;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.mood.Emotion;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SpeechService {

    private record SpeechMood(String source, String style, String pitch, double intensity) {}

    private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private static volatile Future<?> sequentialSpeechTask = null;
    private static volatile Future<?> currentSpeechTask = null;
    private static final AtomicBoolean isActivelySpeaking = new AtomicBoolean(false);
    // Invalidates already queued/in-flight speech when a higher-priority event
    // (especially returning from idle) interrupts the current utterance.
    private static final AtomicLong speechGeneration = new AtomicLong(0);
    private static volatile boolean sequenceCancelled = false;
    private static final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private static VoiceListener voiceListener;
    private static final Random random = new Random();

    private static final AtomicInteger speechQueueCount = new AtomicInteger(0);
    private static volatile boolean mediaWasPausedForSpeech = false;
    private static volatile boolean gameWasPausedForSpeech = false;
    private static final Object pauseLock = new Object();
    // Keep the media/GUI handoff tight without leaving the orb visible for a
    // long post-speech grace period. The queue count still protects chained
    // lines from resuming playback between utterances.
    private static final long SPEECH_HANDOFF_SETTLE_MS = 180L;

    public static void initialize(VoiceListener listener) {
        voiceListener = listener;
        AzureSpeechService.initialize();
        BreezeSpeechService.initialize();
        System.out.println("Ciel Debug: SpeechService initialized.");
    }

    public static Optional<VoiceListener> getVoiceListener() {
        return Optional.ofNullable(voiceListener);
    }

    public static boolean isActivelySpeaking() {
        return isActivelySpeaking.get();
    }

    private static boolean enqueueSpeech() {
        synchronized (pauseLock) {
            if (speechQueueCount.getAndIncrement() == 0) {
                SystemMonitor.SystemMetrics metrics = SystemMonitor.getSystemMetrics();
                String currentCategory = HabitTrackerService.getCurrentCategory();
                String activeProcLower = metrics.activeProcessName() != null ? metrics.activeProcessName().toLowerCase() : "";

                boolean isMediaActive = metrics.isPlayingMedia() ||
                        "Media".equalsIgnoreCase(currentCategory) ||
                        HabitTrackerService.isMediaTitle(metrics.activeWindowTitle()) ||
                        activeProcLower.contains("stremio") ||
                        activeProcLower.contains("crunchyroll");

                boolean isGamingActive = ShortTermMemoryService.getMemory().isInGamingSession() || "Gaming".equalsIgnoreCase(currentCategory);

                if (isMediaActive && !isGamingActive) {
                    mediaWasPausedForSpeech = HabitTrackerService.pauseMediaForSpeech();
                    System.out.println("[Media Transport] Speech owns pause=" + mediaWasPausedForSpeech);
                }

                if (isGamingActive && HabitTrackerService.isCurrentGamePausable()) {
                    System.out.println("Ciel Debug: Global Speech Queue active. Suspending game immediately.");
                    gameWasPausedForSpeech = true;
                    try {
                        Robot robot = new Robot();
                        AzureSpeechService.isSimulatingKeystroke = true;
                        AzureSpeechService.lastSimulatedInputTime = System.currentTimeMillis();
                        robot.keyPress(KeyEvent.VK_ESCAPE);
                        robot.keyRelease(KeyEvent.VK_ESCAPE);
                        Thread.sleep(600);
                        AzureSpeechService.isSimulatingKeystroke = false;
                    } catch (AWTException | InterruptedException ignored) {}
                }
            }
            return mediaWasPausedForSpeech;
        }
    }

    private static void dequeueSpeech() {
        if (speechQueueCount.decrementAndGet() == 0) {
            CompletableFuture.delayedExecutor(SPEECH_HANDOFF_SETTLE_MS, TimeUnit.MILLISECONDS).execute(() -> {
                synchronized (pauseLock) {
                    if (speechQueueCount.get() == 0) {
                        if (mediaWasPausedForSpeech) {
                            System.out.println("Ciel Debug: Global Speech Queue empty. Restoring media playback.");
                            mediaWasPausedForSpeech = false;
                            HabitTrackerService.resumeMediaAfterSpeech();
                        }
                        if (gameWasPausedForSpeech) {
                            System.out.println("Ciel Debug: Global Speech Queue empty. Restoring game.");
                            gameWasPausedForSpeech = false;
                            try {
                                Robot robot = new Robot();
                                AzureSpeechService.isSimulatingKeystroke = true;
                                AzureSpeechService.lastSimulatedInputTime = System.currentTimeMillis();
                                robot.keyPress(KeyEvent.VK_ESCAPE);
                                robot.keyRelease(KeyEvent.VK_ESCAPE);
                                Thread.sleep(100);
                                AzureSpeechService.isSimulatingKeystroke = false;
                            } catch (AWTException | InterruptedException ignored) {}
                        }
                        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
                    }
                }
            });
        }
    }

    public static void stopCurrentPlayback() {
        speechGeneration.incrementAndGet();
        if (currentSpeechTask != null && !currentSpeechTask.isDone()) {
            currentSpeechTask.cancel(true);
        }
        Process p = activeProcess.get();
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        if (AzureSpeechService.isAvailable()) {
            AzureSpeechService.stopAllAudio();
        }
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        isActivelySpeaking.set(false);
        if (voiceListener != null) voiceListener.setInternalMute(false);
        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
    }

    public static void cancelSequentialSpeech() {
        speechGeneration.incrementAndGet();
        sequenceCancelled = true;
        if (sequentialSpeechTask != null) {
            sequentialSpeechTask.cancel(true);
        }
        AzureSpeechService.isIntentionalCancellation = true; 
        if (currentSpeechTask != null) {
            currentSpeechTask.cancel(true);
        }
        AzureSpeechService.stopAllAudio();
        ShortTermMemoryService.getMemory().setSpeechEndTime(System.currentTimeMillis());
        isActivelySpeaking.set(false);
        // A cancelled thought must not leave the activity indicator stranded.
        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
    }

    public static void speak(String text) { speakPreformatted(text, null, false, true); }
    public static void speak(String text, boolean isRare) { speakPreformatted(text, null, isRare, true); }
    public static void speak(String text, String key) { speakPreformatted(text, key, false, true); }

    public static void speakPreformatted(String text) { speakPreformatted(text, null, false, true); }
    public static void speakPreformatted(String text, String key) { speakPreformatted(text, key, false, true); }
    public static void speakPreformatted(String text, String key, boolean isRare) { speakPreformatted(text, key, isRare, true); }

    public static void speakAnnoyed(String text) { speakPreformatted(text, null, false, true); }

    public static void speakChunk(String text) { speakPreformatted(text, null, false, false); }

    public static void speakPreformatted(String text, String key, boolean isRare, boolean flushQueue) {
        queuePreformatted(text, key, isRare, flushQueue, null);
    }

    public static void speakMediaCommentary(String text, java.util.function.Consumer<Boolean> onComplete) {
        // Generated commentary stays English internally; the active Japanese
        // voice receives final-stage phonokana only when it is about to speak.
        queuePreformatted(text, "media_commentary", false, false, spoken -> {
            // Media detection may have put the orb into THINKING before the
            // speech queue rejects the request (lock, gaming suppression,
            // failed pause confirmation, or a stale episode). Never leave a
            // cancelled thought visible.
            if (!spoken) CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
            if (onComplete != null) onComplete.accept(spoken);
        }, null);
    }

    private static void queuePreformatted(String text, String key, boolean isRare, boolean flushQueue,
                                          java.util.function.Consumer<Boolean> onComplete) {
        queuePreformatted(text, key, isRare, flushQueue, onComplete, null);
    }

    private static void queuePreformatted(String text, String key, boolean isRare, boolean flushQueue,
                                          java.util.function.Consumer<Boolean> onComplete,
                                          String forcedLanguageCode) {
        try {
            if (text == null || text.isBlank()) {
                if (onComplete != null) onComplete.accept(false);
                return;
            }
            if (WindowsApiService.isSessionLocked()) {
                if (onComplete != null) onComplete.accept(false);
                return;
            }

            boolean isGreeting = key != null && (key.startsWith("boot_greeting") || key.startsWith("login_greeting") || key.startsWith("warm_login"));

            if (!isGreeting && ShortTermMemoryService.getMemory().isInGamingSession() && !ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
                boolean isGameLaunch = "game_launch".equals(key);
                boolean isPhase3Or4 = ShortTermMemoryService.getMemory().getCurrentPhase() >= 3;
                
                if (!isGameLaunch && !isPhase3Or4) {
                    System.out.println("Ciel Debug: Suppressing non-critical speech (" + key + ") due to active Gaming Mode.");
                    if (onComplete != null) onComplete.accept(false);
                    return;
                }
            }

            if (flushQueue) {
                stopCurrentPlayback();
            }
            final long taskGeneration = speechGeneration.get();

            Matcher matcher = Pattern.compile("\\[([a-zA-Z]+)\\]").matcher(text);
            String emotionToTrigger = null;
            while (matcher.find()) {
                emotionToTrigger = matcher.group(1);
            }
            String cleanText = matcher.replaceAll("").trim();
            cleanText = cleanText.replaceAll("\\*.*?\\*", "").trim();

            if (emotionToTrigger != null && !emotionToTrigger.isBlank()) {
                final String finalEmotion = emotionToTrigger;
                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion(finalEmotion, 0.8, "Dialogue Tag"));
            }

            System.out.println("[Ciel Dialogue]: " + cleanText);

            if (isRare) {
                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Excited", 0.8, "RareDialogue"));
            }

            String langCode = forcedLanguageCode != null
                    ? forcedLanguageCode : CielVoiceManager.getActiveLanguageCode();

            String style = "default";
            String pitch = "+0%";
            String attitude = "Professional";

            if (CielState.getEmotionManager().isPresent()) {
                attitude = CielState.getEmotionManager().get().getCurrentAttitude();
                if (!"Professional".equals(attitude)) {
                    Optional<MoodConfig.AttitudeDefinition> attDef = MoodConfig.getAttitudeDef(attitude);
                    if (attDef.isPresent()) {
                        style = attDef.get().styleModifier();
                        pitch = attDef.get().pitchModifier();
                    }
                } else {
                    List<Emotion> activeEmotions = CielState.getEmotionManager().get().getEmotionalState().getActiveEmotions().values().stream()
                            .sorted(Comparator.comparingDouble(Emotion::intensity).reversed())
                            .collect(Collectors.toList());

                    if (!activeEmotions.isEmpty()) {
                        Emotion dominant = CielState.getEmotionManager().get().getSpeechEmotion().orElse(activeEmotions.get(0));
                        Optional<MoodConfig.EmotionDefinition> domDef = MoodConfig.getEmotionDef(dominant.name());
                        if (domDef.isPresent()) {
                            pitch = domDef.get().pitch();
                            style = domDef.get().ssmlStyle();
                        }
                    }
                }
                pitch = applyHumanVariance(pitch);
            }

            final String finalStyle = style;
            final String finalPitch = pitch;
            final String finalCleanText = cleanText;
            String deliverySource = attitude;
            if ("Professional".equals(attitude)) {
                deliverySource = CielState.getEmotionManager().flatMap(com.cielcompanion.mood.EmotionManager::getSpeechEmotion)
                        .map(Emotion::name).orElse("Professional");
            }
            System.out.printf("Ciel Debug (Voice): delivery emotion=%s style=%s pitch=%s%n",
                    deliverySource, finalStyle, finalPitch);

            currentSpeechTask = speechExecutor.submit(() -> {
                if (taskGeneration != speechGeneration.get()) return;
                boolean hasEnqueued = false;
                boolean completed = false;
                try {
                    String textToSpeak = finalCleanText;

                    boolean needsLanguageConversion = false;
                    boolean requiresPhonokana = !isGreeting && langCode.equals("ja-JP")
                            && Pattern.compile("[a-zA-Z]").matcher(finalCleanText).find();
                    // v4 invalidates older cached transliterations that could
                    // contain collapsed initialisms (for example DD -> ディディ).
                    String cacheKey = requiresPhonokana ? "phonokana-v4:" + key : key;
                    String speechKey = com.cielcompanion.util.SpeechText.cacheKey(cacheKey, finalCleanText, langCode);
                    boolean isAlreadyCached = AzureSpeechService.isCached(speechKey, finalStyle, langCode);

                    if (!isAlreadyCached) {
                        if (requiresPhonokana) {
                            needsLanguageConversion = true;
                        }

                        if (needsLanguageConversion) {
                            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
                        }

                        if (requiresPhonokana) {
                            textToSpeak = com.cielcompanion.ai.AIEngine.transliterateToKatakanaSync(textToSpeak);
                            System.out.println("[Ciel World Voice]: Transliterated to Katakana: " + textToSpeak);
                        } else if (!isGreeting && CielVoiceManager.isLanguageLocked()) {
                            textToSpeak = TranslationService.toJapanese(textToSpeak);
                            System.out.println("[Ciel World Voice]: Translated to: " + textToSpeak);
                        }
                    } else {
                        System.out.println("Ciel Debug: Audio is locally cached (" + key + "). Bypassing Swarm translation pipeline.");
                    }

                    if (!hasEnqueued) {
                        boolean ownsMediaPause = enqueueSpeech();
                        hasEnqueued = true;
                        if (onComplete != null && !ownsMediaPause) {
                            System.out.println("[Media Transport] Commentary deferred: playback pause was not confirmed.");
                            return;
                        }
                        try { Thread.sleep(600); } catch (Exception ignored) {}
                    }

                    if (needsLanguageConversion) {
                        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));
                    }

                    if (taskGeneration != speechGeneration.get() || Thread.currentThread().isInterrupted()) return;

                    boolean audible = executeSpeechBlocking(textToSpeak, speechKey, Settings.getTtsRate(),
                            finalStyle, finalPitch, langCode, taskGeneration,
                            shouldUseBreeze(key, isAlreadyCached));
                    completed = audible && taskGeneration == speechGeneration.get() && !Thread.currentThread().isInterrupted();
                } finally {
                    if (hasEnqueued) {
                        dequeueSpeech();
                    }
                    if (onComplete != null) onComplete.accept(completed);
                }
            });
        } catch (Throwable t) {
            System.err.println("Ciel FATAL Error: Exception caught during speakPreformatted initialization:");
            t.printStackTrace();
            if (onComplete != null) onComplete.accept(false);
        }
    }

    public static void speakSequentially(List<DialogueLine> lines, long delayMs, boolean preformatted, Runnable onComplete) {
        if (WindowsApiService.isSessionLocked()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (lines == null || lines.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        sequenceCancelled = false;
        final long taskGeneration = speechGeneration.get();

        sequentialSpeechTask = speechExecutor.submit(() -> {
            boolean hasEnqueued = false;
            try {
                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Curious", 0.7, "SequenceDialogue"));
                SpeechMood sequenceMood = resolveSpeechMood();
                String sequencePitch = applyHumanVariance(sequenceMood.pitch());
                for (int i = 0; i < lines.size(); i++) {
                    
                    if (WindowsApiService.isSessionLocked() || sequenceCancelled || Thread.currentThread().isInterrupted()
                            || taskGeneration != speechGeneration.get()) {
                        System.out.println("Ciel Debug: Sequential speech loop explicitly broken via flag.");
                        break;
                    }

                    DialogueLine line = lines.get(i);
                    if (line != null && line.text() != null && !line.text().isBlank()) {
                        String textToSpeak = line.text();

                        Matcher matcher = Pattern.compile("\\[([a-zA-Z]+)\\]").matcher(textToSpeak);
                        String emotionToTrigger = null;
                        while (matcher.find()) {
                            emotionToTrigger = matcher.group(1);
                        }
                        textToSpeak = matcher.replaceAll("").trim();
                        textToSpeak = textToSpeak.replaceAll("\\*.*?\\*", "").trim();

                        if (emotionToTrigger != null && !emotionToTrigger.isBlank()) {
                            final String finalEmotion = emotionToTrigger;
                            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion(finalEmotion, 0.8, "Dialogue Tag"));
                            sequenceMood = resolveSpeechMood();
                            sequencePitch = applyHumanVariance(sequenceMood.pitch());
                            System.out.println("Ciel Debug (Voice): Dialogue emotion tag changed sequence delivery to "
                                    + sequenceMood.source() + ".");
                        } else {
                            SpeechMood observedMood = resolveSpeechMood();
                            if (isMajorSpeechMoodShift(sequenceMood, observedMood)) {
                                sequenceMood = observedMood;
                                sequencePitch = applyHumanVariance(sequenceMood.pitch());
                                System.out.println("Ciel Debug (Voice): Speech delivery shifted to "
                                        + sequenceMood.source() + " after a high-intensity mood change.");
                            }
                        }

                        String style = sequenceMood.style();
                        String pitch = sequencePitch;

                        String langCode = CielVoiceManager.getActiveLanguageCode();

                        boolean needsLanguageConversion = false;
                        boolean requiresPhonokana = !line.key().startsWith("boot_greeting")
                                && !line.key().startsWith("login_greeting")
                                && !line.key().startsWith("warm_login")
                                && langCode.equals("ja-JP")
                                && Pattern.compile("[a-zA-Z]").matcher(textToSpeak).find();
                        String cacheKey = requiresPhonokana ? "phonokana-v4:" + line.key() : line.key();
                        String speechKey = com.cielcompanion.util.SpeechText.cacheKey(cacheKey, textToSpeak, langCode);
                        boolean isAlreadyCached = AzureSpeechService.isCached(speechKey, style, langCode);

                        if (!isAlreadyCached) {
                            if (requiresPhonokana) {
                                needsLanguageConversion = true;
                            }

                            if (needsLanguageConversion) {
                                CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
                            }

                            if (requiresPhonokana) {
                                textToSpeak = com.cielcompanion.ai.AIEngine.transliterateToKatakanaSync(textToSpeak);
                            } else if (!line.key().startsWith("boot_greeting") && !line.key().startsWith("login_greeting")
                                    && !line.key().startsWith("warm_login") && CielVoiceManager.isLanguageLocked()) {
                                textToSpeak = TranslationService.toJapanese(textToSpeak);
                            }
                        }

                        if (!hasEnqueued) {
                            enqueueSpeech();
                            hasEnqueued = true;
                            try { Thread.sleep(600); } catch (Exception ignored) {}
                        }

                        if (taskGeneration != speechGeneration.get() || Thread.currentThread().isInterrupted()) break;
                        executeSpeechBlocking(textToSpeak, speechKey, Settings.getTtsRate(), style, pitch, langCode, taskGeneration,
                                shouldUseBreeze(line.key(), isAlreadyCached));

                        if (sequenceCancelled || Thread.currentThread().isInterrupted()
                                || taskGeneration != speechGeneration.get()) {
                            System.out.println("Ciel Debug: Sequential speech loop explicitly broken via flag.");
                            break;
                        }

                        if (i < lines.size() - 1) {
                            try {
                                Thread.sleep(delayMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
                if (hasEnqueued) {
                    dequeueSpeech();
                }
                if (onComplete != null) onComplete.run();
                sequentialSpeechTask = null;
            }
        });
    }

    private static boolean executeSpeechBlocking(String text, String key, int rate, String style, String pitch,
                                              String langCode, long taskGeneration, boolean breezeEligible) {
        if (WindowsApiService.isSessionLocked() || Thread.currentThread().isInterrupted()
                || taskGeneration != speechGeneration.get()) return false;

        try {
            if (voiceListener != null) voiceListener.setInternalMute(true);
            isActivelySpeaking.set(true);
            AzureSpeechService.isIntentionalCancellation = false;

            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));

            boolean breezeSuccess = false;
            // Once a Breeze file exists, it is the preferred local voice. A
            // miss does not spend credits or delay speech: Azure supplies the
            // line immediately and queues a one-time Breeze prefetch below.
            if (BreezeSpeechService.isAvailable() && breezeEligible) {
                boolean breezeCacheHit = BreezeSpeechService.isCached(text, key, style, pitch, langCode);
                boolean warmupComplete = Settings.isBreezeDynamicPrimary()
                        && BreezeSpeechService.isWarmupComplete();
                if (breezeCacheHit || warmupComplete) {
                    breezeSuccess = BreezeSpeechService.speak(text, key, style, pitch, langCode);
                    if (breezeSuccess) {
                        System.out.println(breezeCacheHit
                                ? "[Breeze TTS] Cached line selected before Azure."
                                : "[Breeze TTS] Warm-up threshold reached; generated line selected before Azure.");
                    }
                }
            }

            boolean azureSuccess = false;
            if (!breezeSuccess && AzureSpeechService.isAvailable()) {
                azureSuccess = AzureSpeechService.speak(text, key, style, pitch, langCode);
                if (azureSuccess) {
                    System.out.println("Ciel Debug: Azure Speech successful (Key: " + (key != null ? key : "Dynamic") + ", Style: " + style + ")");
                    queueBreezePrefetch(text, key, style, pitch, langCode);
                } else {
                    System.out.println("Ciel Warning: Azure Speech failed or skipped; trying Breeze generation.");
                }
            }

            // If both the Breeze cache and Azure are unavailable, a direct
            // Breeze request is still the last hosted-provider fallback.
            if (!breezeSuccess && !azureSuccess && BreezeSpeechService.isAvailable() && breezeEligible) {
                breezeSuccess = BreezeSpeechService.speak(text, key, style, pitch, langCode);
                if (breezeSuccess) {
                    System.out.println("Ciel Debug: Breeze Speech successful (generated fallback).");
                }
            }

            boolean sapiSuccess = false;
            if (!azureSuccess && !breezeSuccess && !AzureSpeechService.isIntentionalCancellation
                    && taskGeneration == speechGeneration.get() && !Thread.currentThread().isInterrupted()) {
                System.out.println("Ciel Debug: SAPI Speaking (Clean Fallback): \"" + text + "\"");

                String safeSsml = AzureSpeechService.buildFallbackSsml(text, langCode).replace("'", "''");
                
                String psScript = "$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                        + "Add-Type -AssemblyName System.Speech; "
                        + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                        + "$s.SetOutputToDefaultAudioDevice(); "
                        + "try { $s.SelectVoice('Microsoft Haruka Desktop'); } catch { try { $s.SelectVoiceByHints('Female') } catch {} } "
                        + "$s.Rate = 0; "
                        + "$s.SpeakSsml('" + safeSsml + "'); "
                        + "$s.Dispose();";
                
                String encodedCommand = Base64.getEncoder().encodeToString(psScript.getBytes(StandardCharsets.UTF_16LE));

                ProcessBuilder pb = new ProcessBuilder(com.cielcompanion.util.ProjectPaths.windowlessCommand(java.util.List.of(
                        "pwsh.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedCommand)))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                try {
                    Process p = pb.start();
                    activeProcess.set(p);
                    long sapiTimeoutSeconds = Math.max(15, Math.min(90, (estimateSpeechDuration(text) / 1000) + 10));
                    if (p.waitFor(sapiTimeoutSeconds, TimeUnit.SECONDS)) sapiSuccess = p.exitValue() == 0;
                    else p.destroyForcibly();
                } catch (Exception ignored) {
                } finally {
                    activeProcess.set(null);
                }
            }
            return azureSuccess || breezeSuccess || sapiSuccess;
        } finally {
            isActivelySpeaking.set(false);
            if (voiceListener != null) voiceListener.setInternalMute(false);

            long exactEndTime = System.currentTimeMillis();

            if (text.length() < 15 && ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
                System.out.println("Ciel Debug: Short acknowledgment detected. Backdating speech timer to bypass Ghost Echo filter.");
                ShortTermMemoryService.getMemory().setSpeechEndTime(exactEndTime - 3100);
            } else {
                ShortTermMemoryService.getMemory().setSpeechEndTime(exactEndTime);
            }

            if (ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
                ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
            }
        }
    }

    private static boolean shouldUseBreeze(String originalKey, boolean alreadyCached) {
        // Provider order is enforced in executeSpeechBlocking. This flag keeps
        // Breeze available for cache hits and for the final hosted fallback.
        return BreezeSpeechService.isAvailable();
    }

    private static void queueBreezePrefetch(String text, String key, String style, String pitch, String langCode) {
        if (!Settings.isBreezePrefetchEnabled() || key == null || key.isBlank()
                || !BreezeSpeechService.isAvailable()
                || BreezeSpeechService.isCached(text, key, style, pitch, langCode)) return;
        CompletableFuture.runAsync(() -> {
            if (BreezeSpeechService.prefetch(text, key, style, pitch, langCode)) {
                System.out.println("[Breeze TTS] Cached companion line for future fallback use.");
            }
        });
    }

    private static String applyHumanVariance(String basePitch) {
        if (basePitch.equals("default")) return "+0%";
        try {
            String clean = basePitch.replace("%", "").replace("+", "");
            if (clean.isEmpty()) return "+0%";
            int val = Integer.parseInt(clean);
            int variance = random.nextInt(5) - 2;
            int newVal = val + variance;
            return (newVal >= 0 ? "+" : "") + newVal + "%";
        } catch (Exception e) {
            return basePitch;
        }
    }

    public static long estimateSpeechDuration(String text) {
        if (text == null || text.isBlank()) return 0;
        return (long) (text.length() * 115) + 400;
    }

    public static void cleanup() {
        speechExecutor.shutdownNow();
    }

    private static SpeechMood resolveSpeechMood() {
        return CielState.getEmotionManager().map(manager -> {
            String attitude = manager.getCurrentAttitude();
            if (!"Professional".equals(attitude)) {
                Optional<MoodConfig.AttitudeDefinition> definition = MoodConfig.getAttitudeDef(attitude);
                if (definition.isPresent()) {
                    MoodConfig.AttitudeDefinition value = definition.get();
                    return new SpeechMood("attitude:" + attitude, normalizeSpeechStyle(value.styleModifier()),
                            normalizeSpeechPitch(value.pitchModifier()), 1.0);
                }
            }

            return manager.getSpeechEmotion()
                    .map(emotion -> MoodConfig.getEmotionDef(emotion.name())
                            .map(definition -> new SpeechMood(emotion.name(),
                                    normalizeSpeechStyle(definition.ssmlStyle()),
                                    normalizeSpeechPitch(definition.pitch()), emotion.intensity()))
                            .orElse(new SpeechMood(emotion.name(), "default", "+0%", emotion.intensity())))
                    .orElse(new SpeechMood("Professional", "default", "+0%", 0.0));
        }).orElse(new SpeechMood("Professional", "default", "+0%", 0.0));
    }

    private static boolean isMajorSpeechMoodShift(SpeechMood current, SpeechMood candidate) {
        return !current.source().equals(candidate.source())
                && !current.style().equals(candidate.style())
                && candidate.intensity() >= 0.9;
    }

    private static String normalizeSpeechStyle(String style) {
        return style == null || style.isBlank() ? "default" : style;
    }

    private static String normalizeSpeechPitch(String pitch) {
        return pitch == null || pitch.isBlank() || pitch.equalsIgnoreCase("default") ? "+0%" : pitch;
    }

}
