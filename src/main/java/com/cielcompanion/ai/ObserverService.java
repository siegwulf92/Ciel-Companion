package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;
import com.cielcompanion.service.SystemMonitor;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.service.SystemMonitor.ProcessInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ObserverService {

    private static final int MAX_BUFFER_LINES = 15;
    private static final LinkedList<String> transcriptBuffer = new LinkedList<>();
    private static ScheduledExecutorService observerScheduler;
    
    private static long lastSystemWarningTime = 0; 
    private static String currentForegroundApp = "";
    private static long appFocusStartTime = System.currentTimeMillis();
    private static boolean hasWarnedForFatigue = false;

    // Fatigue threshold set to 2 hours
    private static final long FATIGUE_THRESHOLD_MS = 2 * 60 * 60 * 1000L;

    public static void initialize() {
        if (!Settings.isAiObserverEnabled()) {
            System.out.println("Ciel Debug: AI Observer is disabled in settings.");
            return;
        }

        observerScheduler = Executors.newSingleThreadScheduledExecutor();
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

    public static void logToPermanentTranscript(String text) {
        if (CielState.getCurrentMode() != OperatingMode.DND_ASSISTANT) return;

        String campaignPathStr = Settings.getDndCampaignPath();
        if (campaignPathStr == null || campaignPathStr.isBlank()) return;

        try {
            Path transcriptsDir = Paths.get(campaignPathStr, "Transcripts");
            Files.createDirectories(transcriptsDir);
            
            String dateStr = LocalDate.now().toString();
            Path file = transcriptsDir.resolve("Session_" + dateStr + ".txt");
            
            String logEntry = text + System.lineSeparator();
            Files.writeString(file, logEntry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Ciel Warning: Failed to write to session transcript.");
        }
    }

    private static synchronized void evaluateBuffer() {
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        
        // --- 1. Proactive System Guardian (RAM/CPU Overload) ---
        if ((metrics.memoryUsagePercent() > 95 || metrics.cpuLoadPercent() > 95) 
            && (System.currentTimeMillis() - lastSystemWarningTime > 5 * 60 * 1000)) { 
            
            lastSystemWarningTime = System.currentTimeMillis();
            Optional<ProcessInfo> topProc = metrics.memoryUsagePercent() > 95 ? SystemMonitor.getTopProcessByMemory() : SystemMonitor.getTopProcessByCpu();
            
            if (topProc.isPresent()) {
                String warningData = "SYSTEM ALERT: PC is under heavy load. CPU: " + metrics.cpuLoadPercent() + "%, RAM: " + metrics.memoryUsagePercent() + "%. The culprit process is " + topProc.get().name() + ".";
                String context = ContextBuilder.buildObserverContext();
                
                AIEngine.evaluateBackground(warningData, context).thenAccept(result -> {
                    if (result != null && result.has("speech")) {
                        System.out.println("Ciel Debug: Proactive System Guardian triggered!");
                        extractAndSpeak(result.get("speech").getAsString());
                    }
                });
                return; 
            }
        }

        // --- 2. Universal Perception (User Fatigue Monitor) ---
        String activeApp = metrics.activeProcessName().toLowerCase();
        if (!activeApp.equals("explorer.exe") && !activeApp.isBlank()) {
            if (!activeApp.equals(currentForegroundApp)) {
                currentForegroundApp = activeApp;
                appFocusStartTime = System.currentTimeMillis();
                hasWarnedForFatigue = false;
            } else {
                long elapsedMs = System.currentTimeMillis() - appFocusStartTime;
                if (elapsedMs > FATIGUE_THRESHOLD_MS && !hasWarnedForFatigue) { 
                    hasWarnedForFatigue = true;
                    String warningData = "SYSTEM ALERT: The Master has been actively focused on the application '" + metrics.activeWindowTitle() + "' (" + activeApp + ") for over 2 hours straight without changing windows. Proactively suggest they take a brief break, hydrate, or check their posture.";
                    String context = ContextBuilder.buildObserverContext();

                    AIEngine.evaluateBackground(warningData, context).thenAccept(result -> {
                        if (result != null && result.has("speech")) {
                            System.out.println("Ciel Debug: Universal Perception (Fatigue) triggered!");
                            extractAndSpeak(result.get("speech").getAsString());
                        }
                    });
                    return; 
                }
            }
        }

        // --- 3. Standard Observer Logic (Transcript analysis) ---
        if (transcriptBuffer.isEmpty()) return;

        String combinedTranscript = transcriptBuffer.stream().collect(Collectors.joining("\n"));
        transcriptBuffer.clear();

        String context = ContextBuilder.buildObserverContext();

        AIEngine.evaluateBackground(combinedTranscript, context).thenAccept(result -> {
            if (result != null && result.has("interject") && result.get("interject").getAsBoolean()) {
                
                System.out.println("Ciel Debug: Observer detected an interjection reason: " + result.get("reason").getAsString());
                
                if (result.has("speech")) {
                    String speech = result.get("speech").getAsString();
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
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[([a-zA-Z]+)\\]").matcher(cleanText);
        
        String emotion = null;
        while (matcher.find()) {
            emotion = matcher.group(1);
        }
        
        cleanText = matcher.replaceAll("").trim();
        cleanText = cleanText.replaceAll("\\*.*?\\*", "").trim();
        
        if (emotion != null && !emotion.isBlank()) {
            final String finalEmotion = emotion;
            com.cielcompanion.CielState.getEmotionManager().ifPresent(em -> 
                em.triggerEmotion(finalEmotion, 0.9, "Observer Interjection")
            );
        }
        
        if (!cleanText.isEmpty()) {
            SpeechService.speakPreformatted(cleanText);
        }
    }
}