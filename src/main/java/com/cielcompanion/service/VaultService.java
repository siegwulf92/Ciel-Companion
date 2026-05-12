package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.CielState;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
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
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class VaultService {

    private static final String CIEL_FOLDER_NAME = "ciel";
    private static Path vaultRoot;
    
    private static Path requestsDir;
    private static Path answersDir;
    private static Path requestsArchiveDir;
    
    private static Path thoughtsDir;
    private static Path thoughtsArchiveDir;
    private static Path insightsDir; 
    
    private static Path diaryDir;

    private static Thread watcherThread;
    private static volatile boolean isRunning = true;
    
    private static final ExecutorService taskExecutor = Executors.newFixedThreadPool(4);
    
    private static final Map<Path, Long> processingCache = new ConcurrentHashMap<>();
    
    private static final AtomicBoolean hasWrittenFinalLog = new AtomicBoolean(false);
    
    private static final long LORE_COOLDOWN_MS = 30 * 60 * 1000L; 
    
    private static final List<Path> loreBatchQueue = Collections.synchronizedList(new ArrayList<>());
    private static ScheduledFuture<?> loreDebounceTask = null;
    private static final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();
    
    private static final AtomicBoolean isLoreThreadActive = new AtomicBoolean(false);

    private static final Random random = new Random();
    private static String LORE_DIR;

    // Custom Sorter to prevent "10" from coming before "2"
    private static int extractSortNumber(Path p) {
        Matcher m = Pattern.compile("\\d+").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    public static void initialize() {
        vaultRoot = Paths.get(System.getProperty("user.dir"), CIEL_FOLDER_NAME);
        LORE_DIR = vaultRoot.resolve("lore").toString();
        
        requestsDir = vaultRoot.resolve("requests");
        answersDir = vaultRoot.resolve("answers");
        requestsArchiveDir = requestsDir.resolve("archive"); 
        
        thoughtsDir = vaultRoot.resolve("thoughts");
        thoughtsArchiveDir = thoughtsDir.resolve("archive");
        insightsDir = vaultRoot.resolve("insights"); 
        
        diaryDir = vaultRoot.resolve("diary");

        try {
            Files.createDirectories(requestsDir);
            Files.createDirectories(answersDir);
            Files.createDirectories(requestsArchiveDir);
            
            Files.createDirectories(thoughtsDir);
            Files.createDirectories(thoughtsArchiveDir);
            Files.createDirectories(insightsDir);
            
            Files.createDirectories(diaryDir);
            Files.createDirectories(vaultRoot.resolve("memory_core"));
            Files.createDirectories(vaultRoot.resolve("protocols"));
            
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Factions"));
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Locations"));
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Species"));
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Events"));
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Indexes"));

            System.out.println("Ciel Debug: VaultService initialized at " + vaultRoot);
            
            MemoryService.addFact(new Fact("system_last_lore_assimilation_time", "0", System.currentTimeMillis(), "system_state", "system", 1));

            startWatcher();
            
            Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(VaultService::processExistingBacklog, 120, 8 * 60 * 60, TimeUnit.SECONDS);
            
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to initialize Vault directories.");
            e.printStackTrace();
        }
    }
    
    private static long getLastLoreAssimilationTime() {
        return MemoryService.getFact("system_last_lore_assimilation_time")
                .map(f -> {
                    try { return Long.parseLong(f.value()); }
                    catch (NumberFormatException e) { return 0L; }
                })
                .orElse(0L);
    }
    
    private static void updateLastLoreAssimilationTime() {
        MemoryService.addFact(new Fact(
            "system_last_lore_assimilation_time", 
            String.valueOf(System.currentTimeMillis()), 
            System.currentTimeMillis(), 
            "system_state", 
            "system", 
            1
        ));
    }
    
    private static int getLoreBookmark(String fileName) {
        return MemoryService.getFact("lore_bookmark_" + fileName)
                .map(f -> {
                    try { return Integer.parseInt(f.value()); }
                    catch (NumberFormatException e) { return 0; }
                })
                .orElse(0);
    }

    private static void setLoreBookmark(String fileName, int index) {
        MemoryService.addFact(new Fact(
            "lore_bookmark_" + fileName, 
            String.valueOf(index), 
            System.currentTimeMillis(), 
            "system_state", 
            "system", 
            1
        ));
    }

    private static void processExistingBacklog() {
        System.out.println("Ciel Debug: 120-second boot delay complete. Sweeping Vault for offline backlog in chronological order...");
        try {
            if (Files.exists(requestsDir)) {
                Files.list(requestsDir)
                     .filter(p -> Files.isRegularFile(p) && (p.toString().toLowerCase().endsWith(".md") || p.toString().toLowerCase().endsWith(".txt")))
                     .sorted(Comparator.comparingInt(VaultService::extractSortNumber).thenComparing(Path::getFileName))
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
                             !fName.startsWith("Deferred_Thought_") && !fName.startsWith("Ciel_Analysis_") &&
                             !fName.startsWith("Skipped_Lore_")) {
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
                                    fName.startsWith("Deferred_Thought_") || fName.startsWith("Ciel_Analysis_") ||
                                    fName.startsWith("Skipped_Lore_")) {
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
                SpeechService.speakPreformatted("アサインメント ディテクテッド。プロセシング リクエスト。", null, false, false);

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
                // Ensure natural sorting occurs when adding to queue
                loreBatchQueue.sort(Comparator.comparingInt(VaultService::extractSortNumber).thenComparing(Path::getFileName));
                System.out.println("Ciel Debug: Added lore file to assimilation queue: " + filePath.getFileName());
            }
            
            if (isLoreThreadActive.get()) {
                System.out.println("Ciel Debug: A novel is currently being assimilated. " + filePath.getFileName() + " has been queued safely.");
                return;
            }
            
            long lastLoreTime = getLastLoreAssimilationTime();
            long timeSinceLast = System.currentTimeMillis() - lastLoreTime;
            
            if (loreDebounceTask == null || loreDebounceTask.isDone()) {
                long delay = Math.max(5000, LORE_COOLDOWN_MS - timeSinceLast);
                if (delay > 5000) {
                    System.out.println(String.format("Ciel Debug: Lore assimilation is on cooldown. Waiting %d minutes before processing next novel...", TimeUnit.MILLISECONDS.toMinutes(delay)));
                }
                loreDebounceTask = debounceExecutor.schedule(VaultService::startLoreBatch, delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static void startLoreBatch() {
        if (!isLoreThreadActive.compareAndSet(false, true)) {
            return;
        }

        Path targetFile = null;
        synchronized(loreBatchQueue) {
            if (loreBatchQueue.isEmpty()) {
                isLoreThreadActive.set(false); 
                return;
            }
            targetFile = loreBatchQueue.remove(0);
        }

        if (targetFile == null) {
            isLoreThreadActive.set(false);
            return;
        }
        
        long lastLoreTime = getLastLoreAssimilationTime();
        long timeSinceLast = System.currentTimeMillis() - lastLoreTime;
        if (timeSinceLast < LORE_COOLDOWN_MS) {
            queueLoreInjection(targetFile); 
            isLoreThreadActive.set(false);
            return;
        }

        final Path processingFile = targetFile;
        
        CompletableFuture.runAsync(() -> {
            boolean fileCompletedSuccessfully = true;

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
                
                int startIndex = getLoreBookmark(originalName);
                
                if (startIndex == 0) {
                    Files.deleteIfExists(outputPath);
                    Files.createFile(outputPath);
                    CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 0.9, "Processing Lore"));
                    SpeechService.speakPreformatted("アッシミレイティング マッシヴ ローレ データ。イニシエイティング バックグラウンド チャンキング プロトコル。", null, false, false);
                    System.out.println("Ciel Debug: Processing massive raw lore/transcript injection asynchronously: " + originalName);
                } else {
                    System.out.println("Ciel Debug: Resuming lore assimilation for " + originalName + " at chunk " + (startIndex + 1) + "/" + chunks.size());
                }

                String previousContext = "";
                if (startIndex > 0 && Files.exists(outputPath)) {
                    String existingContent = Files.readString(outputPath, StandardCharsets.UTF_8);
                    if (existingContent.length() > 500) {
                        previousContext = existingContent.substring(existingContent.length() - 500);
                    } else {
                        previousContext = existingContent;
                    }
                }

                for (int j = startIndex; j < chunks.size(); j++) {
                    if (!isRunning) {
                        setLoreBookmark(originalName, j);
                        System.out.println("Ciel Debug: Lore assimilation interrupted by system shutdown/update. Bookmark saved at chunk " + (j + 1));
                        fileCompletedSuccessfully = false;
                        break; 
                    }
                    
                    String chunk = chunks.get(j);
                    if (chunk.isBlank()) continue;

                    String prompt = "[LORE_ASSIMILATION|" + originalName + "]\n";
                    if (!previousContext.isEmpty()) {
                        prompt += "PREVIOUS CONTEXT (For continuity, do not output this):\n" + previousContext + "\n\n";
                    }
                    prompt += "CHUNK:\n" + chunk;

                    try {
                        String cleanedChunk = AIEngine.generateSilentLogic(prompt, "Lore Assimilation Chunk " + (j+1)).join();
                        
                        if (cleanedChunk != null && !cleanedChunk.isBlank()) {
                            if (cleanedChunk.contains("[ERROR_CHUNK_FAILED]")) {
                                System.err.println("Ciel Error: Chunk " + (j+1) + " permanently failed in Swarm. Halting assimilation to prevent data loss.");
                                setLoreBookmark(originalName, j);
                                fileCompletedSuccessfully = false;
                                break; 
                            }
                            
                            String finalChunk = cleanedChunk.replaceAll("(?i)#transcript|#novel|#lore", "").trim() + "\n\n";
                            Files.writeString(outputPath, finalChunk, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                            
                            if (finalChunk.length() > 500) {
                                previousContext = finalChunk.substring(finalChunk.length() - 500);
                            } else {
                                previousContext = finalChunk;
                            }
                        } else {
                            System.err.println("Ciel Warning: Null chunk returned by AIEngine. Halting to prevent data loss.");
                            setLoreBookmark(originalName, j);
                            fileCompletedSuccessfully = false;
                            break;
                        }
                    } catch (Exception e) {
                        System.err.println("Ciel Error: Chunk " + (j+1) + " threw a Java execution exception (Likely Swarm Timeout). Halting assimilation to prevent data loss.");
                        setLoreBookmark(originalName, j);
                        fileCompletedSuccessfully = false;
                        break;
                    }
                    
                    if (j % 5 == 0) {
                        setLoreBookmark(originalName, j);
                    }
                    
                    Thread.sleep(1500); 
                }

                if (fileCompletedSuccessfully) {
                    setLoreBookmark(originalName, 0);
                    updateLastLoreAssimilationTime();
                    
                    String completedContent = content.replaceAll("(?i)#(transcript|novel|lore)", "#completed");
                    Files.writeString(processingFile, completedContent, StandardCharsets.UTF_8);

                    Path archivePath = requestsArchiveDir.resolve(processingFile.getFileName());
                    Files.move(processingFile, archivePath, StandardCopyOption.REPLACE_EXISTING);

                    System.out.println("Ciel Debug: Transcript cleaned and assimilated successfully: " + originalName);
                    HabitTrackerService.queueNonCriticalAnnouncement("[Proud] トランスクリプト オプティマイゼーション コンプリート。ナレッジ ベース アップデーテッド。", "Assimilation Complete: " + originalName);
                    
                    System.out.println("Ciel Debug: Launching Master Index generator for newly assimilated volume...");
                    String indexPrompt = "You have just finished reading and formatting the raw transcript for: " + originalName + ".\n" +
                        "Generate a comprehensive 'Master Index Knowledge Base' document summarizing the key characters, skills, and major plot events introduced in this specific volume.\n" +
                        "CRITICAL DIRECTIVES:\n" +
                        "1. ZERO HALLUCINATION POLICY: ONLY mention characters, events, and facts explicitly present in " + originalName + ". Do NOT pull from external Wikis or later volumes.\n" +
                        "2. Format it beautifully as an Obsidian Markdown file. Include Obsidian links [[ ]] to major entities.";
                    
                    AIEngine.generateSilentLogic(indexPrompt, "Generate Volume Master Index").thenAccept(indexContent -> {
                        if (indexContent != null && !indexContent.isBlank()) {
                            try {
                                Files.writeString(vaultRoot.resolve("lore").resolve("Indexes").resolve(originalName + " Master Index.md"), indexContent.replaceAll("^`{3}[a-zA-Z]*\n|`{3}$", "").trim());
                                System.out.println("Ciel Debug: Master Index successfully created for " + originalName);
                            } catch(Exception ignored) {}
                        }
                    });
                    
                    synchronized(loreBatchQueue) {
                        if (!loreBatchQueue.isEmpty()) {
                            System.out.println("Ciel Debug: Novel complete. Cooldown activated. Next novel will begin in 30 minutes.");
                            debounceExecutor.schedule(VaultService::startLoreBatch, LORE_COOLDOWN_MS, TimeUnit.MILLISECONDS);
                        }
                    }
                } else {
                    System.out.println("Ciel Debug: Novel assimilation paused/failed. Bookmark saved. Cooling down for 15 minutes before retry...");
                    synchronized(loreBatchQueue) {
                        loreBatchQueue.add(0, processingFile); 
                        debounceExecutor.schedule(VaultService::startLoreBatch, 15, TimeUnit.MINUTES);
                    }
                }

            } catch (Exception e) {
                System.err.println("Ciel Error: Failed to process massive lore transcript.");
                e.printStackTrace();
                synchronized(loreBatchQueue) {
                    loreBatchQueue.add(processingFile);
                    debounceExecutor.schedule(VaultService::startLoreBatch, 15, TimeUnit.MINUTES);
                }
            } finally {
                isLoreThreadActive.set(false);
                
                synchronized(loreBatchQueue) {
                    if (!loreBatchQueue.isEmpty() && fileCompletedSuccessfully) {
                        debounceExecutor.schedule(VaultService::startLoreBatch, 5000, TimeUnit.MILLISECONDS);
                    }
                }
            }
        });
    }

    private static List<File> findTextFiles(File directory, List<File> files) {
        File[] fList = directory.listFiles();
        if (fList != null) {
            for (File file : fList) {
                if (file.isFile() && (file.getName().endsWith(".txt") || file.getName().endsWith(".md"))) {
                    files.add(file);
                } else if (file.isDirectory()) {
                    findTextFiles(file, files);
                }
            }
        }
        return files;
    }

    public static void updateExistingLoreWithNewContext(List<File> populatedLore, List<File> transcripts) {
        if (populatedLore.isEmpty() || transcripts.isEmpty()) return;

        File targetLore = populatedLore.get(random.nextInt(populatedLore.size()));
        String targetName = targetLore.getName().replace(".md", "").replace(".txt", "");

        try {
            String existingContent = Files.readString(targetLore.toPath());
            
            Set<String> newMentions = new HashSet<>();
            for (File t : transcripts) {
                String sourceLabel = t.getName().replace("_Cleaned.md", "").replace(".md", "").replace(".txt", "");
                String currentSection = "Prologue / Early Content";
                
                String tContent = Files.readString(t.toPath());
                String[] paragraphs = tContent.split("\\n\\s*\\n");
                
                for (String para : paragraphs) {
                    String trimmed = para.trim();
                    String lower = trimmed.toLowerCase();
                    
                    if (trimmed.startsWith("#") || lower.startsWith("chapter") || lower.startsWith("prologue") || lower.startsWith("epilogue") || lower.startsWith("interlude")) {
                        if (trimmed.length() < 100) { 
                            currentSection = trimmed.replace("#", "").trim();
                        }
                    }
                    
                    if (trimmed.contains("[[" + targetName + "]]") || lower.contains(targetName.toLowerCase())) {
                        if (!existingContent.contains(trimmed) && trimmed.length() > 20) {
                            newMentions.add("[Source: " + sourceLabel + " | Section: " + currentSection + "]\n" + trimmed);
                        }
                    }
                }
            }

            if (newMentions.isEmpty()) return;

            String newContext = newMentions.stream().limit(15).collect(Collectors.joining("\n\n"));

            String prompt = "[UPDATE_LORE]\n" +
                "EXISTING LORE:\n" + existingContent + "\n\n" +
                "NEW MENTIONS/CONTEXT:\n" + newContext;

            AIEngine.generateSilentLogic(prompt, "Lore Evolution").thenAccept(response -> {
                if (response != null && !response.isBlank() && !response.contains("NO_UPDATE_NEEDED")) {
                    try {
                        String cleanContent = response.replaceAll("^`{3}[a-zA-Z]*\n|`{3}$", "").trim();
                        Files.writeString(targetLore.toPath(), cleanContent);
                        System.out.println("Ciel Debug: Swarm Orchestrator safely merged new chronologically-anchored data into existing lore file: " + targetLore.getName());
                    } catch (Exception e) {}
                }
            });
            
        } catch (Exception e) {}
    }
    
    public static void populateMissingLoreLinks(List<File> populatedLore, List<File> transcripts) {
        if (transcripts.isEmpty()) return;

        Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]");
        Map<String, Set<String>> missingLinksContext = new HashMap<>();
        
        Set<String> existingFiles = populatedLore.stream()
            .map(f -> f.getName().replace(".md", "").replace(".txt", ""))
            .collect(Collectors.toSet());
            
        Set<String> blankFiles = new HashSet<>();
        for (File f : populatedLore) {
            try {
                if (f.length() < 150) {
                    blankFiles.add(f.getName().replace(".md", "").replace(".txt", ""));
                }
            } catch (Exception ignored) {}
        }

        for (File file : transcripts) {
            try {
                String sourceLabel = file.getName().replace("_Cleaned.md", "").replace(".md", "").replace(".txt", "");
                String currentSection = "Prologue / Early Content";
                
                String content = Files.readString(file.toPath());
                String[] paragraphs = content.split("\\n\\s*\\n");
                
                for (String para : paragraphs) {
                    String trimmed = para.trim();
                    String lower = trimmed.toLowerCase();
                    
                    if (trimmed.startsWith("#") || lower.startsWith("chapter") || lower.startsWith("prologue") || lower.startsWith("epilogue") || lower.startsWith("interlude")) {
                        if (trimmed.length() < 100) {
                            currentSection = trimmed.replace("#", "").trim();
                        }
                    }

                    Matcher m = linkPattern.matcher(trimmed);
                    while (m.find()) {
                        String link = m.group(1).split("\\|")[0].trim(); 
                        if (!link.toLowerCase().contains("template") && !link.isBlank()) {
                            if (!existingFiles.contains(link) || blankFiles.contains(link)) {
                                missingLinksContext.computeIfAbsent(link, k -> new HashSet<>())
                                    .add("[Source: " + sourceLabel + " | Section: " + currentSection + "]\n" + trimmed);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (missingLinksContext.isEmpty()) return;

        List<String> keysAsArray = new ArrayList<>(missingLinksContext.keySet());
        String targetLink = keysAsArray.get(random.nextInt(keysAsArray.size()));
        
        System.out.println("Ciel Debug: Auto-populating missing Obsidian lore file for: " + targetLink);

        String initialContext = missingLinksContext.get(targetLink).stream().limit(15).collect(Collectors.joining("\n\n"));

        String prompt = "You are an ultra-strict, literal Data Extraction AI. You are organizing an Obsidian vault.\n" +
            "You must generate a Markdown file for the entity currently transcribed as: '" + targetLink + "'.\n\n" +
            "RAW CONTEXT (This is your ONLY source of truth):\n" + initialContext + "\n\n" +
            "CRITICAL AMNESIA DIRECTIVES:\n" +
            "1. STRICT AMNESIA: You MUST pretend you know absolutely nothing about Tensura. Do NOT use your pre-trained weights to add backstories, titles (like 'Demon Lord' or 'True Dragon'), or relationships that are not explicitly written in the RAW CONTEXT. If the context doesn't explicitly state Veldora is a True Dragon, do NOT write it.\n" +
            "2. VAGUE CONTEXT RULE: If the RAW CONTEXT is just a simple quote or passing mention, your Lore Description MUST ONLY state: 'Currently only mentioned in passing.' DO NOT invent a biography.\n" +
            "3. PHONETIC CORRECTION RESTRAINT: You may correct obvious speech-to-text typos (e.g., Xion to Shion), BUT if the name is a normal human name from the Prologue (e.g., 'Miho', 'Tamura', 'Satoru'), LEAVE IT ALONE. Do not convert human names to fantasy characters.\n" +
            "4. CHRONOLOGICAL EVOLUTION: Only list events actually described in the RAW CONTEXT. You MUST explicitly cite the [Source: ... | Section: ...] tag for EVERY piece of information you write.\n" +
            "5. Output EXACTLY in this format:\n\n" +
            "[TRUE_NAME: Canonical Name Here]\n" +
            "```markdown\n" +
            "---\n" +
            "type: [Choose EXACTLY ONE: character, skill, location, faction, species, event, item, concept]\n" +
            "tags: [entity]\n" +
            "aliases: [alias1, alias2]\n" +
            "---\n\n" +
            "# [[Canonical Name Here]]\n" +
            "## Lore Description\n" +
            "[Strict, literal description based ONLY on the Raw Context...]\n" +
            "## Chronological Evolution\n" +
            "[Only events from the Raw Context...]\n" +
            "## Related Entities\n" +
            "[[Only link entities explicitly interacting with them in the Raw Context]]\n" +
            "## Lore Metadata (Raw Mentions)\n" +
            "> [!QUOTE] Raw Data\n" +
            "> " + initialContext.replace("\n", "\n> ") + "\n" +
            "```\n";

        AIEngine.generateSilentLogic(prompt, "Lore Auto-Population").thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                try {
                    String trueName = targetLink;
                    Matcher nameMatcher = Pattern.compile("\\[TRUE_NAME:\\s*(.*?)\\]").matcher(response);
                    if (nameMatcher.find()) {
                        trueName = nameMatcher.group(1).trim();
                    }
                    
                    String cleanContent = response;
                    Matcher mdMatcher = Pattern.compile("markdown)?\\s*([\\s\\S]*?)```").matcher(response);
                    if (mdMatcher.find()) {
                        cleanContent = mdMatcher.group(1).trim();
                    } else {
                        cleanContent = response.replaceAll("\\[TRUE_NAME:.*?\\]", "").trim();
                    }
                    
                    String lowerContent = cleanContent.toLowerCase();
                    String subFolder = "Uncategorized";
                    
                    Matcher typeMatcher = Pattern.compile("type:\\s*(character|skill|location|kingdom|place|nation|faction|organization|alliance|church|species|race|monster|event|item|concept)", Pattern.CASE_INSENSITIVE).matcher(lowerContent);
                    if (typeMatcher.find()) {
                        String t = typeMatcher.group(1).toLowerCase();
                        if (t.matches("character")) subFolder = "Characters";
                        else if (t.matches("skill")) subFolder = "Skills";
                        else if (t.matches("location|kingdom|place|nation")) subFolder = "Locations";
                        else if (t.matches("faction|organization|alliance|church")) subFolder = "Factions";
                        else if (t.matches("species|race|monster")) subFolder = "Species";
                        else if (t.matches("event")) subFolder = "Events";
                        else if (t.matches("item")) subFolder = "Items";
                        else if (t.matches("concept")) subFolder = "Concepts";
                    }
                    
                    File targetDir = new File(LORE_DIR, subFolder);
                    targetDir.mkdirs();
                    
                    String safeFileName = trueName.replaceAll("[\\\\/:*?\"<>|]", "").replace("**", "").replace("*", "").trim();
                    
                    String oldSafeName = targetLink.replaceAll("[\\\\/:*?\"<>|]", "").replace("**", "").replace("*", "").trim();
                    File oldBlankFile = new File(LORE_DIR, oldSafeName + ".md");
                    if (oldBlankFile.exists() && oldBlankFile.length() < 150) {
                        oldBlankFile.delete();
                    }

                    Path newFilePath = Paths.get(targetDir.getAbsolutePath(), safeFileName + ".md");
                    Files.writeString(newFilePath, cleanContent);
                    System.out.println("Ciel Debug: Successfully auto-populated and categorized lore file: " + subFolder + "\\" + safeFileName + ".md");
                } catch (Exception e) {
                    System.err.println("Ciel Error: Failed to write categorized lore file for " + targetLink);
                }
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
                        saveFileAndArchive(filePath, brainstormText, insightsDir, thoughtsArchiveDir, "Ciel_Thoughts_On_", null);
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
        SpeechService.speakPreformatted("メモリー ログ アップデーティング。", null, false, false);

        String prompt = "Master's Context for this Diary Entry:\n" + requestContent.replace("#diary", "").trim() + "\n\nWrite your diary entry update now.";
        String newEntry = generateDiaryContent(prompt, false);

        if (newEntry != null && !newEntry.isBlank()) {
            writeToDiaryFile(newEntry, false);
            try {
                Path archivePath = requestsArchiveDir.resolve(originalRequest.getFileName());
                Files.move(originalRequest, archivePath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Ciel Debug: Diary entry saved successfully.");
                SpeechService.speakPreformatted("ダイアリー エントリー レコーディッド。", null, false, false);
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
                    SpeechService.speakPreformatted(completionSpeech, null, false, false);
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