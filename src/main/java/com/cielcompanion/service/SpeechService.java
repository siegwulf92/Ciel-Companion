package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.ui.CielGui;
import com.cielcompanion.util.PhonoKana; // Ensure this import is present

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Handles Text-to-Speech operations using Azure Cognitive Services.
 * REWORKED: Automatically converts English text to Katakana via PhonoKana 
 * to maintain the Japanese accent and logs final output for troubleshooting.
 */
public class SpeechService {

    private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private static volatile Future<?> sequentialSpeechTask = null;
    private static final AtomicBoolean isActivelySpeaking = new AtomicBoolean(false);
    
    private static final AtomicReference<Process> activeProcess = new AtomicReference<>();
    
    private static VoiceListener voiceListener;

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
        
        if (isRare) {
            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Excited", 0.8, "RareDialogue"));
        }
        
        long estimatedDuration = estimateSpeechDuration(text);
        ShortTermMemoryService.getMemory().setSpeechEndTime(System.currentTimeMillis() + estimatedDuration);
        
        // --- SMART EMOTION MIXING ---
        String style = "default";
        String pitch = "+0%";

        if (CielState.getEmotionManager().isPresent()) {
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

            if ("default".equals(style)) {
                for (Emotion e : activeEmotions) {
                    Optional<MoodConfig.EmotionDefinition> def = MoodConfig.getEmotionDef(e.name());
                    if (def.isPresent()) {
                        String s = def.get().ssmlStyle();
                        if (!"default".equals(s)) {
                            style = s;
                            break; 
                        }
                    }
                }
            }
        }
        
        executeSpeech(text, key, Settings.getTtsRate(), style, pitch);
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
                        if (CielState.getEmotionManager().isPresent()) {
                             List<Emotion> active = CielState.getEmotionManager().get().getEmotionalState().getActiveEmotions().values().stream()
                                 .sorted(Comparator.comparingDouble(Emotion::intensity).reversed()).collect(Collectors.toList());
                             if(!active.isEmpty()) {
                                 Optional<MoodConfig.EmotionDefinition> def = MoodConfig.getEmotionDef(active.get(0).name());
                                 if(def.isPresent()) { pitch = def.get().pitch(); style = def.get().ssmlStyle(); }
                             }
                        }

                        executeSpeechBlocking(line.text(), line.key(), Settings.getTtsRate(), style, pitch);
                        
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

    private static void executeSpeech(String text, String key, int rate, String style, String pitch) {
        speechExecutor.submit(() -> executeSpeechBlocking(text, key, rate, style, pitch));
    }

    private static void executeSpeechBlocking(String text, String key, int rate, String style, String pitch) {
        if (Thread.currentThread().isInterrupted()) return;

        // 1. Apply manual phonetic fixes first
        String filtered = applyPhoneticFixes(text);

        // 2. Convert to Katakana via PhonoKana to ensure the Japanese voice has an accent
        // This catches English words like "Cleveland" or "Ohio" even if the caller forgot to convert them.
        String finalKatakana = PhonoKana.getInstance().toKatakana(filtered);

        // 3. Log the dialogue for troubleshooting
        if (!text.equals(finalKatakana)) {
             // System.out.println("[Ciel Raw Text]: " + text); // Uncomment if you want to see the original English
        }
        System.out.println("[Ciel Dialogue]: " + finalKatakana);

        if (voiceListener != null) voiceListener.setInternalMute(true);
        isActivelySpeaking.set(true);
        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));

        boolean azureSuccess = false;

        // --- AZURE ATTEMPT (Using converted Katakana) ---
        if (AzureSpeechService.isAvailable()) {
            azureSuccess = AzureSpeechService.speak(finalKatakana, key, style, pitch);
            if (azureSuccess) {
                System.out.println("Ciel Debug: Azure Speech successful (Key: " + (key != null ? key : "Dynamic") + ", Style: " + style + ")");
            } else {
                System.out.println("Ciel Warning: Azure Speech failed. Falling back to SAPI.");
            }
        }
        
        // --- FALLBACK ---
        if (!azureSuccess) {
            String targetVoice = Settings.getVoiceNameHint();
            System.out.println("Ciel Debug: SAPI Speaking: \"" + finalKatakana + "\" (Target: " + targetVoice + ")");
            
            String safeText = finalKatakana.replace("'", "''");
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

    /**
     * Pre-processing for specific English words before Katakana conversion.
     */
    private static String applyPhoneticFixes(String text) {
        if (text == null) return null;
        
        // You can add emergency fixes here if the properties file isn't working
        return text; 
    }

    public static long estimateSpeechDuration(String text) {
        if (text == null || text.isBlank()) return 0;
        return (long) (text.length() * 115) + 400;
    }

    public static void cleanup() {
        speechExecutor.shutdown();
    }
}