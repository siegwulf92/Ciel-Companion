package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.ui.CielGui;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.util.CielTools;
import com.cielcompanion.service.Settings;
import com.cielcompanion.service.AzureSpeechService;
import com.cielcompanion.service.CielVoiceManager;
import com.cielcompanion.service.VoiceListener;
import com.cielcompanion.service.TranslationService;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Handles Text-to-Speech operations using Azure Cognitive Services.
 * Integrated: universal media queuing, World Voice (Tensura) support,
 * and dynamic emotion variance.
 */
public class SpeechService {

    /* ------------------------------------------------------------------ */
    /*  Thread-pools & state tracking                                      */
    /* ------------------------------------------------------------------ */
    private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private static volatile Future<?> sequentialSpeechTask = null;
    private static volatile Future<?> currentSpeechTask = null;
    private static final AtomicBoolean isActivelySpeaking = new AtomicBoolean(false);
    private static volatile boolean sequenceCancelled = false;
    private static final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private static VoiceListener voiceListener;
    private static final Random random = new Random();

    /* ------------------------------------------------------------------ */
    /*  GLOBAL MEDIA MANAGER – pause-while-speaking logic                  */
    /* ------------------------------------------------------------------ */
    private static final AtomicInteger speechQueueCount = new AtomicInteger(0);
    private static volatile boolean mediaWasPausedForSpeech = false;
    private static volatile boolean gameWasPausedForSpeech = false;
    private static final Object pauseLock = new Object();

    public static void initialize(VoiceListener listener) {
        voiceListener = listener;
        AzureSpeechService.initialize();
        System.out.println("Ciel Debug: SpeechService initialized.");
    }

    public static Optional<VoiceListener> getVoiceListener() {
        return Optional.ofNullable(voiceListener);
    }

    public static boolean isActivelySpeaking() {
        return isActivelySpeaking.get();
    }

    /* ------------------------------------------------------------------ */
    /*  CORE PAUSE/RESUME LOGIC                                            */
    /* ------------------------------------------------------------------ */
    private static void enqueueSpeech() {
        synchronized (pauseLock) {
            if (speechQueueCount.getAndIncrement() == 0) {
                SystemMetrics metrics = SystemMonitor.getSystemMetrics();
                ShortTermMemory memory = ShortTermMemoryService.getMemory();
                String currentCategory = HabitTrackerService.getCurrentCategory();

                String activeProcLower = metrics.activeProcessName() != null ? metrics.activeProcessName().toLowerCase() : "";

                boolean isMediaActive = metrics.isPlayingMedia() ||
                        "Media".equalsIgnoreCase(currentCategory) ||
                        HabitTrackerService.isMediaTitle(metrics.activeWindowTitle()) ||
                        activeProcLower.contains("stremio") ||
                        activeProcLower.contains("crunchyroll");

                boolean isGamingActive = memory.isInGamingSession() || "Gaming".equalsIgnoreCase(currentCategory);

                if (isMediaActive && !isGamingActive) {
                    System.out.println("Ciel Debug: Global Speech Queue active. Media detected. Suspending playback immediately.");
                    mediaWasPausedForSpeech = true;
                    // Properly delegates to HabitTracker to execute the OS-level pause
                    HabitTrackerService.toggleMediaPlayback();
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
        }
    }

    private static void dequeueSpeech() {
        if (speechQueueCount.decrementAndGet() == 0) {
            // Maintains the 1.5s natural pause buffer AFTER Ciel stops speaking before resuming the media
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                synchronized (pauseLock) {
                    if (speechQueueCount.get() == 0) {
                        if (mediaWasPausedForSpeech) {
                            System.out.println("Ciel Debug: Global Speech Queue empty. Restoring media playback.");
                            mediaWasPausedForSpeech = false;
                            HabitTrackerService.toggleMediaPlayback();
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
                        // FIX: Safely return GUI to idle ONLY when the entire queue is empty
                        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
                    }
                }
            }, 1500, TimeUnit.MILLISECONDS);
        }
    }

    public static void stopCurrentPlayback() {
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
        sequenceCancelled = true;
        if (sequentialSpeechTask != null) {
            sequentialSpeechTask.cancel(true);
        }
        ShortTermMemoryService.getMemory().setSpeechEndTime(System.currentTimeMillis());
    }

    /* ------------------------------------------------------------------ */
    /*  SPEAK API                                                          */
    /* ------------------------------------------------------------------ */
    public static void speak(String text) { speakPreformatted(text, null, false, true); }
    public static void speak(String text, boolean isRare) { speakPreformatted(text, null, isRare, true); }
    public static void speak(String text, String key) { speakPreformatted(text, key, false, true); }

    public static void speakPreformatted(String text) { speakPreformatted(text, null, false, true); }
    public static void speakPreformatted(String text, String key) { speakPreformatted(text, key, false, true); }
    public static void speakPreformatted(String text, String key, boolean isRare) { speakPreformatted(text, key, isRare, true); }

    public static void speakAnnoyed(String text) { speakPreformatted(text, null, false, true); }

    public static void speakChunk(String text) { speakPreformatted(text, null, false, false); }

    public static void speakPreformatted(String text, String key, boolean isRare, boolean flushQueue) {
        try {
            if (text == null || text.isBlank()) return;

            // CRITICAL FIX: Ensure greetings are ALWAYS spoken, completely ignoring gaming suppression.
            boolean isGreeting = key != null && (key.startsWith("boot_greeting") || key.startsWith("login_greeting") || key.startsWith("warm_login"));

            // Suppress unwanted background chatter while gaming
            if (!isGreeting && ShortTermMemoryService.getMemory().isInGamingSession() && !ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
                boolean isGameLaunch = "game_launch".equals(key);
                boolean isPhase3Or4 = ShortTermMemoryService.getMemory().getCurrentPhase() >= 3;
                
                // If it's not the initial launch comment, and we aren't heavily idle, suppress it.
                if (!isGameLaunch && !isPhase3Or4) {
                    System.out.println("Ciel Debug: Suppressing non-critical speech (" + key + ") due to active Gaming Mode.");
                    return;
                }
            }

            if (flushQueue) {
                stopCurrentPlayback();
            }

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

            String langCode = CielVoiceManager.getActiveLanguageCode();

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
                        Emotion dominant = activeEmotions.get(0);
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
            final String finalAttitude = attitude;
            final String finalCleanText = cleanText;

            currentSpeechTask = speechExecutor.submit(() -> {
                boolean hasEnqueued = false;
                try {
                    String textToSpeak = finalCleanText;

                    // --------------------------------------------------------------
                    //  ★  HARD CACHE BYPASS: PREVENTS KATAKANA NETWORK FREEZING ★
                    // --------------------------------------------------------------
                    boolean needsLanguageConversion = false;
                    boolean isAlreadyCached = AzureSpeechService.isCached(key, finalStyle, langCode);

                    if (!isAlreadyCached) {
                        if (CielVoiceManager.isLanguageLocked()) {
                            needsLanguageConversion = true;
                        } else if (langCode.equals("ja-JP") && Pattern.compile("[a-zA-Z]").matcher(textToSpeak).find()) {
                            needsLanguageConversion = true;
                        }

                        if (needsLanguageConversion) {
                            // Update GUI, but do NOT pause media yet!
                            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
                        }

                        if (CielVoiceManager.isLanguageLocked()) {
                            textToSpeak = TranslationService.toJapanese(textToSpeak);
                            System.out.println("[Ciel World Voice]: Translated to: " + textToSpeak);
                        } else if (langCode.equals("ja-JP") && Pattern.compile("[a-zA-Z]").matcher(textToSpeak).find()) {
                            textToSpeak = com.cielcompanion.ai.AIEngine.transliterateToKatakanaSync(textToSpeak);
                            System.out.println("[Ciel World Voice]: Transliterated to Katakana: " + textToSpeak);
                        }
                    } else {
                        System.out.println("Ciel Debug: Audio is locally cached (" + key + "). Bypassing Swarm translation pipeline.");
                    }
                    // --------------------------------------------------------------

                    // CRITICAL FIX: The Katakana translation might have taken 5-10 seconds.
                    // We only enqueue the speech (and thus pause the media) right now, exactly
                    // 600ms before she begins physically speaking.
                    if (!hasEnqueued) {
                        enqueueSpeech();
                        hasEnqueued = true;
                        try { Thread.sleep(600); } catch (Exception ignored) {}
                    }

                    if (needsLanguageConversion) {
                        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));
                    }

                    executeSpeechBlocking(textToSpeak, key, Settings.getTtsRate(),
                            finalStyle, finalPitch, langCode);
                } finally {
                    if (hasEnqueued) {
                        dequeueSpeech();
                    }
                }
            });
        } catch (Throwable t) {
            System.err.println("Ciel FATAL Error: Exception caught during speakPreformatted initialization:");
            t.printStackTrace();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Sequential speech (still uses the same queue logic)                */
    /* ------------------------------------------------------------------ */
    public static void speakSequentially(List<DialogueLine> lines, long delayMs, boolean preformatted, Runnable onComplete) {
        if (lines == null || lines.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        sequenceCancelled = false;

        sequentialSpeechTask = speechExecutor.submit(() -> {
            boolean hasEnqueued = false;
            try {
                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Curious", 0.7, "SequenceDialogue"));
                for (int i = 0; i < lines.size(); i++) {
                    
                    if (sequenceCancelled || Thread.currentThread().isInterrupted()) {
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
                        }

                        String style = "default";
                        String pitch = "+0%";
                        String attitude = "Professional";

                        if (CielState.getEmotionManager().isPresent()) {
                             attitude = CielState.getEmotionManager().get().getCurrentAttitude();
                             if (!"Professional".equals(attitude)) {
                                 Optional<MoodConfig.AttitudeDefinition> attDef = MoodConfig.getAttitudeDef(attitude);
                                 if (attDef.isPresent()) {
                                     pitch = attDef.get().pitchModifier();
                                     style = attDef.get().styleModifier();
                                 }
                             } else {
                                 List<Emotion> active = CielState.getEmotionManager().get()
                                         .getEmotionalState()
                                         .getActiveEmotions()
                                         .values()
                                         .stream()
                                         .sorted(Comparator.comparingDouble(Emotion::intensity).reversed())
                                         .collect(Collectors.toList());
                                 if (!active.isEmpty()) {
                                     Optional<MoodConfig.EmotionDefinition> def = MoodConfig.getEmotionDef(active.get(0).name());
                                     if (def.isPresent()) {
                                         pitch = def.get().pitch();
                                         style = def.get().ssmlStyle();
                                     }
                                 }
                             }
                             pitch = applyHumanVariance(pitch);
                        }

                        String langCode = CielVoiceManager.getActiveLanguageCode();

                        // --- HARD CACHE BYPASS ---
                        boolean needsLanguageConversion = false;
                        boolean isAlreadyCached = AzureSpeechService.isCached(line.key(), style, langCode);

                        if (!isAlreadyCached) {
                            if (CielVoiceManager.isLanguageLocked()) {
                                needsLanguageConversion = true;
                            } else if (langCode.equals("ja-JP") && Pattern.compile("[a-zA-Z]").matcher(textToSpeak).find()) {
                                needsLanguageConversion = true;
                            }

                            if (needsLanguageConversion) {
                                CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
                            }

                            if (CielVoiceManager.isLanguageLocked()) {
                                textToSpeak = TranslationService.toJapanese(textToSpeak);
                            } else if (langCode.equals("ja-JP") && Pattern.compile("[a-zA-Z]").matcher(textToSpeak).find()) {
                                textToSpeak = com.cielcompanion.ai.AIEngine.transliterateToKatakanaSync(textToSpeak);
                            }
                        }

                        if (!hasEnqueued) {
                            enqueueSpeech();
                            hasEnqueued = true;
                            try { Thread.sleep(600); } catch (Exception ignored) {}
                        }

                        executeSpeechBlocking(textToSpeak, line.key(), Settings.getTtsRate(), style, pitch, langCode);

                        if (sequenceCancelled || Thread.currentThread().isInterrupted()) {
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

    private static void executeSpeechBlocking(String text, String key, int rate, String style, String pitch, String langCode) {
        if (Thread.currentThread().isInterrupted()) return;

        try {
            if (voiceListener != null) voiceListener.setInternalMute(true);
            isActivelySpeaking.set(true);
            AzureSpeechService.isIntentionalCancellation = false;

            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));

            boolean azureSuccess = false;

            if (AzureSpeechService.isAvailable()) {
                azureSuccess = AzureSpeechService.speak(text, key, style, pitch, langCode);
                if (azureSuccess) {
                    System.out.println("Ciel Debug: Azure Speech successful (Key: " + (key != null ? key : "Dynamic") + ", Style: " + style + ")");
                } else {
                    System.out.println("Ciel Warning: Azure Speech failed or skipped. Falling back to SAPI.");
                }
            }

            if (!azureSuccess && !AzureSpeechService.isIntentionalCancellation) {
                System.out.println("Ciel Debug: SAPI Speaking (Clean Fallback): \"" + text + "\"");

                String safeText = text.replace("'", "''");
                
                // Enforce a clean default SAPI fallback, ignoring all Azure pitch/style modifiers.
                // We explicitly attempt to catch Microsoft Haruka Desktop so Katakana reads correctly.
                String psScript = "$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                        + "Add-Type -AssemblyName System.Speech; "
                        + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                        + "$s.SetOutputToDefaultAudioDevice(); "
                        + "try { $s.SelectVoice('Microsoft Haruka Desktop'); } catch { try { $s.SelectVoiceByHints('Female') } catch {} } "
                        + "$s.Rate = 0; "
                        + "$s.Speak('" + safeText + "'); "
                        + "$s.Dispose();";
                
                String encodedCommand = Base64.getEncoder().encodeToString(psScript.getBytes(StandardCharsets.UTF_16LE));

                ProcessBuilder pb = new ProcessBuilder("pwsh.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedCommand);
                try {
                    Process p = pb.start();
                    activeProcess.set(p);
                    p.waitFor(15, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                } finally {
                    activeProcess.set(null);
                }
            }
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
}