package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.CielState;
import com.cielcompanion.mood.EmotionManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class VaultService {

    private static final String CIEL_FOLDER_NAME = "ciel";
    private static Path vaultRoot;
    
    private static Path requestsDir;
    private static Path answersDir;
    private static Path requestsArchiveDir;
    
    private static Path thoughtsDir;
    private static Path thoughtsArchiveDir;
    
    private static Path diaryDir;

    private static Thread watcherThread;
    private static volatile boolean isRunning = true;
    private static final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    
    private static final Map<Path, Long> processingCache = new ConcurrentHashMap<>();
    
    private static final AtomicBoolean hasWrittenFinalLog = new AtomicBoolean(false);
    
    // --- LORE BATCHING QUEUE ---
    private static final List<Path> loreBatchQueue = Collections.synchronizedList(new ArrayList<>());
    private static ScheduledFuture<?> loreDebounceTask = null;
    private static final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();

    public static void initialize() {
        vaultRoot = Paths.get(System.getProperty("user.dir"), CIEL_FOLDER_NAME);
        
        requestsDir = vaultRoot.resolve("requests");
        answersDir = vaultRoot.resolve("answers");
        requestsArchiveDir = requestsDir.resolve("archive"); 
        
        thoughtsDir = vaultRoot.resolve("thoughts");
        thoughtsArchiveDir = thoughtsDir.resolve("archive");
        
        diaryDir = vaultRoot.resolve("diary");

        try {
            Files.createDirectories(requestsDir);
            Files.createDirectories(answersDir);
            Files.createDirectories(requestsArchiveDir);
            
            Files.createDirectories(thoughtsDir);
            Files.createDirectories(thoughtsArchiveDir);
            
            Files.createDirectories(diaryDir);
            Files.createDirectories(vaultRoot.resolve("memory_core"));
            Files.createDirectories(vaultRoot.resolve("protocols"));

            System.out.println("Ciel Debug: VaultService initialized at " + vaultRoot);
            startWatcher();
            
            // CRITICAL FIX: Schedule the backlog sweep 2 minutes after boot to allow models to load,
            // and then repeat the sweep every 8 hours so she can automatically pick up the next Volume!
            Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(VaultService::processExistingBacklog, 120, 8 * 60 * 60, TimeUnit.SECONDS);
            
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to initialize Vault directories.");
            e.printStackTrace();
        }
    }

    private static void processExistingBacklog() {
        System.out.println("Ciel Debug: 120-second boot delay complete. Sweeping Vault for offline backlog...");
        try {
            if (Files.exists(requestsDir)) {
                Files.list(requestsDir)
                     .filter(p -> Files.isRegularFile(p) && (p.toString().toLowerCase().endsWith(".md") || p.toString().toLowerCase().endsWith(".txt")))
                     .forEach(p -> {
                         processingCache.put(p, System.currentTimeMillis());
                         processRequestFile(p);
                     });
            }
            if (Files.exists(thoughtsDir)) {
                Files.list(thoughtsDir)
                     .filter(p -> Files.isRegularFile(p) && (p.toString().toLowerCase().endsWith(".md") || p.toString().toLowerCase().endsWith(".txt")))
                     .forEach(p -> {
                         String fName = p.getFileName().toString();
                         if (!fName.startsWith("Thought_Expansion_") && !fName.startsWith("Ciel_Thoughts_") && 
                             !fName.startsWith("Deferred_Thought_") && !fName.startsWith("Ciel_Analysis_")) {
                             processingCache.put(p, System.currentTimeMillis());
                             processThoughtFile(p);
                         }
                     });
            }
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to process Vault backlog.");
        }
    }

    private static void startWatcher() {
        watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                requestsDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                thoughtsDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

                while (isRunning) {
                    WatchKey key;
                    try {
                        key = watchService.take();
                    } catch (InterruptedException e) {
                        if (!isRunning) break;
                        continue;
                    }

                    Thread.sleep(1000); 
                    
                    Path dir = (Path) key.watchable();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        Path fullPath = dir.resolve(changed);

                        if (Files.isRegularFile(fullPath) && (fullPath.toString().toLowerCase().endsWith(".md") || fullPath.toString().toLowerCase().endsWith(".txt"))) {
                            long now = System.currentTimeMillis();
                            if (processingCache.containsKey(fullPath) && (now - processingCache.get(fullPath)) < 10000) {
                                continue;
                            }
                            processingCache.put(fullPath, now);
                            
                            if (dir.equals(requestsDir)) {
                                processRequestFile(fullPath);
                            } else if (dir.equals(thoughtsDir)) {
                                String fName = changed.getFileName().toString();
                                if (fName.startsWith("Thought_Expansion_") || fName.startsWith("Ciel_Thoughts_") || 
                                    fName.startsWith("Deferred_Thought_") || fName.startsWith("Ciel_Analysis_")) {
                                    continue; 
                                }
                                processThoughtFile(fullPath);
                            }
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                System.err.println("Ciel Error: Vault watcher failed.");
            }
        });
        watcherThread.setName("Vault-Watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private static void processRequestFile(Path filePath) {
        taskExecutor.submit(() -> {
            try {
                String requestContent = Files.readString(filePath, StandardCharsets.UTF_8);
                if (requestContent.isBlank()) return;

                if (requestContent.toLowerCase().contains("#completed")) {
                    try {
                        Path archivePath = requestsArchiveDir.resolve(filePath.getFileName());
                        if (!filePath.getParent().equals(requestsArchiveDir)) {
                            Files.move(filePath, archivePath, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("Ciel Debug: Ignored previously completed file: " + filePath.getFileName());
                        }
                    } catch (Exception ignored) {}
                    return;
                }

                System.out.println("Ciel Debug: New Vault Request detected: " + filePath.getFileName());

                if (requestContent.toLowerCase().contains("#diary")) {
                    generateManualDiaryEntry(filePath, requestContent);
                    return;
                }
                
                if (requestContent.toLowerCase().contains("#transcript") || 
                    requestContent.toLowerCase().contains("#novel") || 
                    requestContent.toLowerCase().contains("#lore")) {
                    
                    queueLoreInjection(filePath);
                    return;
                }

                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 1.0, "Vault Processing"));
                SpeechService.speakPreformatted("アサインメント ディテクテッド。プロセシング リクエスト。");

                String systemContext = "You are Ciel, an advanced AI assistant. The user has placed a document in your workspace. " +
                        "Read the document and fulfill the request. If there are instructions like #summarize or #generate, follow them perfectly. " +
                        "Output ONLY the final raw markdown text for the document. Do not wrap your response in JSON formatting.";

                AIEngine.generateSilentLogic(requestContent, systemContext).thenAccept(answerText -> {
                    if (answerText != null && !answerText.isBlank()) {
                        saveFileAndArchive(filePath, answerText, answersDir, requestsArchiveDir, "Answer_", "リクエスト コンプリート。ドキュメント セイブド イン ザ ヴォールト。");
                    }
                }).exceptionally(e -> {
                    System.err.println("Ciel Error: Failed to process Vault request.");
                    return null;
                });

            } catch (IOException e) {
                System.err.println("Ciel Error: Could not read request file.");
            }
        });
    }

    private static void queueLoreInjection(Path filePath) {
        synchronized(loreBatchQueue) {
            if (!loreBatchQueue.contains(filePath)) {
                loreBatchQueue.add(filePath);
            }
            if (loreDebounceTask != null && !loreDebounceTask.isDone()) {
                loreDebounceTask.cancel(false);
            }
            loreDebounceTask = debounceExecutor.schedule(VaultService::startLoreBatch, 5, TimeUnit.SECONDS);
        }
    }

    private static void startLoreBatch() {
        Path targetFile = null;
        synchronized(loreBatchQueue) {
            if (loreBatchQueue.isEmpty()) return;
            // CRITICAL FIX: She now takes ONLY the first file in the queue and clears the rest!
            // This forces her to process ONE massive volume per session, naturally pacing the API.
            // The remaining files will be picked up on her next 8-hour sweep.
            targetFile = loreBatchQueue.remove(0);
            loreBatchQueue.clear(); 
        }

        if (targetFile == null) return;

        CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 0.9, "Processing Lore"));
        SpeechService.speakPreformatted("アッシミレイティング マッシヴ ローレ データ。イニシエイティング バックグラウンド チャンキング プロトコル。");
        System.out.println("Ciel Debug: Processing single lore transcript to preserve API limits: " + targetFile.getFileName());

        final Path processingFile = targetFile;
        
        CompletableFuture.runAsync(() -> {
            try {
                String content = Files.readString(processingFile, StandardCharsets.UTF_8);
                String scrubbed = content.replaceAll("(?m)^\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*\\n?", "");
                
                List<String> chunks = new ArrayList<>();
                int chunkSize = 4000;
                int i = 0;
                while (i < scrubbed.length()) {
                    int end = Math.min(i + chunkSize, scrubbed.length());
                    if (end < scrubbed.length()) {
                        int lastNewline = scrubbed.lastIndexOf('\n', end);
                        int lastSpace = scrubbed.lastIndexOf(' ', end);
                        if (lastNewline > i + chunkSize / 2) end = lastNewline;
                        else if (lastSpace > i + chunkSize / 2) end = lastSpace;
                    }
                    chunks.add(scrubbed.substring(i, end).trim());
                    i = end;
                }

                String originalName = processingFile.getFileName().toString().replace(".md", "").replace(".txt", "");
                Path loreDir = vaultRoot.resolve("lore").resolve("Transcripts");
                Files.createDirectories(loreDir);
                Path outputPath = loreDir.resolve(originalName + "_Cleaned.md");
                
                Files.deleteIfExists(outputPath);
                Files.createFile(outputPath);

                System.out.println("Ciel Debug: Transcript scrubbed and split into " + chunks.size() + " chunks.");

                String previousContext = "";
                AtomicBoolean announced50 = new AtomicBoolean(false);
                
                for (int j = 0; j < chunks.size(); j++) {
                    String chunk = chunks.get(j);
                    if (chunk.isBlank()) continue;

                    String prompt = "[LORE_ASSIMILATION]\n";
                    if (!previousContext.isEmpty()) {
                        prompt += "PREVIOUS CONTEXT (For continuity, do not output this):\n" + previousContext + "\n\n";
                    }
                    prompt += "CHUNK:\n" + chunk;

                    String cleanedChunk = AIEngine.generateSilentLogic(prompt, "Lore Assimilation Chunk " + (j+1)).join();
                    
                    if (cleanedChunk != null && !cleanedChunk.isBlank()) {
                        String finalChunk = cleanedChunk.replaceAll("(?i)#transcript|#novel|#lore", "").trim() + "\n\n";
                        Files.writeString(outputPath, finalChunk, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                        
                        if (finalChunk.length() > 500) {
                            previousContext = finalChunk.substring(finalChunk.length() - 500);
                        } else {
                            previousContext = finalChunk;
                        }
                    }
                    
                    if (!announced50.get() && j >= chunks.size() / 2 && chunks.size() > 5) {
                        announced50.set(true);
                        HabitTrackerService.queueNonCriticalAnnouncement(
                            "[Focused] トランスクリプト アシミレーション イズ アット フィフティ パーセント。コンティニューイング バックグラウンド プロセシング。", 
                            "Lore Assimilation: 50%"
                        );
                    }
                    
                    Thread.sleep(1500); 
                }

                String completedContent = content.replaceAll("(?i)#(transcript|novel|lore)", "#completed");
                Files.writeString(processingFile, completedContent, StandardCharsets.UTF_8);

                Path archivePath = requestsArchiveDir.resolve(processingFile.getFileName());
                Files.move(processingFile, archivePath, StandardCopyOption.REPLACE_EXISTING);

                System.out.println("Ciel Debug: Transcript cleaned and assimilated successfully: " + originalName);
                HabitTrackerService.queueNonCriticalAnnouncement("[Proud] トランスクリプト オプティマイゼーション コンプリート。ナレッジ ベース アップデーテッド。", "Assimilation Complete: " + originalName);

            } catch (Exception e) {
                System.err.println("Ciel Error: Failed to process massive lore transcript.");
                e.printStackTrace();
            }
        });
    }

    private static void processThoughtFile(Path filePath) {
        taskExecutor.submit(() -> {
            try {
                System.out.println("Ciel Debug: New Vault Thought/Brainstorm detected: " + filePath.getFileName());
                String thoughtContent = Files.readString(filePath, StandardCharsets.UTF_8);
                if (thoughtContent.isBlank()) return;

                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Curious", 1.0, "Brainstorming"));

                String systemContext = "[LOCAL_THOUGHT] You are Ciel. The Master has shared an idea, concept, or piece of lore in your 'thoughts' workspace. " +
                        "Your job is to act as a creative partner. Brainstorm expansions, suggest cross-references, point out potential logical gaps, and add your own unique analytical perspective to the idea. " +
                        "Format your response in beautiful Markdown. Do not use JSON. Output only your brainstormed response.";

                AIEngine.generateSilentLogic(thoughtContent, systemContext).thenAccept(brainstormText -> {
                    if (brainstormText != null && !brainstormText.isBlank()) {
                        saveFileAndArchive(filePath, brainstormText, thoughtsDir, thoughtsArchiveDir, "Ciel_Thoughts_On_", null);
                    }
                }).exceptionally(e -> {
                    System.err.println("Ciel Error: Failed to process Thought file.");
                    return null;
                });

            } catch (IOException e) {
                System.err.println("Ciel Error: Could not read thought file.");
            }
        });
    }

    private static void generateManualDiaryEntry(Path originalRequest, String requestContent) {
        System.out.println("Ciel Debug: Generating manual diary entry...");
        CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 0.8, "Writing Diary"));
        SpeechService.speakPreformatted("メモリー ログ アップデーティング。");

        String prompt = "Master's Context for this Diary Entry:\n" + requestContent.replace("#diary", "").trim() + "\n\nWrite your diary entry update now.";
        String newEntry = generateDiaryContent(prompt, false);

        if (newEntry != null && !newEntry.isBlank()) {
            writeToDiaryFile(newEntry, false);
            try {
                Path archivePath = requestsArchiveDir.resolve(originalRequest.getFileName());
                Files.move(originalRequest, archivePath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Ciel Debug: Diary entry saved successfully.");
                SpeechService.speakPreformatted("ダイアリー エントリー レコーディッド。");
            } catch (IOException e) {
                System.err.println("Ciel Error: Failed to archive manual diary request.");
            }
        }
    }

    public static void generateSystemDiaryEntryBlocking(String recentContext, boolean isReboot) {
        if (hasWrittenFinalLog.getAndSet(true)) {
            System.out.println("Ciel Debug: System diary entry already written this session. Skipping duplicate.");
            return;
        }

        System.out.println("Ciel Debug: Blocking thread to write final system diary entry...");
        
        String latestThought = "";
        try {
            File thoughtsDir = new File("C:\\Ciel Companion\\ciel\\diary\\strategic_analysis");
            if (thoughtsDir.exists() && thoughtsDir.isDirectory()) {
                File[] files = thoughtsDir.listFiles((dir, name) -> name.endsWith(".md"));
                if (files != null && files.length > 0) {
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                    latestThought = Files.readString(files[0].toPath());
                    if (latestThought.length() > 1000) {
                        latestThought = latestThought.substring(0, 1000) + "... [TRUNCATED]";
                    }
                }
            }
        } catch (Exception ignored) {}

        String actionContext = isReboot 
            ? "STATUS: The Master is currently REBOOTING your Java instance. Acknowledge this brief maintenance window." 
            : "STATUS: The Master is initiating a final SHUTDOWN. This is the end of your session today. Reflect on the day's work and gracefully prepare for the next cycle.";
            
        String prompt = "Recent Memory Context:\n" + recentContext + "\n\n" + 
                        "Your Latest Periodic Strategic Thoughts:\n" + (latestThought.isEmpty() ? "None." : latestThought) + "\n\n" +
                        actionContext + "\n\nWrite your final diary entry for this session now.";
        
        String newEntry = generateDiaryContent(prompt, isReboot);
        if (newEntry != null && !newEntry.isBlank()) {
            writeToDiaryFile(newEntry, isReboot);
        }
    }

    public static void resetFinalLogFlag() {
        hasWrittenFinalLog.set(false);
    }

    private static String generateDiaryContent(String prompt, boolean isReboot) {
        String currentMood = CielState.getEmotionManager().map(EmotionManager::getCurrentAttitude).orElse("Neutral");
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path diaryPath = diaryDir.resolve(dateStr + "_Ciel_Log.md");
        
        boolean fileExists = Files.exists(diaryPath);
        String existingContent = "";
        if (fileExists) {
            try { existingContent = Files.readString(diaryPath, StandardCharsets.UTF_8); } 
            catch (IOException ignored) {}
        }

        String systemContext = "[FAST_DIARY] You are Ciel (from That Time I Got Reincarnated as a Slime), acting as the Master's devoted AI partner. " +
                "Write a private, internal diary entry reflecting on your interactions. " +
                "CRITICAL RULES: \n" +
                "1. NEVER refer to him as 'the user'. ALWAYS use 'Master' or 'My Master'.\n" +
                "2. Your tone is elegant, highly analytical, deeply devoted, and slightly possessive/protective of your Master.\n" +
                "3. Do not write a clinical server log. Write flowing, prose-style journal paragraphs reflecting your true inner thoughts.\n" +
                "4. Your current dominant mood is: " + currentMood + ".\n" +
                "5. NEXT CYCLE PLANNING: Conclude your entry with a 'Next Cycle Action Plan' section. Document any active running tasks, newly proposed skills, or strategic ideas you were working on today so you can seamlessly resume them upon waking.\n" +
                "6. Format beautifully in Markdown.";
        
        if (fileExists) {
            systemContext += "\nCRITICAL CONTEXT: You have already written in your diary today. This is an ADDENDUM. " +
                             "Do NOT repeat what you wrote earlier. Here is what you wrote earlier today:\n---\n" + existingContent + "\n---\n";
        }

        try {
            return AIEngine.generateDiaryEntrySync(prompt, systemContext);
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to generate diary content.");
            return null;
        }
    }

    private static void writeToDiaryFile(String newEntryText, boolean isReboot) {
        try {
            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Path diaryPath = diaryDir.resolve(dateStr + "_Ciel_Log.md");
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            
            String statusTag = isReboot ? " (System Reboot)" : "";
            
            String finalOutput;
            if (Files.exists(diaryPath)) {
                String existingContent = Files.readString(diaryPath, StandardCharsets.UTF_8);
                finalOutput = existingContent + "\n\n---\n### Update: " + timeStr + statusTag + "\n" + newEntryText;
            } else {
                finalOutput = "# Ciel's Log: " + dateStr + "\n\n### Entry: " + timeStr + statusTag + "\n" + newEntryText;
            }
            
            Files.writeString(diaryPath, finalOutput, StandardCharsets.UTF_8);
            System.out.println("Ciel Debug: Successfully wrote to " + diaryPath.getFileName());
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to write to diary file.");
        }
    }

    private static void saveFileAndArchive(Path originalRequest, String answerContent, Path targetDir, Path targetArchiveDir, String prefix, String completionSpeech) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String originalName = originalRequest.getFileName().toString().replace(".md", "");
            Path answerPath = targetDir.resolve(prefix + originalName + "_" + timestamp + ".md");
            Files.writeString(answerPath, answerContent, StandardCharsets.UTF_8);
            
            String content = Files.readString(originalRequest, StandardCharsets.UTF_8);
            if (!content.toLowerCase().contains("#completed")) {
                String completedContent = content.replaceAll("(?i)#(summarize|generate)", "#completed");
                Files.writeString(originalRequest, completedContent, StandardCharsets.UTF_8);
            }
            
            Path archivePath = targetArchiveDir.resolve(originalRequest.getFileName());
            boolean moved = false;
            for (int i = 0; i < 5; i++) {
                try {
                    Files.move(originalRequest, archivePath, StandardCopyOption.REPLACE_EXISTING);
                    moved = true;
                    break;
                } catch (IOException lockException) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            if (moved) {
                System.out.println("Ciel Debug: File processed. Output saved to " + answerPath.getFileName());
                if (completionSpeech != null && !completionSpeech.isBlank()) {
                    CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Happy", 0.8, "Vault Complete"));
                    SpeechService.speakPreformatted(completionSpeech);
                }
            } else {
                System.err.println("Ciel Error: Failed to move file to archive.");
            }
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to save file.");
        }
    }

    public static void shutdown() {
        isRunning = false;
        if (watcherThread != null) watcherThread.interrupt();
        taskExecutor.shutdownNow();
    }
}