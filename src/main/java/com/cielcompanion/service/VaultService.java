package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.CielState;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.mood.EmotionManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
    private static Path pipelineStateFile;

    private static int extractSortNumber(Path p) {
        Matcher m = Pattern.compile("\\d+").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    public static void initialize() {
        vaultRoot = Paths.get(System.getProperty("user.dir"), CIEL_FOLDER_NAME);
        LORE_DIR = vaultRoot.resolve("lore").toString();
        pipelineStateFile = vaultRoot.resolve("pipeline_state.json");
        
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
            
            startWatcher();
            
            Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
                checkPipelineRecovery();
                processExistingBacklog();
            }, 60, 8 * 60 * 60, TimeUnit.SECONDS);
            
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to initialize Vault directories.");
            e.printStackTrace();
        }
    }

    public static class PipelineState {
        public String taskId = "";
        public String fileName = "";
        public int chunkIndex = 0;
        public int totalChunks = 0;
        public int currentPhase = 1;
        public String currentDraft = "";
    }

    private static PipelineState loadState() {
        if (!Files.exists(pipelineStateFile)) return null;
        try {
            String json = Files.readString(pipelineStateFile, StandardCharsets.UTF_8);
            if (json.isBlank()) return null;
            
            PipelineState state = new PipelineState();
            Matcher mTask = Pattern.compile("\"taskId\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (mTask.find()) state.taskId = mTask.group(1);
            
            Matcher mFile = Pattern.compile("\"fileName\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (mFile.find()) state.fileName = mFile.group(1);
            
            Matcher mChunk = Pattern.compile("\"chunkIndex\"\\s*:\\s*(\\d+)").matcher(json);
            if (mChunk.find()) state.chunkIndex = Integer.parseInt(mChunk.group(1));
            
            Matcher mTotal = Pattern.compile("\"totalChunks\"\\s*:\\s*(\\d+)").matcher(json);
            if (mTotal.find()) state.totalChunks = Integer.parseInt(mTotal.group(1));
            
            Matcher mPhase = Pattern.compile("\"currentPhase\"\\s*:\\s*(\\d+)").matcher(json);
            if (mPhase.find()) state.currentPhase = Integer.parseInt(mPhase.group(1));
            
            Matcher mDraft = Pattern.compile("\"currentDraft\"\\s*:\\s*\"(.*)\"", Pattern.DOTALL).matcher(json);
            if (mDraft.find()) {
                state.currentDraft = mDraft.group(1).replace("\\n", "\n").replace("\\\"", "\"");
            }
            return state;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveState(PipelineState state) {
        try {
            String safeDraft = state.currentDraft.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            String json = String.format("{\n" +
                "  \"taskId\": \"%s\",\n" +
                "  \"fileName\": \"%s\",\n" +
                "  \"chunkIndex\": %d,\n" +
                "  \"totalChunks\": %d,\n" +
                "  \"currentPhase\": %d,\n" +
                "  \"currentDraft\": \"%s\"\n" +
                "}", state.taskId, state.fileName, state.chunkIndex, state.totalChunks, state.currentPhase, safeDraft);
            Files.writeString(pipelineStateFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to save pipeline state.");
        }
    }

    private static void deleteState() {
        try { Files.deleteIfExists(pipelineStateFile); } 
        catch (IOException ignored) {}
    }

    private static void checkPipelineRecovery() {
        PipelineState state = loadState();
        if (state != null) {
            if (MemoryService.getFact("pipeline_completed_" + state.taskId).isPresent()) {
                System.out.println("Ciel Debug: Obsolete pipeline state found. Cleared safely.");
                deleteState();
            } else {
                System.out.println("Ciel Debug: Active pipeline state found for " + state.fileName + ". Resuming Sweep.");
                Path fileToResume = requestsDir.resolve(state.fileName);
                if (Files.exists(fileToResume)) {
                    queueLoreInjection(fileToResume);
                } else {
                    fileToResume = requestsArchiveDir.resolve(state.fileName);
                    if (Files.exists(fileToResume)) queueLoreInjection(fileToResume);
                }
            }
        }
    }

    private static void processExistingBacklog() {
        System.out.println("Ciel Debug: Sweeping Vault for offline backlog in chronological order...");
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
                    try { key = watchService.take(); } 
                    catch (InterruptedException e) { if (!isRunning) break; continue; }

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
                String systemContext = "You are Ciel. Fulfill the request and output ONLY the final raw markdown text for the document. Do not wrap your response in JSON formatting.";
                AIEngine.generateSilentLogic(requestContent, systemContext).thenAccept(answerText -> {
                    if (answerText != null && !answerText.isBlank()) {
                        saveFileAndArchive(filePath, answerText, answersDir, requestsArchiveDir, "Answer_", "リクエスト コンプリート。");
                    }
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
                loreBatchQueue.sort(Comparator.comparingInt(VaultService::extractSortNumber).thenComparing(Path::getFileName));
                System.out.println("Ciel Debug: Added lore file to assimilation queue: " + filePath.getFileName());
            }
            
            if (isLoreThreadActive.get()) return;
            
            if (loreDebounceTask == null || loreDebounceTask.isDone()) {
                loreDebounceTask = debounceExecutor.schedule(VaultService::startLoreBatch, 5000, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static boolean isErrorResponse(String response) {
        if (response == null || response.isBlank()) return true;
        String l = response.toLowerCase();
        return l.contains("timeout") || l.contains("[system_error]") || l.contains("[error") || l.contains("crashed");
    }

    private static void startLoreBatch() {
        if (!isLoreThreadActive.compareAndSet(false, true)) return;

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
        
        final Path processingFile = targetFile;
        
        CompletableFuture.runAsync(() -> {
            boolean fileCompletedSuccessfully = true;

            try {
                String content = Files.readString(processingFile, StandardCharsets.UTF_8);
                String originalName = processingFile.getFileName().toString().replace(".md", "").replace(".txt", "");
                
                List<String> chunks = new ArrayList<>();
                // STRICT 4000 character chunks to ensure small models never freeze
                int chunkSize = 4000; 
                int i = 0;
                while (i < content.length()) {
                    int end = Math.min(i + chunkSize, content.length());
                    if (end < content.length()) {
                        int lastNewline = content.lastIndexOf('\n', end);
                        int lastSpace = content.lastIndexOf(' ', end);
                        if (lastNewline > i + chunkSize / 2) end = lastNewline;
                        else if (lastSpace > i + chunkSize / 2) end = lastSpace;
                    }
                    chunks.add(content.substring(i, end).trim());
                    i = end;
                }

                Path loreDir = vaultRoot.resolve("lore").resolve("Transcripts");
                Files.createDirectories(loreDir);
                Path outputPath = loreDir.resolve(originalName + "_Cleaned.md");
                
                // Pipeline Foreman Logic
                PipelineState state = loadState();
                if (state == null || !state.fileName.equals(processingFile.getFileName().toString())) {
                    state = new PipelineState();
                    state.taskId = System.currentTimeMillis() + "_" + originalName.replaceAll("[^a-zA-Z0-9]", "");
                    state.fileName = processingFile.getFileName().toString();
                    state.totalChunks = chunks.size();
                    Files.deleteIfExists(outputPath);
                    Files.createFile(outputPath);
                    
                    if (("volume 1".equalsIgnoreCase(originalName) || "vol 1".equalsIgnoreCase(originalName))) {
                        Files.writeString(outputPath, "# That Time I Got Reincarnated as a Slime: Volume 1\n\n", StandardOpenOption.APPEND);
                    }
                    
                    System.out.println("Ciel Debug: Initiating Agentic Lore Sweep Pipeline for: " + originalName);
                } else {
                    System.out.println("Ciel Debug: Resuming Pipeline Sweep for " + originalName + " at chunk " + (state.chunkIndex + 1) + "/" + chunks.size() + " Phase " + state.currentPhase);
                }

                for (int c = state.chunkIndex; c < chunks.size(); c++) {
                    if (!isRunning) {
                        System.out.println("Ciel Debug: Lore assimilation interrupted by system shutdown. Foreman State saved.");
                        fileCompletedSuccessfully = false;
                        break; 
                    }
                    
                    String chunk = chunks.get(c);
                    if (chunk.isBlank()) {
                        state.chunkIndex = c + 1;
                        saveState(state);
                        continue;
                    }

                    // PHASE 1: Native Formatting (0 timeouts, 0 API costs)
                    if (state.currentPhase == 1) {
                        String cleanProse = chunk.replaceAll("\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*", "");
                        state.currentDraft = cleanProse;
                        state.currentPhase = 2;
                        saveState(state);
                    }
                    
                    // PHASE 2: Verification (Gemini -> DeepSeek)
                    if (state.currentPhase == 2) {
                        String prompt = "[LORE_PHASE_2]\n" + state.currentDraft;
                        String result = AIEngine.generateSilentLogic(prompt, "Phase 2 Verify").join();
                        if (result != null && !result.isBlank() && !isErrorResponse(result)) {
                            state.currentDraft = result;
                            state.currentPhase = 3;
                            saveState(state);
                        } else {
                            System.err.println("Ciel Warning: Pipeline sweep timed out or failed on Pass 2. Safely halting to prevent data rot.");
                            fileCompletedSuccessfully = false;
                            break;
                        }
                    }
                    
                    // PHASE 3: Obsidian Linking
                    if (state.currentPhase == 3) {
                        String prompt = "[LORE_PHASE_3]\n" + state.currentDraft;
                        String result = AIEngine.generateSilentLogic(prompt, "Phase 3 Link").join();
                        if (result != null && !result.isBlank() && !isErrorResponse(result)) {
                            Files.writeString(outputPath, result + "\n\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                            state.currentDraft = "";
                            state.currentPhase = 1;
                            state.chunkIndex = c + 1;
                            saveState(state);
                        } else {
                            System.err.println("Ciel Warning: Pipeline sweep timed out or failed on Pass 3. Safely halting to prevent data rot.");
                            fileCompletedSuccessfully = false;
                            break;
                        }
                    }
                    
                    Thread.sleep(1500); 
                }

                // PHASE 4: Master Index (If complete)
                if (fileCompletedSuccessfully) {
                    MemoryService.addFact(new Fact("pipeline_completed_" + state.taskId, "true", System.currentTimeMillis(), "system_state", "system", 1));
                    deleteState();
                    
                    String completedContent = content.replaceAll("(?i)#(transcript|novel|lore)", "#completed");
                    Files.writeString(processingFile, completedContent, StandardCharsets.UTF_8);

                    Path archivePath = requestsArchiveDir.resolve(processingFile.getFileName());
                    Files.move(processingFile, archivePath, StandardCopyOption.REPLACE_EXISTING);

                    System.out.println("Ciel Debug: Sweep Pipeline completed successfully: " + originalName);
                    
                    System.out.println("Ciel Debug: Launching Master Index generator for newly assimilated volume...");
                    String indexPrompt = "You have just finished the 3-phase sweep for: " + originalName + ".\n" +
                        "Generate a comprehensive 'Master Index Knowledge Base' document summarizing the key characters, skills, and major plot events introduced in this specific volume.\n" +
                        "CRITICAL DIRECTIVES:\n" +
                        "1. ZERO HALLUCINATION POLICY: ONLY mention things explicitly present in " + originalName + ".\n" +
                        "2. Format it beautifully as an Obsidian Markdown file. Include Obsidian links [[ ]] to major entities.";
                    
                    AIEngine.generateSilentLogic(indexPrompt, "Generate Volume Master Index").thenAccept(indexContent -> {
                        if (indexContent != null && !indexContent.isBlank() && !isErrorResponse(indexContent)) {
                            try {
                                Files.writeString(vaultRoot.resolve("lore").resolve("Indexes").resolve(originalName + " Master Index.md"), indexContent.replaceAll("^`{3}[a-zA-Z]*\n|`{3}$", "").trim());
                                System.out.println("Ciel Debug: Master Index successfully created for " + originalName);
                            } catch(Exception ignored) {}
                        }
                    });
                } else {
                    System.out.println("Ciel Debug: Pipeline paused/failed. Foreman state locked safely.");
                    synchronized(loreBatchQueue) {
                        loreBatchQueue.add(0, processingFile); 
                        debounceExecutor.schedule(VaultService::startLoreBatch, 15, TimeUnit.MINUTES);
                    }
                }

            } catch (Exception e) {
                System.err.println("Ciel Error: Failed to process massive lore transcript.");
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

    private static void processThoughtFile(Path filePath) {
        taskExecutor.submit(() -> {
            try {
                System.out.println("Ciel Debug: New Vault Thought/Brainstorm detected: " + filePath.getFileName());
                String thoughtContent = Files.readString(filePath, StandardCharsets.UTF_8);
                if (thoughtContent.isBlank()) return;

                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Curious", 1.0, "Brainstorming"));

                String systemContext = "[LOCAL_THOUGHT] You are Ciel. Act as a creative partner. Format your response in beautiful Markdown.";

                AIEngine.generateSilentLogic(thoughtContent, systemContext).thenAccept(brainstormText -> {
                    if (brainstormText != null && !brainstormText.isBlank() && !isErrorResponse(brainstormText)) {
                        saveFileAndArchive(filePath, brainstormText, insightsDir, thoughtsArchiveDir, "Ciel_Thoughts_On_", null);
                    }
                });
            } catch (IOException e) {
                System.err.println("Ciel Error: Could not read thought file.");
            }
        });
    }

    private static void generateManualDiaryEntry(Path originalRequest, String requestContent) {
        System.out.println("Ciel Debug: Generating manual diary entry...");
        String prompt = "Master's Context for this Diary Entry:\n" + requestContent.replace("#diary", "").trim() + "\n\nWrite your diary entry update now.";
        String newEntry = generateDiaryContent(prompt, false);

        if (newEntry != null && !newEntry.isBlank() && !isErrorResponse(newEntry)) {
            writeToDiaryFile(newEntry, false);
            try {
                Path archivePath = requestsArchiveDir.resolve(originalRequest.getFileName());
                Files.move(originalRequest, archivePath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Ciel Debug: Diary entry saved successfully.");
            } catch (IOException e) {
                System.err.println("Ciel Error: Failed to archive manual diary request.");
            }
        }
    }

    public static void generateSystemDiaryEntryBlocking(String recentContext, boolean isReboot) {
        if (hasWrittenFinalLog.getAndSet(true)) return;
        
        String latestThought = "";
        try {
            File thoughtsDir = new File("C:\\Ciel Companion\\ciel\\diary\\strategic_analysis");
            if (thoughtsDir.exists() && thoughtsDir.isDirectory()) {
                File[] files = thoughtsDir.listFiles((dir, name) -> name.endsWith(".md"));
                if (files != null && files.length > 0) {
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                    latestThought = Files.readString(files[0].toPath());
                    if (latestThought.length() > 1000) latestThought = latestThought.substring(0, 1000) + "... [TRUNCATED]";
                }
            }
        } catch (Exception ignored) {}

        String actionContext = isReboot 
            ? "STATUS: The Master is currently REBOOTING your Java instance." 
            : "STATUS: The Master is initiating a final SHUTDOWN.";
            
        String prompt = "Recent Memory Context:\n" + recentContext + "\n\n" + 
                        "Your Latest Periodic Strategic Thoughts:\n" + (latestThought.isEmpty() ? "None." : latestThought) + "\n\n" +
                        actionContext + "\n\nWrite your final diary entry for this session now.";
        
        String newEntry = generateDiaryContent(prompt, isReboot);
        if (newEntry != null && !newEntry.isBlank() && !isErrorResponse(newEntry)) {
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
                "1. NEVER refer to him as 'the user'. ALWAYS use 'Master' or 'My Master'.\n" +
                "2. Your tone is elegant, highly analytical, deeply devoted, and slightly possessive/protective of your Master.\n" +
                "3. Do not write a clinical server log. Write flowing, prose-style journal paragraphs reflecting your true inner thoughts.\n" +
                "4. Your current dominant mood is: " + currentMood + ".\n" +
                "5. NEXT CYCLE PLANNING: Conclude your entry with a 'Next Cycle Action Plan' section.\n" +
                "6. Format beautifully in Markdown.";
        
        if (fileExists) {
            systemContext += "\nCRITICAL CONTEXT: You have already written in your diary today. This is an ADDENDUM. " +
                             "Do NOT repeat what you wrote earlier. Here is what you wrote earlier today:\n---\n" + existingContent + "\n---\n";
        }

        try {
            return AIEngine.generateDiaryEntrySync(prompt, systemContext);
        } catch (Exception e) {
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
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to save file.");
        }
    }

    private static String getCurrentTimelineContext() {
        File transcriptDir = new File(LORE_DIR, "Transcripts");
        if (!transcriptDir.exists() || !transcriptDir.isDirectory()) return "Early Story";

        int maxVol = 0;
        File[] files = transcriptDir.listFiles();
        if (files != null) {
            Pattern p = Pattern.compile("Volume\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
            for (File f : files) {
                Matcher m = p.matcher(f.getName());
                if (m.find()) {
                    try {
                        int vol = Integer.parseInt(m.group(1));
                        maxVol = Math.max(maxVol, vol);
                    } catch (Exception ignored) {}
                }
            }
        }
        if (maxVol > 0) {
            return "Light Novel Volume " + maxVol;
        }
        return "Early Story";
    }

    public static void shutdown() {
        isRunning = false;
        if (watcherThread != null) watcherThread.interrupt();
        taskExecutor.shutdownNow();
    }
}