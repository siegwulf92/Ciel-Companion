package com.cielcompanion.ai;

import com.cielcompanion.CielState;
import com.cielcompanion.service.OperatingMode;
import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;
import com.cielcompanion.service.SystemMonitor;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.service.SystemMonitor.ProcessInfo;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.dnd.CombatTrackerService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static volatile CombatTrackerService combatTrackerService;
    private static final AtomicBoolean urgentEvaluationQueued = new AtomicBoolean(false);
    private static String lastObserverSpeech = "";
    private static long lastObserverSpeechAt;

    // Fatigue threshold set to 2 hours
    private static final long FATIGUE_THRESHOLD_MS = 2 * 60 * 60 * 1000L;
    
    // Processes to ignore for System Guardian memory alerts
    private static final Set<String> IGNORED_GUARDIAN_PROCESSES = Set.of(
        "llama-server.exe", "ollama.exe", "ollama_llama_server.exe", 
        "python.exe", "lmstudio-server.exe", "java.exe", "javaw.exe"
    );

    // Quick truncator to stop massive JSON prompts from bloating the AI Context
    private static String truncateLog(String text) {
        if (text == null) return "";
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    public static void initialize() {
        if (!Settings.isAiObserverEnabled()) {
            System.out.println("Ciel Debug: AI Observer is disabled in settings.");
            return;
        }

        observerScheduler = Executors.newSingleThreadScheduledExecutor();
        observerScheduler.scheduleWithFixedDelay(ObserverService::evaluateBuffer, 45, 45, TimeUnit.SECONDS);
        System.out.println("Ciel Debug: AI Observer Service started (Evaluator Core).");
    }

    public static void setCombatTrackerService(CombatTrackerService tracker) {
        combatTrackerService = tracker;
        System.out.println("Ciel Debug: D&D table observer connected to combat tracker.");
    }

    public static synchronized void appendTranscript(String text) {
        if (!Settings.isAiObserverEnabled()) return;
        
        transcriptBuffer.add(text);
        if (transcriptBuffer.size() > MAX_BUFFER_LINES) {
            transcriptBuffer.removeFirst();
        }
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT
                && observerScheduler != null && looksLikeTableQuestion(text)
                && urgentEvaluationQueued.compareAndSet(false, true)) {
            observerScheduler.execute(() -> {
                try { evaluateBuffer(); }
                finally { urgentEvaluationQueued.set(false); }
            });
        }
    }

    private static boolean looksLikeTableQuestion(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return text.contains("?") || lower.contains("what does") || lower.contains("how does")
                || lower.contains("is it a rule") || lower.contains("ruling") || lower.contains("initiative")
                || lower.contains("whose turn") || lower.contains("bloodied") || lower.contains("hit point")
                || lower.contains("ciel");
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

    public static void setHasWarnedForFatigue(boolean value) {
        hasWarnedForFatigue = value;
    }

    private static synchronized void evaluateBuffer() {
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT && combatTrackerService != null) {
            combatTrackerService.refreshFromLiveNotes(Settings.getDndCampaignPath());
        }
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        
        // --- 1. Proactive System Guardian (RAM/CPU Overload) ---
        if ((metrics.memoryUsagePercent() > 95 || metrics.cpuLoadPercent() > 95) 
            && (System.currentTimeMillis() - lastSystemWarningTime > 5 * 60 * 1000)) { 
            
            Optional<ProcessInfo> topProc = metrics.memoryUsagePercent() > 95 ? SystemMonitor.getTopProcessByMemory() : SystemMonitor.getTopProcessByCpu();
            
            if (topProc.isPresent()) {
                String procName = topProc.get().name().toLowerCase();
                
                // CRITICAL FIX: Do NOT trigger memory alerts if the culprit is an AI model runner
                if (!IGNORED_GUARDIAN_PROCESSES.contains(procName)) {
                    lastSystemWarningTime = System.currentTimeMillis();
                    
                    // Enforce strict JSON to prevent hallucinatory parsing errors
                    String warningData = "SYSTEM ALERT: PC is under heavy load. CPU: " + metrics.cpuLoadPercent() + "%, RAM: " + metrics.memoryUsagePercent() + "%. The culprit process is " + topProc.get().name() + ".\n" +
                        "CRITICAL INSTRUCTION: You MUST respond ONLY with a valid JSON object containing a 'speech' key. The speech string MUST start with a bracketed emotion tag like [Concerned] or [Annoyed].\n" +
                        "Example: {\"speech\": \"[Concerned] Master, the system is under heavy load from an application.\"}";
                        
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
                    String warningData = "SYSTEM ALERT: The Master has been actively focused on the application '" + truncateLog(metrics.activeWindowTitle()) + "' (" + activeApp + ") for over 2 hours straight without changing windows. Proactively suggest they take a brief break, hydrate, or check their posture.\n" +
                        "CRITICAL INSTRUCTION: You MUST respond ONLY with a valid JSON object containing a 'speech' key. The speech string MUST start with a bracketed emotion tag like [Concerned].\n" +
                        "Example: {\"speech\": \"[Concerned] Master, you have been focused for two hours. Please hydrate.\"}";
                        
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
        
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(.*?)\\]").matcher(cleanText);
        
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
            long now = System.currentTimeMillis();
            if (cleanText.equalsIgnoreCase(lastObserverSpeech)
                    && now - lastObserverSpeechAt < 90_000L) {
                System.out.println("Ciel Debug: Suppressed duplicate observer speech.");
                return;
            }
            lastObserverSpeech = cleanText;
            lastObserverSpeechAt = now;
            SpeechService.speak(cleanText);
        }
    }
}
