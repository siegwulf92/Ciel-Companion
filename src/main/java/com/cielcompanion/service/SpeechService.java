package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.SpokenLine;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.ui.CielGui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import java.util.stream.Collectors;

/**
 * Handles Text-to-Speech operations using Azure Cognitive Services.
 * INTEGRATED: Universal Media Queuing, World Voice (Tensura) support,
 * dynamic emotion variance, and stuttering logic.
 */
public class SpeechService {

    private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private static volatile Future<?> sequentialSpeechTask = null;
    private static volatile Future<?> currentSpeechTask = null; 
    private static final AtomicBoolean isActivelySpeaking = new AtomicBoolean(false);
    
    private static volatile boolean sequenceCancelled = false;
    
    private static final AtomicReference<Process> activeProcess = new AtomicReference<>();
    
    private static VoiceListener voiceListener;
    private static final Random random = new Random();

    // --- GLOBAL MEDIA MANAGER ---
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
    
    // External exposure for CommandService termination waiting
    public static boolean isActivelySpeaking() {
        return isActivelySpeaking.get();
    }
    
    // --- THE UNIFIED PAUSE PROTOCOL ---
    private static void enqueueSpeech() {
        synchronized(pauseLock) {
            if (speechQueueCount.getAndIncrement() == 0) {
                SystemMetrics metrics = SystemMonitor.getSystemMetrics();
                ShortTermMemory memory = ShortTermMemoryService.getMemory();
                String currentCategory = HabitTrackerService.getCurrentCategory();
                
                boolean isMediaActive = metrics.isPlayingMedia() || "Media".equalsIgnoreCase(currentCategory);
                boolean isGamingActive = memory.isInGamingSession() || "Gaming".equalsIgnoreCase(currentCategory);
                
                if (isMediaActive && !isGamingActive) {
                    System.out.println("Ciel Debug: Global Speech Queue active. Media detected. Suspending playback immediately.");
                    mediaWasPausedForSpeech = true;
                    HabitTrackerService.toggleMediaPlayback();
                    try { Thread.sleep(400); } catch(Exception ignored) {}
                }
                
                if (isGamingActive && HabitTrackerService.isCurrentGamePausable()) {
                    System.out.println("Ciel Debug: Global Speech Queue active. Suspending game immediately.");
                    gameWasPausedForSpeech = true;
                    try {
                        java.awt.Robot robot = new java.awt.Robot();
                        AzureSpeechService.isSimulatingKeystroke = true;
                        AzureSpeechService.lastSimulatedInputTime = System.currentTimeMillis();
                        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
                        Thread.sleep(600);
                        AzureSpeechService.isSimulatingKeystroke = false;
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static void dequeueSpeech() {
        if (speechQueueCount.decrementAndGet() == 0) {
            // Schedule unpause with a short delay to bridge rapid consecutive speak() calls smoothly
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                synchronized(pauseLock) {
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
                                java.awt.Robot robot = new java.awt.Robot();
                                AzureSpeechService.isSimulatingKeystroke = true;
                                AzureSpeechService.lastSimulatedInputTime = System.currentTimeMillis();
                                robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
                                robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
                                Thread.sleep(100);
                                AzureSpeechService.isSimulatingKeystroke = false;
                            } catch (Exception ignored) {}
                        }
                        // --- CRITICAL GUI FIX: Set GUI to IDLE only after the entire queue bridge resolves! ---
                        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
                    }
                }
            }, 1500, TimeUnit.MILLISECONDS);
        }
    }

    public static void cancelSequentialSpeech() {
        sequenceCancelled = true;
        if (sequentialSpeechTask != null) {
            sequentialSpeechTask.cancel(true);
        }
        ShortTermMemoryService.getMemory().setSpeechEndTime(System.currentTimeMillis());
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

    public static void speak(String text) { speakPreformatted(text, null, false, true); }
    public static void speak(String text, boolean isRare) { speakPreformatted(text, null, isRare, true); }
    public static void speak(String text, String key) { speakPreformatted(text, key, false, true); }

    public static void speakPreformatted(String text) { speakPreformatted(text, null, false, true); }
    public static void speakPreformatted(String text, String key) { speakPreformatted(text, key, false, true); }
    
    public static void speakPreformatted(String text, String key, boolean isRare) { speakPreformatted(text, key, isRare, true); }

    public static void speakAnnoyed(String text) { speakPreformatted(text, null, false, true); }

    public static void speakChunk(String text) { speakPreformatted(text, null, false, false); }

    public static void speakPreformatted(String text, String key, boolean isRare, boolean flushQueue) {
        if (text == null || text.isBlank()) return;

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

                // 1. Allow media to play while she thinks/translates
                if (CielVoiceManager.isLanguageLocked()) {
                    CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
                    textToSpeak = TranslationService.toJapanese(textToSpeak);
                    System.out.println("[Ciel World Voice]: Translated to: " + textToSpeak);
                } else if (langCode.equals("ja-JP") && Pattern.compile("[a-zA-Z]").matcher(textToSpeak).find()) {
                    CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
                    textToSpeak = com.cielcompanion.ai.AIEngine.transliterateToKatakanaSync(textToSpeak);
                }

                if ("Glitched".equals(finalAttitude) || "Concerned".equals(finalAttitude)) {
                    textToSpeak = applyStutter(textToSpeak);
                }

                // 2. CRITICAL FIX: Lock the media exactly as the audio is prepared to fire!
                enqueueSpeech();
                hasEnqueued = true;

                long estimatedDuration = estimateSpeechDuration(textToSpeak);
                ShortTermMemoryService.getMemory().setSpeechEndTime(System.currentTimeMillis() + estimatedDuration);

                // 3. Audio physically outputs to speakers
                executeSpeechBlocking(textToSpeak, key, Settings.getTtsRate(), finalStyle, finalPitch, langCode);
            } finally {
                // 4. Cleanly release lock when finished
                if (hasEnqueued) {
                    dequeueSpeech();
                }
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
    
    private static String applyStutter(String input) {
        if (random.nextInt(10) > 3) return input; 
        String[] words = input.split(" ");
        if (words.length == 0) return input;
        int targetIdx = random.nextInt(words.length);
        String targetWord = words[targetIdx];
        if (targetWord.length() > 2) {
            String stutter = targetWord.substring(0, 2) + "-" + targetWord;
            words[targetIdx] = stutter;
            return String.join(" ", words);
        }
        return input;
    }
    
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
                                 if (attDef.isPresent()) { pitch = attDef.get().pitchModifier(); style = attDef.get().styleModifier(); }
                             } else {
                                 List<Emotion> active = CielState.getEmotionManager().get().getEmotionalState().getActiveEmotions().values().stream()
                                     .sorted(Comparator.comparingDouble(Emotion::intensity).reversed()).collect(Collectors.toList());
                                 if(!active.isEmpty()) {
                                     Optional<MoodConfig.EmotionDefinition> def = MoodConfig.getEmotionDef(active.get(0).name());
                                     if(def.isPresent()) { pitch = def.get().pitch(); style = def.get().ssmlStyle(); }
                                 }
                             }
                             pitch = applyHumanVariance(pitch);
                        }

                        String langCode = CielVoiceManager.getActiveLanguageCode();
                        
                        // 1. Think & Translate first
                        if (CielVoiceManager.isLanguageLocked()) {
                            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
                            textToSpeak = TranslationService.toJapanese(textToSpeak);
                        } else if (langCode.equals("ja-JP") && Pattern.compile("[a-zA-Z]").matcher(textToSpeak).find()) {
                            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
                            textToSpeak = com.cielcompanion.ai.AIEngine.transliterateToKatakanaSync(textToSpeak);
                        }
                         
                        if ("Glitched".equals(attitude) || "Concerned".equals(attitude)) {
                            textToSpeak = applyStutter(textToSpeak);
                        }

                        // 2. Lock the media directly before the first sentence fires, and hold it
                        if (!hasEnqueued) {
                            enqueueSpeech();
                            hasEnqueued = true;
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

    private static void executeSpeech(String text, String key, int rate, String style, String pitch, String langCode) {
        currentSpeechTask = speechExecutor.submit(() -> executeSpeechBlocking(text, key, rate, style, pitch, langCode));
    }

    private static void executeSpeechBlocking(String text, String key, int rate, String style, String pitch, String langCode) {
        if (Thread.currentThread().isInterrupted()) return;

        try {
            if (voiceListener != null) voiceListener.setInternalMute(true);
            isActivelySpeaking.set(true);
            AzureSpeechService.isIntentionalCancellation = false;

            boolean azureSuccess = false;

            if (AzureSpeechService.isAvailable()) {
                azureSuccess = AzureSpeechService.speak(text, key, style, pitch, langCode);
                if (azureSuccess) {
                    System.out.println("Ciel Debug: Azure Speech successful (Key: " + (key != null ? key : "Dynamic") + ", Style: " + style + ")");
                } else {
                    System.out.println("Ciel Warning: Azure Speech failed or skipped. Falling back to SAPI.");
                }
            }
            
            // Only trigger SAPI if Azure failed, AND we didn't explicitly tell it to stop via VoiceAttack
            if (!azureSuccess && !AzureSpeechService.isIntentionalCancellation) {
                CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));
                
                String targetVoice = Settings.getVoiceNameHint();
                System.out.println("Ciel Debug: SAPI Speaking: \"" + text + "\" (Target: " + targetVoice + ")");
                
                String safeText = text.replace("'", "''");
                String safeVoice = targetVoice.replace("'", "''");
                String psScript = "$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.SetOutputToDefaultAudioDevice(); try { $s.SelectVoice('" + safeVoice + "'); } catch {} $s.Rate = " + rate + "; $s.Speak('" + safeText + "'); $s.Dispose();";
                String encodedCommand = Base64.getEncoder().encodeToString(psScript.getBytes(StandardCharsets.UTF_16LE));
                
                ProcessBuilder pb = new ProcessBuilder("pwsh.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedCommand);
                try { 
                    Process p = pb.start();
                    activeProcess.set(p);
                    p.waitFor(15, TimeUnit.SECONDS); 
                } catch (Exception e) {
                } finally {
                    activeProcess.set(null);
                }
            }
        } finally {
            isActivelySpeaking.set(false);
            if (voiceListener != null) voiceListener.setInternalMute(false);
            // --- CRITICAL GUI FIX: The IDLE state is no longer forced here. 
            // It is deferred to the 1500ms delay in dequeueSpeech() to prevent blipping! ---
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