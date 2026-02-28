package com.cielcompanion.ai;

import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;

import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ObserverService {

    private static final int MAX_BUFFER_LINES = 15;
    private static final LinkedList<String> transcriptBuffer = new LinkedList<>();
    private static ScheduledExecutorService observerScheduler;

    public static void initialize() {
        if (!Settings.isAiObserverEnabled()) {
            System.out.println("Ciel Debug: AI Observer is disabled in settings.");
            return;
        }

        observerScheduler = Executors.newSingleThreadScheduledExecutor();
        // Check the buffer every 45 seconds to simulate her "thinking" before interrupting
        observerScheduler.scheduleWithFixedDelay(ObserverService::evaluateBuffer, 45, 45, TimeUnit.SECONDS);
        System.out.println("Ciel Debug: AI Observer Service started (Evaluator Core).");
    }

    public static synchronized void appendTranscript(String text) {
        if (!Settings.isAiObserverEnabled()) return;
        
        transcriptBuffer.add(text);
        if (transcriptBuffer.size() > MAX_BUFFER_LINES) {
            transcriptBuffer.removeFirst();
        }
    }

    private static synchronized void evaluateBuffer() {
        if (transcriptBuffer.isEmpty()) return;

        String combinedTranscript = transcriptBuffer.stream().collect(Collectors.joining("\n"));
        String context = ContextBuilder.buildObserverContext();

        AIEngine.evaluateBackground(combinedTranscript, context).thenAccept(result -> {
            if (result != null && result.has("interject") && result.get("interject").getAsBoolean()) {
                
                System.out.println("Ciel Debug: Observer detected an interjection reason: " + result.get("reason").getAsString());
                
                if (result.has("speech")) {
                    String speech = result.get("speech").getAsString();
                    transcriptBuffer.clear(); // Clear so she doesn't repeat it
                    extractAndSpeak(speech);
                }
            }
        }).exceptionally(e -> {
            System.err.println("Ciel Warning: Observer evaluation failed.");
            return null;
        });
    }

    private static void extractAndSpeak(String text) {
        String cleanText = text.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^\\[(.*?)\\]\\s*").matcher(cleanText);
        
        if (matcher.find()) {
            String emotion = matcher.group(1);
            cleanText = matcher.replaceFirst("").trim();
            com.cielcompanion.CielState.getEmotionManager().ifPresent(em -> 
                em.triggerEmotion(emotion, 0.9, "Observer Interjection")
            );
        }
        
        if (!cleanText.isEmpty()) {
            SpeechService.speakPreformatted(cleanText);
        }
    }
}