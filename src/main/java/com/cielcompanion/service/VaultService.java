package com.cielcompanion.service;

import com.cielcompanion.util.ProjectPaths;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.CielState;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.mood.EmotionManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
import java.util.concurrent.TimeoutException;
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
    private static Path suggestionsDir;
    private static Path pipelineStateDir;
    private static Path lorePartsDir;
    private static Path loreReviewDir;

    private static Thread watcherThread;
    private static volatile boolean isRunning = true;
    
    private static final ExecutorService taskExecutor = Executors.newFixedThreadPool(4);
    private static final Map<Path, Long> processingCache = new ConcurrentHashMap<>();
    private static final AtomicBoolean hasWrittenFinalLog = new AtomicBoolean(false);
    
    // One novel start per three hours, even after a failed checkpoint or JVM restart.
    private static final long LORE_COOLDOWN_MS = 3 * 60 * 60 * 1000L;
    private static final List<Path> loreBatchQueue = Collections.synchronizedList(new ArrayList<>());
    private static ScheduledFuture<?> loreDebounceTask = null;
    private static final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();
    // A volume already contains many model calls. Serializing volumes avoids
    // competing requests and lets each checkpoint advance before the next one.
    private static final int MAX_CONCURRENT_LORE_FILES = 1;
    private static final Set<Path> activeLorePaths = ConcurrentHashMap.newKeySet();

    // Failed background requests remain queued for retry, but repeated failures
    // must not flood the primary log. Keep the first failure and a periodic
    // summary so a persistent endpoint problem remains diagnosable.
    private static final long FAILURE_LOG_COOLDOWN_MS = 120_000L;
    private static final Map<String, FailureLogState> failureLogState = new ConcurrentHashMap<>();

    private static final class FailureLogState {
        volatile long lastLoggedAt;
        volatile int suppressed;
    }

    private static void logFailure(String key, String message) {
        long now = System.currentTimeMillis();
        FailureLogState state = failureLogState.computeIfAbsent(key, ignored -> new FailureLogState());
        synchronized (state) {
            if (state.lastLoggedAt != 0L && now - state.lastLoggedAt < FAILURE_LOG_COOLDOWN_MS) {
                state.suppressed++;
                return;
            }
            String suffix = state.suppressed == 0 ? "" : " (suppressed " + state.suppressed + " similar failures)";
            state.suppressed = 0;
            state.lastLoggedAt = now;
            System.err.println(message + suffix);
        }
    }

    private static String safeFailureReason(String reason) {
        if (reason == null) return "unknown failure";
        String safe = reason.replaceAll("(?i)(api[_-]?key|token|password|secret|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        return safe.length() > 500 ? safe.substring(0, 500) : safe;
    }

    private static final Random random = new Random();
    private static String LORE_DIR;

    private static int extractSortNumber(Path p) {
        Matcher m = Pattern.compile("\\d+").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    public static void initialize() {
        vaultRoot = Paths.get(System.getProperty("user.dir"), CIEL_FOLDER_NAME);
        LORE_DIR = vaultRoot.resolve("lore").toString();
        pipelineStateDir = vaultRoot.resolve("pipeline_state");
        lorePartsDir = pipelineStateDir.resolve("lore_parts");
        loreReviewDir = vaultRoot.resolve("lore").resolve("Review").resolve("Tensura");
        
        requestsDir = vaultRoot.resolve("requests");
        answersDir = vaultRoot.resolve("answers");
        requestsArchiveDir = requestsDir.resolve("archive"); 
        
        thoughtsDir = vaultRoot.resolve("thoughts");
        thoughtsArchiveDir = thoughtsDir.resolve("archive");
        insightsDir = vaultRoot.resolve("insights"); 
        
        diaryDir = vaultRoot.resolve("diary");
        suggestionsDir = vaultRoot.resolve("suggestions");

        try {
            Files.createDirectories(requestsDir);
            Files.createDirectories(answersDir);
            Files.createDirectories(requestsArchiveDir);
            Files.createDirectories(thoughtsDir);
            Files.createDirectories(thoughtsArchiveDir);
            Files.createDirectories(insightsDir);
            Files.createDirectories(diaryDir);
            Files.createDirectories(suggestionsDir);
            Files.createDirectories(pipelineStateDir);
            Files.createDirectories(lorePartsDir);
            Files.createDirectories(loreReviewDir);
            Files.createDirectories(vaultRoot.resolve("memory_core"));
            Files.createDirectories(vaultRoot.resolve("protocols"));
            
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Factions"));
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Locations"));
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Species"));
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Events"));
            Files.createDirectories(vaultRoot.resolve("lore").resolve("Indexes"));

            // Older runs archived some volumes without ever producing a transcript.
            // Put those source texts back in the ordered queue without removing the archive.
            for (int volume = 1; volume <= LoreDocumentStore.MAX_LN_VOLUME; volume++) {
                String name = "LN Volume " + volume;
                Path request = requestsDir.resolve(name + ".md");
                Path archived = requestsArchiveDir.resolve(name + ".md");
                Path transcript = vaultRoot.resolve("lore").resolve("Transcripts")
                        .resolve(name + "_Cleaned.md");
                if (!Files.exists(request) && Files.isRegularFile(archived) && !Files.exists(transcript)) {
                    Files.copy(archived, request);
                    System.out.println("Ciel Lore Recovery: restored unprocessed " + name + " to queue.");
                }
            }

            System.out.println("Ciel Debug: VaultService initialized at " + vaultRoot);

            taskExecutor.submit(VaultService::refreshLoreLibrary);
            debounceExecutor.scheduleWithFixedDelay(
                    () -> taskExecutor.submit(VaultService::refreshLoreLibrary),
                    15, 15, TimeUnit.MINUTES);
            
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
        public long sourceLength = 0;
        public long sourceModified = 0;
    }

    private static Path statePathFor(String fileName) {
        return pipelineStateDir.resolve(fileName.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
    }

    private static PipelineState loadState(String fileName) {
        return loadState(statePathFor(fileName));
    }

    private static PipelineState loadState(Path statePath) {
        if (!Files.exists(statePath)) return null;
        try {
            String json = Files.readString(statePath, StandardCharsets.UTF_8);
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
            Matcher mLength = Pattern.compile("\"sourceLength\"\\s*:\\s*(\\d+)").matcher(json);
            if (mLength.find()) state.sourceLength = Long.parseLong(mLength.group(1));
            Matcher mModified = Pattern.compile("\"sourceModified\"\\s*:\\s*(\\d+)").matcher(json);
            if (mModified.find()) state.sourceModified = Long.parseLong(mModified.group(1));
            Matcher mDraft = Pattern.compile("\"currentDraftBase64\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            if (mDraft.find()) {
                state.currentDraft = new String(Base64.getDecoder().decode(mDraft.group(1)), StandardCharsets.UTF_8);
            } else {
                Matcher legacy = Pattern.compile("\"currentDraft\"\\s*:\\s*\"(.*)\"", Pattern.DOTALL).matcher(json);
                if (legacy.find()) state.currentDraft = legacy.group(1).replace("\\n", "\n").replace("\\\"", "\"");
            }
            return state;
        } catch (Exception e) {
            System.err.println("Ciel Recovery: Invalid checkpoint " + statePath.getFileName() + ": " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static void saveState(PipelineState state) throws IOException {
            String safeDraft = Base64.getEncoder().encodeToString(state.currentDraft.getBytes(StandardCharsets.UTF_8));
            String json = String.format("{\n  \"taskId\": \"%s\",\n  \"fileName\": \"%s\",\n  \"chunkIndex\": %d,\n  \"totalChunks\": %d,\n  \"currentPhase\": %d,\n  \"sourceLength\": %d,\n  \"sourceModified\": %d,\n  \"currentDraftBase64\": \"%s\"\n}",
                    state.taskId, state.fileName, state.chunkIndex, state.totalChunks, state.currentPhase,
                    state.sourceLength, state.sourceModified, safeDraft);
            writeAtomically(statePathFor(state.fileName), json);
    }

    private static void deleteState(String fileName) {
        try { Files.deleteIfExists(statePathFor(fileName)); } catch (IOException ignored) {}
    }

    private static void checkPipelineRecovery() {
        try (var states = Files.list(pipelineStateDir)) {
            states.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                PipelineState state = loadState(p);
                if (state == null || state.fileName.isBlank() || state.taskId.isBlank()) return;
                if (MemoryService.getFact("pipeline_completed_" + state.taskId).isPresent()) {
                    deleteState(state.fileName);
                    return;
                }
                Path source = requestsDir.resolve(state.fileName);
                Path archived = requestsArchiveDir.resolve(state.fileName);
                if (Files.exists(archived) && !Files.exists(source)) {
                    // The source was archived, so restore deterministic post-run indexing before cleanup.
                    String name = state.fileName.replaceFirst("(?i)\\.(md|txt)$", "");
                    Path transcript = vaultRoot.resolve("lore").resolve("Transcripts").resolve(name + "_Cleaned.md");
                    if (Files.exists(transcript)) createGroundedLoreIndex(transcript, name);
                    deleteState(state.fileName);
                    return;
                }
                if (Files.exists(source)) {
                    System.out.println("Ciel Recovery: task=" + state.fileName + " chunk="
                            + (state.chunkIndex + 1) + "/" + state.totalChunks + " phase=" + state.currentPhase);
                    queueLoreInjection(source);
                }
            });
        } catch (IOException e) {
            System.err.println("Ciel Recovery: Could not scan task checkpoints: " + e.getMessage());
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void refreshLoreLibrary() {
        String campaign = Settings.getDndCampaignPath();
        Path campaignRoot = campaign == null || campaign.isBlank() ? null : Paths.get(campaign);
        LoreDocumentStore.initialize(vaultRoot, campaignRoot);
    }

    private static void writeAtomically(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        moveAtomically(temp, path);
    }

    private static Path partPath(PipelineState state, int chunkIndex) throws IOException {
        Path taskParts = lorePartsDir.resolve(state.taskId);
        Files.createDirectories(taskParts);
        return taskParts.resolve(String.format(Locale.ROOT, "chunk_%05d.md", chunkIndex));
    }

    private static String safeLorePart(String value) {
        String safe = value == null ? "" : value.replaceAll("[^\\p{L}\\p{N} _-]", "").trim().replace(' ', '_');
        return safe.isBlank() ? "Uncategorized" : safe.substring(0, Math.min(safe.length(), 80));
    }

    private static JsonObject parseLoreChunkResult(String raw) throws IOException {
        String json = raw == null ? "" : raw.trim();
        json = json.replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < start) throw new IOException("Model response did not contain a JSON object");
        try {
            JsonObject object = JsonParser.parseString(json.substring(start, end + 1)).getAsJsonObject();
            if (!object.has("cleaned_text") || !object.get("cleaned_text").isJsonPrimitive())
                throw new IOException("JSON response is missing cleaned_text");
            if (!object.has("lore_facts") || !object.get("lore_facts").isJsonArray())
                object.add("lore_facts", new JsonArray());
            return object;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not parse model JSON response", e);
        }
    }

    private static void saveLoreFactCandidates(JsonObject result, String volumeName, int chunkIndex,
                                               String sourceChunk) throws IOException {
        JsonArray facts = result.getAsJsonArray("lore_facts");
        if (facts == null || facts.isEmpty()) {
            System.out.println("Ciel Lore Candidates: volume=" + volumeName + " chunk=" + (chunkIndex + 1) + " count=0");
            return;
        }
        Set<String> allowed = Set.of("Characters", "Locations", "Skills", "Factions", "Species", "Items", "Events", "Concepts");
        Map<Path, StringBuilder> grouped = new LinkedHashMap<>();
        int rejected = 0;
        for (JsonElement element : facts) {
            try {
                JsonObject fact = element.getAsJsonObject();
                String category = fact.get("category").getAsString().trim();
                String entity = fact.get("entity").getAsString().trim();
                String claim = fact.get("fact").getAsString().trim();
                String evidence = fact.get("evidence").getAsString().trim();
                if (!allowed.contains(category) || entity.isBlank() || claim.isBlank() || evidence.length() < 8
                        || !sourceChunk.contains(evidence)) {
                    rejected++;
                    continue;
                }
                Path note = loreReviewDir.resolve(safeLorePart(volumeName)).resolve(category)
                        .resolve(safeLorePart(entity)).resolve(String.format(Locale.ROOT, "Chunk_%05d.md", chunkIndex + 1));
                StringBuilder body = grouped.computeIfAbsent(note, ignored -> new StringBuilder(
                        "---\nstatus: review-candidate\nseries: \"That Time I Got Reincarnated as a Slime\"\n"
                                + "spoiler_reviewed: false\nsource_volume: \"" + volumeName + "\"\nsource_chunk: " + (chunkIndex + 1)
                                + "\n---\n\n# " + entity + "\n\n## Candidate fact\n"));
                body.append("- ").append(claim.replace("\r", " ").replace("\n", " ")).append("\n")
                        .append("  - Evidence: \"").append(evidence.replace("\r", " ").replace("\n", " ")).append("\"\n");
            } catch (Exception e) {
                rejected++;
            }
        }
        for (Map.Entry<Path, StringBuilder> note : grouped.entrySet()) writeAtomically(note.getKey(), note.getValue().toString());
        System.out.println("Ciel Lore Candidates: volume=" + volumeName + " chunk=" + (chunkIndex + 1)
                + " accepted=" + grouped.size() + " rejected=" + rejected + " status=review-candidate");
    }

    private static boolean awaitStableFile(Path path, long maxWaitSeconds) throws InterruptedException, IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSeconds);
        long lastSize = -1L, lastModified = -1L;
        int stableSamples = 0;
        while (System.nanoTime() < deadline) {
            if (!Files.exists(path)) return false;
            long size = Files.size(path), modified = Files.getLastModifiedTime(path).toMillis();
            if (size == lastSize && modified == lastModified) stableSamples++;
            else stableSamples = 0;
            if (stableSamples >= 2) return true;
            lastSize = size; lastModified = modified;
            Thread.sleep(2000);
        }
        return false;
    }

    private static void assembleLoreOutput(PipelineState state, Path outputPath, String originalName) throws IOException {
        Path temp = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            if ("volume 1".equalsIgnoreCase(originalName) || "vol 1".equalsIgnoreCase(originalName))
                writer.write("# That Time I Got Reincarnated as a Slime: Volume 1\n\n");
            for (int i = 0; i < state.totalChunks; i++) {
                Path part = partPath(state, i);
                if (!Files.exists(part)) throw new IOException("Missing completed lore chunk " + (i + 1));
                writer.write(Files.readString(part, StandardCharsets.UTF_8));
                writer.write("\n\n");
            }
        }
        moveAtomically(temp, outputPath);
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
                if (!awaitStableFile(filePath, 120)) {
                    System.err.println("Ciel Vault: request file did not settle before timeout; will retry: " + filePath.getFileName());
                    debounceExecutor.schedule(() -> processRequestFile(filePath), 1, TimeUnit.MINUTES);
                    return;
                }
                String requestContent = Files.readString(filePath, StandardCharsets.UTF_8);
                if (requestContent.isBlank()) return;

                if (requestContent.toLowerCase().contains("#completed")) {
                    try {
                        Path archivePath = requestsArchiveDir.resolve(filePath.getFileName());
                        if (!filePath.getParent().equals(requestsArchiveDir)) {
                            Files.move(filePath, archivePath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        PipelineState completedLore = loadState(filePath.getFileName().toString());
                        if (completedLore != null) {
                            String name = completedLore.fileName.replaceFirst("(?i)\\.(md|txt)$", "");
                            Path transcript = vaultRoot.resolve("lore").resolve("Transcripts").resolve(name + "_Cleaned.md");
                            if (Files.exists(transcript)) createGroundedLoreIndex(transcript, name);
                        }
                        deleteState(filePath.getFileName().toString());
                    } catch (Exception ignored) {}
                    return;
                }

                System.out.println("Ciel Debug: New Vault Request detected: " + filePath.getFileName());

                if (requestContent.toLowerCase().contains("#diary")) {
                    generateManualDiaryEntry(filePath, requestContent);
                    return;
                }

                if (isCampaignSuggestionRequest(requestContent)) {
                    processCampaignSuggestion(filePath, requestContent);
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
                AIEngine.generateSilentLogic(requestContent, systemContext, "Saved Request Processing").whenComplete((answerText, error) -> {
                    if (error != null) {
                        System.err.println("Ciel Vault: request inference failed; left queued for recovery: "
                                + filePath.getFileName() + " type=" + error.getClass().getSimpleName());
                        return;
                    }
                    if (answerText != null && !answerText.isBlank()) {
                        saveFileAndArchive(filePath, answerText, answersDir, requestsArchiveDir, "Answer_", "リクエスト コンプリート。");
                    }
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Ciel Vault: request wait interrupted: " + filePath.getFileName());
            } catch (IOException e) {
                System.err.println("Ciel Error: Could not read request file.");
            }
        });
    }

    private static boolean isCampaignSuggestionRequest(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("#campaign") || lower.contains("#suggestion") || lower.contains("#dm-review")) return true;
        boolean campaignTopic = lower.contains("campaign") || lower.contains("across the multiverse");
        boolean asksForIdeas = lower.contains("suggest") || lower.contains("idea") || lower.contains("brainstorm")
                || lower.contains("proposal") || lower.contains("analyz") || lower.contains("develop");
        return campaignTopic && asksForIdeas;
    }

    private static void processCampaignSuggestion(Path requestPath, String requestContent) {
        String knowledgeMode = campaignKnowledgeMode(requestContent);
        String campaignContext = readCampaignNotes(32000, knowledgeMode, requestContent);
        String prompt = "[CAMPAIGN_REVIEW]\n" + requestContent + "\n\n"
                + "CAMPAIGN SOURCE NOTES (read-only context):\n" + campaignContext + "\n\n"
                + "Produce reviewable ideas only. Do not edit, rewrite, or claim to have changed campaign files. "
                + "For each proposal, include the source note(s), rationale, likely impact, and any uncertainty. "
                + "Separate established campaign facts from new suggestions. Output Markdown.\n"
                + ("player".equals(knowledgeMode)
                ? "You are participating as a player/character. Do not use DM knowledge, hidden motives, unrevealed identities, "
                  + "future events, secret encounters, or author-only continuity. If the packet does not establish something, say it is unknown."
                : "This is an explicitly authorized DM review. You may use the supplied DM packet, but label secrets and unresolved canon clearly." );
        String system = "You are Ciel, an attentive D&D campaign assistant. Treat supplied campaign notes as read-only evidence. "
                + "Never finalize or write campaign changes; return suggestions for Master to review. "
                + ("player".equals(knowledgeMode)
                ? "Maintain strict player knowledge boundaries and never infer hidden facts from the structure of the notes."
                : "Keep established facts, DM-only secrets, and new proposals in separate sections.");
        AIEngine.generateSilentLogicWithModel(prompt, system, null, 0.2, "Campaign Analysis")
                .whenComplete((suggestions, error) -> {
                    if (error != null || suggestions == null || suggestions.isBlank() || isErrorResponse(suggestions)) {
                        Throwable cause = error;
                        while (cause != null && cause.getCause() != null) cause = cause.getCause();
                        String reason = error != null
                                ? "error=" + cause.getClass().getSimpleName() + ":" + (cause.getMessage() == null ? "no message" : cause.getMessage())
                                : (suggestions == null || suggestions.isBlank() ? "empty response" : "provider returned an error response");
                        logFailure("campaign-suggestions:" + requestPath.toAbsolutePath(),
                                "Ciel Campaign Suggestions: failed; request remains queued: "
                                        + requestPath.getFileName() + " " + safeFailureReason(reason));
                        return;
                    }
                    saveFileAndArchive(requestPath, suggestions, suggestionsDir, requestsArchiveDir,
                            "Campaign_Suggestions_", "");
                });
    }

    private static String campaignKnowledgeMode(String requestContent) {
        String lower = requestContent == null ? "" : requestContent.toLowerCase(Locale.ROOT);
        if (lower.contains("#dm-review")) return "dm";
        String configured = Settings.getDndKnowledgeMode();
        return "dm".equals(configured) ? "dm" : "player";
    }

    private static boolean isPlayerSafeCampaignNote(Path root, Path file, String content) {
        String relative = root.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String[] blockedSegments = {"02 dm notes", "dm notes", "secrets", "private", "99 utilities"};
        for (String blocked : blockedSegments) {
            for (String segment : relative.split("/")) {
                if (segment.equals(blocked) || segment.contains(blocked)) return false;
            }
        }
        String header = content.substring(0, Math.min(content.length(), 5000));
        if (header.matches("(?s).*?\\A---.*?\\b(?:audience|visibility|access|spoiler_scope)\\s*:\\s*(?:dm|private|secret).*?---.*")) return false;
        if (header.matches("(?is).*?\\A---.*?\\bplayer_visible\\s*:\\s*false.*?---.*")) return false;
        if (header.matches("(?im).*^#+\\s*.*\\b(?:dm only|gm only|secret|private)\\b.*$")) return false;
        if (header.matches("(?im).*^dm_only\\s*:\\s*true\\s*$")) return false;
        return true;
    }

    private static String linkTarget(String raw) {
        if (raw == null) return "";
        String target = raw.split("\\|", 2)[0].split("#", 2)[0].trim();
        return target.replace('\\', '/').replaceAll("(?i)\\.(md|txt)$", "").toLowerCase(Locale.ROOT);
    }

    private static Set<Path> linkedCampaignNotes(Path root, Path source, String content,
                                                  Map<String, Path> byKey) {
        Set<Path> result = new LinkedHashSet<>();
        Matcher links = Pattern.compile("\\[\\[([^\\]]+)\\]\\]").matcher(content);
        while (links.find()) {
            String target = linkTarget(links.group(1));
            if (target.isBlank()) continue;
            Path relative = root.resolve(target).normalize();
            if (!relative.toString().toLowerCase(Locale.ROOT).endsWith(".md")
                    && !relative.toString().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                relative = root.resolve(target + ".md").normalize();
            }
            if (relative.startsWith(root) && Files.isRegularFile(relative)) result.add(relative);
            Path byName = byKey.get(target);
            if (byName != null) result.add(byName);
        }
        return result;
    }

    private static String readCampaignNotes(int maxChars, String knowledgeMode, String requestContent) {
        String configured = com.cielcompanion.service.Settings.getDndCampaignPath();
        if (configured == null || configured.isBlank()) return "No campaign folder is configured.";
        Path root = Paths.get(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return "Configured campaign folder is unavailable: " + root;
        record Note(Path path, String relative, String content, int score) {}
        Map<String, Path> byKey = new HashMap<>();
        Map<Path, String> contents = new HashMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".md")
                            || p.toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted().toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if ("player".equals(knowledgeMode) && !isPlayerSafeCampaignNote(root, file, content)) continue;
                String relative = root.relativize(file).toString().replace('\\', '/');
                contents.put(file, content);
                byKey.putIfAbsent(relative.replaceAll("(?i)\\.(md|txt)$", "").toLowerCase(Locale.ROOT), file);
                byKey.putIfAbsent(file.getFileName().toString().replaceAll("(?i)\\.(md|txt)$", "").toLowerCase(Locale.ROOT), file);
            }
        } catch (IOException e) {
            logFailure("campaign-notes-read", "Ciel Campaign Suggestions: could not read campaign notes: "
                    + safeFailureReason(e.getMessage()));
            return "Campaign notes could not be read. Use only the request content and disclose the missing context.";
        }
        if (contents.isEmpty()) return "No notes are available within the selected campaign knowledge boundary.";
        Set<String> requestedTokens = new HashSet<>();
        for (String token : requestContent.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 3) requestedTokens.add(token);
        }
        List<Note> ranked = new ArrayList<>();
        for (Map.Entry<Path, String> entry : contents.entrySet()) {
            Path file = entry.getKey();
            String relative = root.relativize(file).toString().replace('\\', '/');
            String searchable = (relative + "\n" + entry.getValue()).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String token : requestedTokens) if (searchable.contains(token)) score++;
            if (relative.toLowerCase(Locale.ROOT).contains("player") || relative.toLowerCase(Locale.ROOT).contains("handout")) score += 2;
            ranked.add(new Note(file, relative, entry.getValue(), score));
        }
        ranked.sort(Comparator.comparingInt(Note::score).reversed().thenComparing(Note::relative));
        LinkedHashSet<Path> selected = new LinkedHashSet<>();
        for (Note note : ranked) {
            if (selected.size() >= 8) break;
            if (note.score() > 0 || selected.isEmpty()) selected.add(note.path());
        }
        // Follow Obsidian links from the most relevant notes, but keep the same
        // player-safe filter. This creates a bounded local wiki instead of a
        // positional dump of the vault.
        for (Path source : new ArrayList<>(selected)) {
            if (selected.size() >= 12) break;
            for (Path linked : linkedCampaignNotes(root, source, contents.get(source), byKey)) {
                // Direct path links are rechecked against the already-filtered
                // content map; a link must never bypass the player boundary.
                if (contents.containsKey(linked)) selected.add(linked);
                if (selected.size() >= 12) break;
            }
        }
        StringBuilder context = new StringBuilder("KNOWLEDGE MODE: ").append(knowledgeMode)
                .append("\nOnly the following source notes are available to this reasoning pass.\n");
        for (Path file : selected) {
            if (context.length() >= maxChars) break;
            String relative = root.relativize(file).toString().replace('\\', '/');
            String content = contents.get(file);
            if (content == null) continue;
            int take = Math.min(content.length(), Math.min(6000, maxChars - context.length()));
            if (take <= 0) break;
            context.append("\n\n--- SOURCE: ").append(relative).append(" ---\n")
                    .append(content, 0, take);
        }
        return context.length() <= 80 ? "No relevant notes were found within the selected campaign knowledge boundary." : context.toString();
    }

    private static void queueLoreInjection(Path filePath) {
        synchronized(loreBatchQueue) {
            if (!activeLorePaths.contains(filePath) && !loreBatchQueue.contains(filePath)) {
                loreBatchQueue.add(filePath);
                loreBatchQueue.sort(Comparator.comparingInt(VaultService::extractSortNumber).thenComparing(Path::getFileName));
                System.out.println("Ciel Debug: Added lore file to assimilation queue: " + filePath.getFileName());
            }
            
            if (loreDebounceTask == null || loreDebounceTask.isDone()) {
                loreDebounceTask = debounceExecutor.schedule(VaultService::startLoreBatch, 5000, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static long lastLoreStartMillis() {
        Path schedule = pipelineStateDir.resolve("lore_schedule.json");
        if (!Files.isRegularFile(schedule)) return 0;
        try {
            JsonObject state = JsonParser.parseString(Files.readString(schedule, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return Math.max(0, state.get("lastVolumeStartedEpochMs").getAsLong());
        } catch (Exception e) {
            // A damaged throttle record must not unleash the entire backlog.
            System.err.println("Ciel Lore Schedule: unreadable throttle record; holding the queue until repaired.");
            return System.currentTimeMillis() - LORE_COOLDOWN_MS + TimeUnit.HOURS.toMillis(2);
        }
    }

    private static void recordLoreStart(Path file) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("lastVolumeStartedEpochMs", System.currentTimeMillis());
        state.addProperty("fileName", file.getFileName().toString());
        writeAtomically(pipelineStateDir.resolve("lore_schedule.json"), state.toString());
    }

    private static Path earliestPendingNovel() throws IOException {
        try (var pending = Files.list(requestsDir)) {
            return pending.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("(?i)LN Volume ([1-9]|1[0-9]|2[0-3])\\.md"))
                    .min(Comparator.comparingInt(VaultService::extractSortNumber))
                    .orElse(null);
        }
    }

    private static boolean isErrorResponse(String response) {
        if (response == null || response.isBlank()) return true;
        String l = response.toLowerCase();
        return l.contains("timeout") || l.contains("[system_error]") || l.contains("[error") || l.contains("crashed");
    }

    private static void startLoreBatch() {
        Path targetFile = null;
        synchronized(loreBatchQueue) {
            loreDebounceTask = null;
            if (activeLorePaths.size() >= MAX_CONCURRENT_LORE_FILES || loreBatchQueue.isEmpty()) return;
            long wait = LORE_COOLDOWN_MS - (System.currentTimeMillis() - lastLoreStartMillis());
            if (wait > 0) {
                loreDebounceTask = debounceExecutor.schedule(VaultService::startLoreBatch, wait,
                        TimeUnit.MILLISECONDS);
                System.out.println("Ciel Lore Schedule: next volume deferred for "
                        + TimeUnit.MILLISECONDS.toMinutes(wait) + " minutes; queued=" + loreBatchQueue.size());
                return;
            }
            try {
                Path earliest = earliestPendingNovel();
                if (earliest != null && !loreBatchQueue.contains(earliest)) {
                    loreDebounceTask = debounceExecutor.schedule(VaultService::startLoreBatch, 1,
                            TimeUnit.MINUTES);
                    System.out.println("Ciel Lore Schedule: waiting for earlier volume "
                            + earliest.getFileName() + " to enter the queue.");
                    return;
                }
                if (earliest != null) {
                    loreBatchQueue.remove(earliest);
                    loreBatchQueue.add(0, earliest);
                }
            } catch (IOException e) {
                loreDebounceTask = debounceExecutor.schedule(VaultService::startLoreBatch, 1,
                        TimeUnit.MINUTES);
                System.err.println("Ciel Lore Schedule: cannot verify volume order; holding queue: " + e.getMessage());
                return;
            }
            for (Iterator<Path> it = loreBatchQueue.iterator(); it.hasNext();) {
                Path candidate = it.next();
                if (activeLorePaths.add(candidate)) {
                    try {
                        recordLoreStart(candidate);
                    } catch (IOException e) {
                        activeLorePaths.remove(candidate);
                        loreDebounceTask = debounceExecutor.schedule(VaultService::startLoreBatch, 1,
                                TimeUnit.MINUTES);
                        System.err.println("Ciel Lore Schedule: could not persist throttle; volume held: "
                                + e.getMessage());
                        return;
                    }
                    targetFile = candidate;
                    it.remove();
                    break;
                }
            }
        }

        if (targetFile == null) return;
        
        final Path processingFile = targetFile;
        
        CompletableFuture.runAsync(() -> {
            boolean fileCompletedSuccessfully = true;

            try {
                if (!awaitStableFile(processingFile, 120)) {
                    throw new IOException("request file did not settle before processing");
                }
                String content = Files.readString(processingFile, StandardCharsets.UTF_8);
                String originalName = processingFile.getFileName().toString().replace(".md", "").replace(".txt", "");
                
                List<String> chunks = new ArrayList<>();
                // Keep excerpts bounded; state and completed parts are persisted per file.
                int chunkSize = 6000;
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
                PipelineState state = loadState(processingFile.getFileName().toString());
                long sourceLength = Files.size(processingFile);
                long sourceModified = Files.getLastModifiedTime(processingFile).toMillis();
                if (state == null || !state.fileName.equals(processingFile.getFileName().toString())
                        || state.sourceLength != sourceLength || state.sourceModified != sourceModified
                        || state.totalChunks != chunks.size()) {
                    state = new PipelineState();
                    state.taskId = System.currentTimeMillis() + "_" + originalName.replaceAll("[^a-zA-Z0-9]", "");
                    state.fileName = processingFile.getFileName().toString();
                    state.totalChunks = chunks.size();
                    state.sourceLength = sourceLength;
                    state.sourceModified = sourceModified;
                    Files.createDirectories(lorePartsDir.resolve(state.taskId));
                    saveState(state);
                    
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
                        writeAtomically(partPath(state, c), "");
                        state.chunkIndex = c + 1;
                        state.currentPhase = 1;
                        state.currentDraft = "";
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
                        String prompt = "SOURCE: " + originalName + ", excerpt " + (c + 1) + ".\n"
                                + "Return exactly one JSON object with keys cleaned_text and lore_facts. cleaned_text must preserve every sentence and event, correcting only obvious transcription spelling and punctuation errors. "
                                + "lore_facts must be an array of objects {category, entity, fact, evidence}; use only categories Characters, Locations, Skills, Factions, Species, Items, Events, Concepts. "
                                + "Include only important facts explicitly stated in this excerpt. evidence must be a short exact quotation copied from SOURCE TEXT; omit any fact without a matching quote. "
                                + "Do not use outside knowledge, foreshadow later events, or add headings outside the JSON.\n\nSOURCE TEXT:\n" + state.currentDraft;
                        String result = awaitLorePhase(prompt,
                                "You are a conservative source archivist. Preserve the transcript and extract only explicitly evidenced facts.",
                                "proofread-and-extract", state, c);
                        if (result != null && !result.isBlank() && !isErrorResponse(result)) {
                            // Validate before checkpointing: malformed JSON must never
                            // become a phase-3 checkpoint that every retry reparses.
                            JsonObject validated = parseLoreChunkResult(result);
                            if (validated.get("cleaned_text").getAsString().isBlank())
                                throw new IOException("Model returned empty cleaned transcript for chunk " + (c + 1));
                            state.currentDraft = result;
                            state.currentPhase = 3;
                            saveState(state);
                        } else {
                            System.err.println("Ciel Warning: Pipeline sweep timed out or failed on Pass 2. Safely halting to prevent data rot.");
                            fileCompletedSuccessfully = false;
                            break;
                        }
                    }
                    
                    // PHASE 3 is a local atomic commit. Avoid another model pass,
                    // which doubled latency and could rewrite already-verified prose.
                    if (state.currentPhase == 3) {
                        JsonObject chunkResult;
                        try {
                            chunkResult = parseLoreChunkResult(state.currentDraft);
                            if (chunkResult.get("cleaned_text").getAsString().isBlank())
                                throw new IOException("Model returned empty cleaned transcript for chunk " + (c + 1));
                        } catch (IOException invalidCheckpoint) {
                            // Recover checkpoints saved by older versions without
                            // accepting invalid output or advancing the chunk.
                            state.currentDraft = chunk.replaceAll("\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*", "");
                            state.currentPhase = 2;
                            saveState(state);
                            throw invalidCheckpoint;
                        }
                        String cleanedText = chunkResult.get("cleaned_text").getAsString();
                        if (cleanedText.isBlank()) throw new IOException("Model returned empty cleaned transcript for chunk " + (c + 1));
                        // A model occasionally summarizes instead of proofreading. Keep
                        // lore ingestion lossless: accept only a near-full-length edit;
                        // otherwise commit the deterministic timestamp-only cleanup.
                        String timestampCleanedSource = chunk.replaceAll("\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*", "").trim();
                        int sourceWords = evidenceWordCount(timestampCleanedSource);
                        int cleanedWords = evidenceWordCount(cleanedText);
                        if (sourceWords > 40 && cleanedWords < Math.ceil(sourceWords * 0.90)) {
                            System.err.println("Ciel Warning: Lore proofread reduced chunk " + (c + 1)
                                    + " from " + sourceWords + " to " + cleanedWords
                                    + " words; preserving source text and applying timestamp cleanup only.");
                            cleanedText = timestampCleanedSource;
                        }
                        writeAtomically(partPath(state, c), cleanedText);
                        String evidenceSource = chunk.replaceAll("\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*", "");
                        saveLoreFactCandidates(chunkResult, originalName, c, evidenceSource);
                        state.currentDraft = "";
                        state.currentPhase = 1;
                        state.chunkIndex = c + 1;
                        saveState(state);
                    }
                    
                    Thread.sleep(1500); 
                }

                // PHASE 4: Master Index (If complete)
                if (fileCompletedSuccessfully) {
                    assembleLoreOutput(state, outputPath, originalName);
                    
                    String completedContent = content.replaceAll("(?i)#(transcript|novel|lore)", "#completed");
                    processingCache.put(processingFile, System.currentTimeMillis());
                    writeAtomically(processingFile, completedContent);

                    Path archivePath = requestsArchiveDir.resolve(processingFile.getFileName());
                    Files.move(processingFile, archivePath, StandardCopyOption.REPLACE_EXISTING);

                    createGroundedLoreIndex(outputPath, originalName);
                    taskExecutor.submit(VaultService::refreshLoreLibrary);
                    MemoryService.addFact(new Fact("pipeline_completed_" + state.taskId, "true", System.currentTimeMillis(), "system_state", "system", 1));
                    deleteState(state.fileName);

                    System.out.println("Ciel Debug: Sweep Pipeline completed successfully: " + originalName);
                } else {
                    System.out.println("Ciel Debug: Pipeline paused/failed. Foreman state locked safely.");
                    debounceExecutor.schedule(() -> queueLoreInjection(processingFile), 15, TimeUnit.MINUTES);
                }

            } catch (Exception e) {
                System.err.println("Ciel Error: Lore task failed for " + processingFile.getFileName()
                        + " type=" + e.getClass().getSimpleName() + " message=" + e.getMessage());
                debounceExecutor.schedule(() -> queueLoreInjection(processingFile), 15, TimeUnit.MINUTES);
            } finally {
                activeLorePaths.remove(processingFile);
                synchronized(loreBatchQueue) {
                    if (!loreBatchQueue.isEmpty()) {
                        debounceExecutor.schedule(VaultService::startLoreBatch, 5000, TimeUnit.MILLISECONDS);
                    }
                }
            }
        });

        synchronized (loreBatchQueue) {
            if (!loreBatchQueue.isEmpty() && activeLorePaths.size() < MAX_CONCURRENT_LORE_FILES) {
                debounceExecutor.schedule(VaultService::startLoreBatch, 100, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static int evidenceWordCount(String text) {
        Matcher matcher = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)?").matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String awaitLorePhase(String prompt, String systemPrompt, String phase,
                                         PipelineState state, int chunkIndex) throws Exception {
        long startedAt = System.nanoTime();
        CompletableFuture<String> request = AIEngine.generateSilentLogicWithModel(
                prompt, systemPrompt, null, 0.1, "Lore Processing");
        try {
            String result = request.get(6, TimeUnit.MINUTES);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (result == null || result.isBlank() || isErrorResponse(result)) {
                System.err.println("Ciel Lore Pipeline: phase=" + phase + " file=" + state.fileName
                        + " chunk=" + (chunkIndex + 1) + " result=error-response elapsedMs=" + elapsedMs);
                return null;
            }
            System.out.println("Ciel Lore Pipeline: phase=" + phase + " file=" + state.fileName
                    + " chunk=" + (chunkIndex + 1) + " result=ok chars=" + result.length()
                    + " elapsedMs=" + elapsedMs);
            return result;
        } catch (TimeoutException e) {
            request.cancel(true);
            System.err.println("Ciel Lore Pipeline: phase=" + phase + " file=" + state.fileName
                    + " chunk=" + (chunkIndex + 1) + " result=timeout limitSeconds=360");
            throw e;
        } catch (Exception e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            System.err.println("Ciel Lore Pipeline: phase=" + phase + " file=" + state.fileName
                    + " chunk=" + (chunkIndex + 1) + " result=exception type="
                    + e.getClass().getSimpleName() + " elapsedMs=" + elapsedMs);
            throw e;
        }
    }

    private static void createGroundedLoreIndex(Path transcriptPath, String originalName) {
        Path indexPath = vaultRoot.resolve("lore").resolve("Indexes").resolve(originalName + " Master Index.md");
        try {
            Path volumeReview = loreReviewDir.resolve(safeLorePart(originalName));
            StringBuilder index = new StringBuilder("---\nstatus: review-required\nseries: \"That Time I Got Reincarnated as a Slime\"\n---\n\n")
                    .append("# ").append(originalName).append(" — Lore Index\n\n")
                    .append("Source transcript: [[../Transcripts/").append(transcriptPath.getFileName().toString().replace(".md", ""))
                    .append("]]\nRaw novel source: [[../Raw/").append(originalName)
                    .append("]]\n\nAI-extracted facts below are evidence-checked candidates. Review them for accuracy and spoiler placement before approving.\n");
            if (Files.isDirectory(volumeReview)) {
                try (var notes = Files.walk(volumeReview)) {
                    List<Path> noteFiles = notes.filter(Files::isRegularFile)
                            .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                            .sorted().toList();
                    for (Path note : noteFiles) {
                        String relative = volumeReview.relativize(note).toString().replace('\\', '/').replace(".md", "");
                        index.append("\n- [[../Review/Tensura/").append(safeLorePart(originalName)).append('/')
                                .append(relative).append("]]\n");
                    }
                }
            }
            if (index.toString().endsWith("reviewing accuracy and spoiler placement before approving.\n"))
                index.append("\nNo evidence-backed entity candidates were produced.\n");
            writeAtomically(indexPath, index.toString());
            System.out.println("Ciel Lore Pipeline: phase=index file=" + originalName + " result=ok type=deterministic-review-index");
        } catch (Exception e) {
            System.err.println("Ciel Lore Pipeline: phase=index file=" + originalName + " result=exception type="
                    + e.getClass().getSimpleName());
        }
    }

    private static void processThoughtFile(Path filePath) {
        taskExecutor.submit(() -> {
            try {
                System.out.println("Ciel Debug: New Vault Thought/Brainstorm detected: " + filePath.getFileName());
                String thoughtContent = Files.readString(filePath, StandardCharsets.UTF_8);
                if (thoughtContent.isBlank()) return;

                CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Curious", 1.0, "Brainstorming"));

                String systemContext = "[LOCAL_THOUGHT] You are Ciel. Act as a creative partner. Format your response in beautiful Markdown.";

                AIEngine.generateSilentLogic(thoughtContent, systemContext, "Saved Thought Brainstorming").thenAccept(brainstormText -> {
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
            File thoughtsDir = ProjectPaths.ciel().resolve("diary").resolve("strategic_analysis").toFile();
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
            String originalName = originalRequest.getFileName().toString().replace(".md", "");
            String content = Files.readString(originalRequest, StandardCharsets.UTF_8);
            String taskId = UUID.nameUUIDFromBytes((originalName + "\n" + content).getBytes(StandardCharsets.UTF_8))
                    .toString().substring(0, 12);
            Path answerPath = targetDir.resolve(prefix + originalName + "_" + taskId + ".md");
            writeAtomically(answerPath, answerContent);
            
            if (!content.toLowerCase().contains("#completed")) {
                String completedContent = content.replaceAll("(?i)#(summarize|generate|campaign|suggestion)", "#completed");
                processingCache.put(originalRequest, System.currentTimeMillis());
                writeAtomically(originalRequest, completedContent);
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
