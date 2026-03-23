package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.ui.CielGui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Handles Text-to-Speech operations using Azure Cognitive Services.
 * INTEGRATED: Combines original logic with World Voice (Tensura) support,
 * dynamic emotion variance, and stuttering logic.
 */
public class SpeechService {

    private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private static volatile Future<?> sequentialSpeechTask = null;
    private static volatile Future<?> currentSpeechTask = null; // Tracks the active speech task for interruptions
    private static final AtomicBoolean isActivelySpeaking = new AtomicBoolean(false);
    
    private static final AtomicReference<Process> activeProcess = new AtomicReference<>();
    
    private static VoiceListener voiceListener;
    private static final Random random = new Random();

    public static void initialize(VoiceListener listener) {
        voiceListener = listener;
        AzureSpeechService.initialize();
        System.out.println("Ciel Debug: SpeechService initialized.");
    }

    public static Optional<VoiceListener> getVoiceListener() {
        return Optional.ofNullable(voiceListener);
    }
    
    public static void cancelSequentialSpeech() {
        if (sequentialSpeechTask != null) {
            sequentialSpeechTask.cancel(true);
            sequentialSpeechTask = null;
        }
        ShortTermMemoryService.getMemory().setSpeechEndTime(System.currentTimeMillis());
    }

    // --- Hard-Kills the currently playing audio ---
    public static void stopCurrentPlayback() {
        if (currentSpeechTask != null && !currentSpeechTask.isDone()) {
            currentSpeechTask.cancel(true);
        }
        Process p = activeProcess.get();
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        
        // Add a 250ms delay to allow the OS audio buffer to completely flush.
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        isActivelySpeaking.set(false);
        if (voiceListener != null) voiceListener.setInternalMute(false); // Guarantee unmute
        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
    }

    // --- OVERLOADS ---
    public static void speak(String text) { speakPreformatted(text, null, false, true); }
    public static void speak(String text, boolean isRare) { speakPreformatted(text, null, isRare, true); }
    public static void speak(String text, String key) { speakPreformatted(text, key, false, true); }

    public static void speakPreformatted(String text) { speakPreformatted(text, null, false, true); }
    public static void speakPreformatted(String text, String key) { speakPreformatted(text, key, false, true); }
    
    public static void speakPreformatted(String text, String key, boolean isRare) { speakPreformatted(text, key, isRare, true); }

    public static void speakAnnoyed(String text) { speakPreformatted(text, null, false, true); }

    // NEW METHOD: Adds text to the queue without interrupting current speech (used for streaming)
    public static void speakChunk(String text) { speakPreformatted(text, null, false, false); }

    public static void speakPreformatted(String text, String key, boolean isRare, boolean flushQueue) {
        if (text == null || text.isBlank()) return;

        // Only stop current playback if this is a brand new user command, not a stream chunk
        if (flushQueue) {
            stopCurrentPlayback();
        }
        
        // --- 1. EXTRACT AND STRIP EMOTION TAGS ---
        Matcher matcher = Pattern.compile("\\[([a-zA-Z]+)\\]").matcher(text);
        String emotionToTrigger = null;
        while (matcher.find()) {
            emotionToTrigger = matcher.group(1);
        }
        String cleanText = matcher.replaceAll("").trim();
        cleanText = cleanText.replaceAll("\\*.*?\\*", "").trim(); // Remove markdown actions like *sighs*

        if (emotionToTrigger != null && !emotionToTrigger.isBlank()) {
            final String finalEmotion = emotionToTrigger;
            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion(finalEmotion, 0.8, "Dialogue Tag"));
        }

        System.out.println("[Ciel Dialogue]: " + cleanText);

        if (isRare) {
            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Excited", 0.8, "RareDialogue"));
        }
        
        String langCode = CielVoiceManager.getActiveLanguageCode();
        
        // --- DYNAMIC EMOTION & ATTITUDE MIXING ---
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
        
        // --- 2. EXECUTE IN THREAD TO PREVENT DEADLOCKS ---
        currentSpeechTask = speechExecutor.submit(() -> {
            String textToSpeak = finalCleanText;

            // IMPORTANT: Since AIEngine now sends ALREADY translated Katakana, 
            // we only translate here as a final safety check if English letters still exist.
            if (CielVoiceManager.isLanguageLocked()) {
                textToSpeak = TranslationService.toJapanese(textToSpeak);
            } 
            else if (langCode.equals("ja-JP") && Pattern.compile("[a-zA-Z]").matcher(textToSpeak).find()) {
                textToSpeak = com.cielcompanion.ai.AIEngine.transliterateToKatakanaSync(textToSpeak);
            }

            if ("Glitched".equals(finalAttitude) || "Concerned".equals(finalAttitude)) {
                textToSpeak = applyStutter(textToSpeak);
            }

            long estimatedDuration = estimateSpeechDuration(textToSpeak);
            ShortTermMemoryService.getMemory().setSpeechEndTime(System.currentTimeMillis() + estimatedDuration);

            executeSpeechBlocking(textToSpeak, key, Settings.getTtsRate(), finalStyle, finalPitch, langCode);
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

        sequentialSpeechTask = speechExecutor.submit(() -> {
            try {
                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Curious", 0.7, "SequenceDialogue"));
                for (int i = 0; i < lines.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    DialogueLine line = lines.get(i);
                    if (line != null && line.text() != null && !line.text().isBlank()) {
                        
                        String textToSpeak = line.text();

                        // STRIP EMOTIONS IN SEQUENTIAL LISTS TOO
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
                        
                        // TENSURA LOGIC: If Locked, Translate to Japanese
                        if (CielVoiceManager.isLanguageLocked()) {
                            textToSpeak = TranslationService.toJapanese(textToSpeak);
                        } 
                         
                         if ("Glitched".equals(attitude) || "Concerned".equals(attitude)) {
                             textToSpeak = applyStutter(textToSpeak);
                         }

                        stopCurrentPlayback();
                        executeSpeechBlocking(textToSpeak, line.key(), Settings.getTtsRate(), style, pitch, langCode);
                        
                        if (Thread.currentThread().isInterrupted()) break;

                        if (i < lines.size() - 1) {
                            try { Thread.sleep(delayMs); } 
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                        }
                    }
                }
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            } finally {
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

        // FIXED: The try-finally block guarantees the microphone ALWAYS unmutes, even if audio is forcibly skipped or interrupted
        try {
            if (voiceListener != null) voiceListener.setInternalMute(true);
            isActivelySpeaking.set(true);
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
            
            if (!azureSuccess) {
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
                    // Handle interruption 
                } finally {
                    activeProcess.set(null);
                }
            }
        } finally {
            isActivelySpeaking.set(false);
            if (voiceListener != null) voiceListener.setInternalMute(false);
            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
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