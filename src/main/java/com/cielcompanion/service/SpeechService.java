package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.ui.CielGui;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SpeechService {

    private static final ExecutorService speechExecutor = Executors.newSingleThreadExecutor();
    private static volatile Future<?> sequentialSpeechTask = null;
    private static final AtomicBoolean isActivelySpeaking = new AtomicBoolean(false);
    
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
    
    // UPDATED: Accepts List<DialogueLine> to preserve keys
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

        if (voiceListener != null) voiceListener.setInternalMute(true);
        isActivelySpeaking.set(true);
        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.SPEAKING));

        boolean azureSuccess = false;

        if (AzureSpeechService.isAvailable()) {
            azureSuccess = AzureSpeechService.speak(text, key, style, pitch);
            if (azureSuccess) {
                System.out.println("Ciel Debug: Azure Speech successful (Key: " + (key != null ? key : "Dynamic") + ", Style: " + style + ")");
            } else {
                System.out.println("Ciel Warning: Azure Speech failed or skipped. Falling back to SAPI.");
            }
        }
        
        if (!azureSuccess) {
            // SAPI Fallback logic (abbreviated)
            String targetVoice = Settings.getVoiceNameHint();
            System.out.println("Ciel Debug: SAPI Speaking: \"" + text + "\"");
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