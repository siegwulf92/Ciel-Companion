package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.ui.CielGui;
import com.cielcompanion.util.PhonoKana;

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
import java.util.stream.Collectors;

/**
 * Handles Text-to-Speech operations using Azure Cognitive Services.
 * INTEGRATED: Combines original logic with World Voice (Tensura) support,
 * dynamic emotion variance, and stuttering logic.
 */
public class SpeechService {

    private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private static volatile Future<?> sequentialSpeechTask = null;
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

    // --- OVERLOADS ---
    public static void speak(String text) { speakPreformatted(text, null, false); }
    public static void speak(String text, boolean isRare) { speakPreformatted(text, null, isRare); }
    public static void speak(String text, String key) { speakPreformatted(text, key, false); }

    public static void speakPreformatted(String text) { speakPreformatted(text, null, false); }
    public static void speakPreformatted(String text, String key) { speakPreformatted(text, key, false); }

    public static void speakAnnoyed(String text) { speakPreformatted(text, null, false); }

    public static void speakPreformatted(String text, String key, boolean isRare) {
        if (text == null || text.isBlank()) return;
        
        System.out.println("[Ciel Dialogue]: " + text);

        if (isRare) {
            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Excited", 0.8, "RareDialogue"));
        }
        
        String finalOutput = text;
        String langCode = CielVoiceManager.getActiveLanguageCode();

        // TENSURA LOGIC: If Locked, Translate to Japanese
        if (CielVoiceManager.isLanguageLocked()) {
            finalOutput = TranslationService.toJapanese(text);
            System.out.println("[Ciel World Voice]: Translated to: " + finalOutput);
        } 
        // D&D LOGIC: If English Mode, use raw text (no Katakana conversion needed for Jenny)
        else if (langCode.equals("en-US")) {
            finalOutput = text; 
        }
        // DEFAULT LOGIC: If Japanese Mode but text is English, convert to Katakana
        else if (langCode.equals("ja-JP") && text.matches(".*[a-zA-Z]+.*")) {
             finalOutput = PhonoKana.getInstance().toKatakana(text);
        }

        long estimatedDuration = estimateSpeechDuration(finalOutput);
        ShortTermMemoryService.getMemory().setSpeechEndTime(System.currentTimeMillis() + estimatedDuration);
        
        // --- DYNAMIC EMOTION & ATTITUDE MIXING ---
        String style = "default";
        String pitch = "+0%";
        String attitude = "Professional";

        if (CielState.getEmotionManager().isPresent()) {
            // 1. Check for Attitude Override (from JSON quirks or events)
            attitude = CielState.getEmotionManager().get().getCurrentAttitude();
            if (!"Professional".equals(attitude)) {
                Optional<MoodConfig.AttitudeDefinition> attDef = MoodConfig.getAttitudeDef(attitude);
                if (attDef.isPresent()) {
                    style = attDef.get().styleModifier();
                    pitch = attDef.get().pitchModifier();
                }
            } 
            // 2. If no attitude override, check Dominant Emotion
            else {
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
            
            // 3. Apply Human Variance (Small random fluctuations)
            pitch = applyHumanVariance(pitch);
            
            // 4. Apply Stutter if Glitched/Concerned (Narrative effect)
            if ("Glitched".equals(attitude) || "Concerned".equals(attitude)) {
                finalOutput = applyStutter(finalOutput);
            }
        }
        
        executeSpeech(finalOutput, key, Settings.getTtsRate(), style, pitch, langCode);
    }
    
    // Adds small random variances to pitch strings (e.g. "+5%" becomes "+4%" or "+6%")
    private static String applyHumanVariance(String basePitch) {
        if (basePitch.equals("default")) return "+0%";
        try {
            // Remove % and +, parse, jitter by -2 to +2, reconstruct
            String clean = basePitch.replace("%", "").replace("+", "");
            if (clean.isEmpty()) return "+0%";
            
            int val = Integer.parseInt(clean);
            int variance = random.nextInt(5) - 2; // -2 to +2 variation
            int newVal = val + variance;
            return (newVal >= 0 ? "+" : "") + newVal + "%";
        } catch (Exception e) {
            return basePitch;
        }
    }
    
    // Randomly repeats the first syllable of words to simulate processing error/stress
    private static String applyStutter(String input) {
        if (random.nextInt(10) > 3) return input; // Only stutter 30% of the time in this mode
        
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
                        String textToSpeak = line.text();
                        
                         if (CielVoiceManager.isLanguageLocked()) {
                             textToSpeak = TranslationService.toJapanese(textToSpeak);
                         } else if (langCode.equals("ja-JP") && textToSpeak.matches(".*[a-zA-Z]+.*")) {
                             textToSpeak = PhonoKana.getInstance().toKatakana(textToSpeak);
                         }
                         
                         // Apply stutter to sequential lines too if glitched
                         if ("Glitched".equals(attitude) || "Concerned".equals(attitude)) {
                             textToSpeak = applyStutter(textToSpeak);
                         }

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
        speechExecutor.submit(() -> executeSpeechBlocking(text, key, rate, style, pitch, langCode));
    }

    private static void executeSpeechBlocking(String text, String key, int rate, String style, String pitch, String langCode) {
        if (Thread.currentThread().isInterrupted()) return;

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
            try { pb.start().waitFor(15, TimeUnit.SECONDS); } catch (Exception e) {}
        }

        isActivelySpeaking.set(false);
        if (voiceListener != null) voiceListener.setInternalMute(false);
        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
    }

    public static long estimateSpeechDuration(String text) {
        if (text == null || text.isBlank()) return 0;
        return (long) (text.length() * 115) + 400;
    }

    public static void cleanup() {
        speechExecutor.shutdown();
    }
}