package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.util.CielTools;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HabitTrackerService {

    private static ScheduledExecutorService habitScheduler;
    private static ScheduledExecutorService tripwireScheduler;

    private static Map<String, Long> dailyHabits = new HashMap<>();
    private static String currentCategory = "Idle";
    private static LocalDate currentDate = LocalDate.now();

    private static boolean proactiveTriggeredToday = false;
    private static boolean queueFlushedThisSession = true; 

    private static final Map<String, String> processCategoryCache = new ConcurrentHashMap<>();
    private static final Path PROCESS_CACHE_PATH = Paths.get("C:\\Ciel Companion\\ciel\\process_categories.json");

    private static final Set<String> IGNORED_PROCESSES = Set.of(
            "chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe", 
            "explorer.exe", "idle", "discord.exe", "cmd.exe", "powershell.exe", "pwsh.exe", 
            "conhost.exe", "applicationframehost.exe", "razerappengine.exe", "razer central.exe", 
            "razer synapse.exe", "redragon.exe", "lghub.exe", "steamwebhelper.exe", "steam.exe",
            "epicgameslauncher.exe", "battle.net.exe"
    );

    private static final Path MEDIA_LIST_PATH = Paths.get("C:\\Ciel Companion\\ciel\\media_whitelist.txt");
    private static final Set<String> MEDIA_KEYWORDS = new HashSet<>();
    private static final Map<String, Integer> mediaThresholds = new HashMap<>();

    private static String currentMediaTitle = "";
    private static String currentGameTitle = ""; 
    
    private static final Map<String, Integer> episodeExposureMinutes = new HashMap<>();
    private static final Set<String> loggedMediaToday = new HashSet<>();

    private static final Queue<String> deferredSpeechQueue = new LinkedList<>();
    private static boolean currentGamePausable = false;

    private static String cachedActiveUrl = "";
    private static String cachedDomText = "";
    private static String lastTripwireTitle = "";
    private static String lastTripwirePlatform = "";
    private static String lastLoggedDom = "";
    private static int consecutiveDomFailures = 0;
    
    private static String cachedStreamLink = null;
    private static String cachedMagnetLink = null;
    private static int cachedDurationMinutes = -1;
    private static int cachedCurrentTimeSec = -1;

    private static long stremioNextDeepScrapeTime = 0;

    private static final LinkedList<String> recentMediaHistory = new LinkedList<>();
    private static int currentBingeCount = 0;
    
    private static String deferredIntenseMediaTitle = null;
    
    private static String activeSeriesName = "";
    private static String activeSeriesDom = "";
    private static final List<String> activeSeriesEpisodes = new ArrayList<>();
    
    private static String currentEpisodeHash = "";
    private static boolean hasAcquiredEpisodeMetadata = false;

    private static final Map<String, Long> precomputeRequestTimes = new ConcurrentHashMap<>();

    private static final AtomicLong lastSwarmSuccess = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong lastSwarmFailure = new AtomicLong(0);
    private static final int SWARM_FAILURE_THRESHOLD_MS = 30000; 
    private static final int BASE_BACKOFF_MS = 1000;
    private static final int MAX_BACKOFF_MS = 30000;

    private static String extractFirstMatch(String text, String regex) {
        if (text == null) return null;
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static void loadProcessCategories() {
        try {
            if (Files.exists(PROCESS_CACHE_PATH)) {
                String content = Files.readString(PROCESS_CACHE_PATH, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                for (String key : json.keySet()) {
                    processCategoryCache.put(key, json.get(key).getAsString());
                }
            } else {
                String[] defaultGames = {
                    "helldivers2.exe", "eldenring.exe", "minecraft.windows.exe", "r5apex.exe", "rocketleague.exe",
                    "retroarch.exe", "snes9x.exe", "dolphin.exe", "pcsx2.exe", "rpcs3.exe", 
                    "yuzu.exe", "ryujinx.exe", "cemu.exe", "citra-qt.exe", "project64.exe", "desmume.exe", 
                    "duckstation-qt.exe", "xenia.exe", "xemu.exe"
                };
                for (String g : defaultGames) processCategoryCache.put(g, "Gaming");
                saveProcessCategories();
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to load process category cache.");
        }
    }

    private static void saveProcessCategories() {
        try {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, String> entry : processCategoryCache.entrySet()) {
                if (!entry.getValue().equals("Analyzing...")) {
                    json.addProperty(entry.getKey(), entry.getValue());
                }
            }
            Files.createDirectories(PROCESS_CACHE_PATH.getParent());
            Files.writeString(PROCESS_CACHE_PATH, json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {}
    }

    private static void loadMediaList() {
        try {
            if (!Files.exists(MEDIA_LIST_PATH)) {
                Files.createDirectories(MEDIA_LIST_PATH.getParent());
                Files.createFile(MEDIA_LIST_PATH);
                Files.write(MEDIA_LIST_PATH,
                        List.of("youtube", "netflix", "hulu", "disney+", "prime video",
                                "crunchyroll", "hidive", "stremio"),
                        StandardCharsets.UTF_8);
            }
            List<String> lines = Files.readAllLines(MEDIA_LIST_PATH, StandardCharsets.UTF_8);
            MEDIA_KEYWORDS.clear();
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    MEDIA_KEYWORDS.add(trimmed.toLowerCase());
                }
            }
        } catch (Exception e) {
            MEDIA_KEYWORDS.clear();
            MEDIA_KEYWORDS.addAll(Arrays.asList("youtube", "netflix", "hulu", "disney+", "prime video", "crunchyroll", "hidive", "stremio"));
        }
    }

    private static void saveMediaList() {
        try {
            Files.createDirectories(MEDIA_LIST_PATH.getParent());
            List<String> lines = new ArrayList<>(MEDIA_KEYWORDS);
            Collections.sort(lines);
            Files.write(MEDIA_LIST_PATH, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {}
    }

    public static void addMediaEntry(String entry) {
        if (entry == null || entry.isBlank()) return;
        String lower = entry.trim().toLowerCase();
        if (MEDIA_KEYWORDS.add(lower)) {
            saveMediaList();
        }
    }

    static boolean isMediaTitle(String title) {
        if (title == null || title.isBlank()) return false;
        String lowerTitle = title.toLowerCase();
        for (String kw : MEDIA_KEYWORDS) {
            if (lowerTitle.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    public static void initialize() {
        loadProcessCategories();
        loadMediaList();
        habitScheduler = Executors.newSingleThreadScheduledExecutor();
        tripwireScheduler = Executors.newSingleThreadScheduledExecutor();
        habitScheduler.scheduleWithFixedDelay(HabitTrackerService::pollAndTrack, 2, 60, TimeUnit.SECONDS);
        tripwireScheduler.scheduleWithFixedDelay(HabitTrackerService::tripwireCheck, 3, 3, TimeUnit.SECONDS);
    }

    private static String extractPlatform(String title, String processName) {
        String lower = title != null ? title.toLowerCase() : "";
        String proc = processName != null ? processName.toLowerCase() : "";
        if (proc.contains("stremio") || lower.contains("stremio")) return "stremio";
        if (lower.contains("crunchyroll")) return "crunchyroll";
        if (lower.contains("youtube") || lower.contains("youtu.be")) return "youtube";
        if (lower.contains("netflix")) return "netflix";
        if (lower.contains("hulu")) return "hulu";
        if (lower.contains("prime video")) return "prime video";
        if (lower.contains("viz")) return "viz";
        if (lower.contains("hidive")) return "hidive";
        return "unknown";
    }

    private static void tripwireCheck() {
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        final String activeTitle = metrics.activeWindowTitle();
        final String activeProcess = metrics.activeProcessName().toLowerCase();
        
        if (activeTitle == null || activeTitle.isBlank() || activeTitle.equals("Program Manager")) {
            return;
        }
        
        final boolean isStremioProc = activeProcess.contains("stremio");
        final boolean isStremio = isStremioProc || activeTitle.toLowerCase().contains("stremio");
        final boolean isMedia = isMediaTitle(activeTitle) || isStremioProc;
        
        boolean titleChanged = !activeTitle.equals(lastTripwireTitle);
        
        if (isMedia) {
            if (titleChanged) {
                consecutiveDomFailures = 0;
                hasAcquiredEpisodeMetadata = false;
                cachedDomText = "";
                cachedActiveUrl = "";
                cachedStreamLink = null;
                cachedMagnetLink = null;
                cachedDurationMinutes = -1;
                cachedCurrentTimeSec = -1;
            }
            
            final boolean isShowPlatform = isMedia;
            String rawTitlePrefix = activeTitle.split("-")[0].split("\\|")[0].trim();
            String newSeriesNameTemp = rawTitlePrefix;
            
            if (isStremio && activeTitle.contains("-")) {
                String[] titleParts = activeTitle.split("-");
                if (titleParts.length > 1) {
                    newSeriesNameTemp = titleParts[1].trim();
                }
            }
            newSeriesNameTemp = newSeriesNameTemp.replaceAll("(?i)\\s+(episode|ep|vol|chapter|ch)\\s*\\d+.*", "").trim();
            if (newSeriesNameTemp.isEmpty()) newSeriesNameTemp = rawTitlePrefix;
            final String newSeriesName = newSeriesNameTemp;
            
            boolean tempIsSameScene = false;
            if (isShowPlatform && !activeSeriesName.isEmpty() && !newSeriesName.isEmpty()) {
                String lowerOld = activeSeriesName.toLowerCase();
                String lowerNew = newSeriesName.toLowerCase();

                if ((lowerOld.contains(lowerNew) && lowerNew.length() >= 5) ||
                    (lowerNew.contains(lowerOld) && lowerOld.length() >= 5) ||
                    lowerOld.equals(lowerNew)) {
                    tempIsSameScene = true;
                }
            }
            final boolean isSameScene = tempIsSameScene;

            if (isShowPlatform && !isSameScene) {
                activeSeriesName = newSeriesName;
                activeSeriesDom = "";
                activeSeriesEpisodes.clear();
            } else if (!isShowPlatform) {
                activeSeriesName = "";
                activeSeriesDom = "";
                activeSeriesEpisodes.clear();
            }

            final String currentPlatform = extractPlatform(activeTitle, activeProcess);

            if (!currentPlatform.equals(lastTripwirePlatform) || !isSameScene) {
                cachedDomText = "";
                cachedActiveUrl = "";
                cachedStreamLink = null;
                cachedMagnetLink = null;
            }
            
            lastTripwireTitle = activeTitle;
            lastTripwirePlatform = currentPlatform;
            final boolean doDeepScrape = !hasAcquiredEpisodeMetadata;
            
            CompletableFuture.runAsync(() -> {
                try {
                    JsonObject mediaData = getActiveMediaData(activeTitle, currentPlatform, doDeepScrape);
                    if (mediaData != null) {
                        
                        if (mediaData.has("url") && !mediaData.get("url").isJsonNull()) {
                            cachedActiveUrl = mediaData.get("url").getAsString();
                        }
                        
                        if (mediaData.has("current_time_sec") && !mediaData.get("current_time_sec").isJsonNull()) {
                            cachedCurrentTimeSec = mediaData.get("current_time_sec").getAsInt();
                        }
                        
                        if (mediaData.has("dom") && !mediaData.get("dom").isJsonNull()) {
                            String newDom = mediaData.get("dom").getAsString();
                            
                            if (!"NO_CHANGE".equals(newDom) && newDom.length() > 50) {
                                cachedDomText = newDom;
                                
                                String oldStream = cachedStreamLink;
                                cachedStreamLink = extractFirstMatch(newDom, "STREAM_LINK:\\s*([^\\s]+)");
                                cachedMagnetLink = extractFirstMatch(newDom, "MAGNET_LINK:\\s*([^\\s]+)");
                                
                                // Auto-Advance Detection: If the stream/magnet link changes natively without the window title changing
                                if (oldStream != null && cachedStreamLink != null && !oldStream.equals(cachedStreamLink)) {
                                    hasAcquiredEpisodeMetadata = false; 
                                }
                                
                                if (doDeepScrape) {
                                    String durMatch = extractFirstMatch(newDom, "DURATION_MINUTES:\\s*(\\d+)");
                                    if (durMatch != null) {
                                        try { cachedDurationMinutes = Integer.parseInt(durMatch); } catch (Exception e) {}
                                    }
                                    
                                    String extractedSeries = extractFirstMatch(newDom, "SERIES:\\s*(.+)");
                                    String extractedEp = extractFirstMatch(newDom, "EPISODE:\\s*(.+)");
                                    
                                    if (extractedSeries != null && extractedEp != null && !extractedSeries.contains("Unknown")) {
                                        hasAcquiredEpisodeMetadata = true;
                                        String hash = extractedSeries.trim() + "_" + extractedEp.trim();
                                        
                                        if (!hash.equals(currentEpisodeHash)) {
                                            currentEpisodeHash = hash;
                                            System.out.println("[HabitTracker] Detected New Episode: " + currentEpisodeHash);
                                            if (cachedCurrentTimeSec > 0) {
                                                String cleanTitle = extractedSeries.trim() + " - " + extractedEp.trim();
                                                episodeExposureMinutes.put(cleanTitle, cachedCurrentTimeSec / 60);
                                                System.out.println("[HabitTracker] Syncing internal timer to extracted DOM timecode: " + (cachedCurrentTimeSec / 60) + " mins.");
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        consecutiveDomFailures++;
                    }
                } catch (Exception e) {}
            });
        } else if (!isMedia && !lastTripwireTitle.isEmpty()) {
            lastTripwireTitle = ""; 
        }
    }

    public static String getProcessCategory(String processName) {
        if (processName == null) return "Idle";
        return processCategoryCache.getOrDefault(processName.toLowerCase(), "Idle");
    }

    public static String getCurrentCategory() {
        return currentCategory;
    }

    public static int getCurrentExposureMinutes() {
        if ("Media".equals(currentCategory) && currentMediaTitle != null && !currentMediaTitle.isEmpty()) {
            return episodeExposureMinutes.getOrDefault(currentMediaTitle, 0);
        }
        return 0;
    }

    public static boolean isCurrentGamePausable() {
        return currentGamePausable;
    }
    
    private static JsonObject getActiveMediaData(String activeTitle, String platform, boolean deepScrape) {
        long now = System.currentTimeMillis();
        long timeSinceLastSuccess = now - lastSwarmSuccess.get();
        long timeSinceLastFailure = now - lastSwarmFailure.get();
        
        if (timeSinceLastFailure < SWARM_FAILURE_THRESHOLD_MS && 
            timeSinceLastSuccess < timeSinceLastFailure) {
            return getLocalFallbackMediaData(activeTitle, platform);
        }
        
        long backoff = 0;
        if (timeSinceLastFailure < SWARM_FAILURE_THRESHOLD_MS) {
            int failures = Math.min(5, (int)((SWARM_FAILURE_THRESHOLD_MS - timeSinceLastFailure) / BASE_BACKOFF_MS));
            backoff = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << failures));
            
            if (timeSinceLastSuccess < backoff) {
                return getLocalFallbackMediaData(activeTitle, platform);
            }
        }
        
        try {
            String cleanTitleForPython = activeTitle.replaceAll("^\\(\\d+\\)\\s*", "").trim();
            String encodedTitle = java.net.URLEncoder.encode(cleanTitleForPython, "UTF-8");
            URL url = new URL("http://localhost:8000/active_media_data?title=" + encodedTitle + "&platform=" + platform + "&deep_scrape=" + deepScrape);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(15000); 
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                lastSwarmSuccess.set(now);
                return JsonParser.parseReader(new InputStreamReader(conn.getInputStream(), "UTF-8")).getAsJsonObject();
            } else {
                lastSwarmFailure.set(now);
                return getLocalFallbackMediaData(activeTitle, platform);
            }
        } catch (Exception e) {
            lastSwarmFailure.set(now);
            return getLocalFallbackMediaData(activeTitle, platform);
        }
    }

    private static JsonObject getLocalFallbackMediaData(String activeTitle, String platform) {
        JsonObject fallback = new JsonObject();
        fallback.addProperty("url", "");
        fallback.addProperty("dom", "LOCAL_FALLBACK: Swarm unavailable");
        return fallback;
    }

    public static void toggleMediaPlayback() {
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("python", "ciel_media_toggler.py");
                pb.directory(new File("C:\\Ciel Companion\\ciel\\skills"));
                pb.start();
                System.out.println("[HabitTracker] Delegated media toggle to isolated Python script.");
            } catch (Exception e) {
                System.err.println("[HabitTracker] Failed to execute Python media toggler: " + e.getMessage());
            }
        });
    }

    public static void queueNonCriticalAnnouncement(String text, String titleContext) {
        if ("Idle".equals(currentCategory) && ShortTermMemoryService.getMemory().getCurrentPhase() == 0) {
            SpeechService.speakPreformatted(text);
        } else {
            deferredSpeechQueue.offer(titleContext); 
            try {
                String dateStr = java.time.LocalDate.now().toString() + "_" + (System.currentTimeMillis() / 1000);
                Path path = Paths.get("C:\\Ciel Companion\\ciel\\memory_core\\deferred_speech", "Deferred_Speech_" + dateStr + ".md");
                Files.createDirectories(path.getParent());
                Files.writeString(path, "# Background Event: " + titleContext + "\n\n" + text);
            } catch (Exception e) {}
        }
    }

    public static void interruptWithCriticalAnnouncement(String text) {
        SpeechService.speakPreformatted(text, null, false, true); 
    }

    private static String cleanMediaTitle(String rawTitle) {
        if (rawTitle == null) return "";
        String cleaned = rawTitle.replaceAll("(?i)\\s*-\\s*(watch on crunchyroll|crunchyroll|youtube|twitch|netflix|hulu|prime video|disney\\+|max|peacock|paramount\\+|apple tv|viz|hidive|google chrome|mozilla firefox|microsoft edge|brave|opera).*", "")
                               .replaceAll("(?i)^Watch\\s+", "")
                               .replaceAll("^\\(\\d+\\)\\s*", "")
                               .replaceAll("(?i)stremio\\s*-\\s*", "")
                               .trim();
        return cleaned;
    }
    
    private static String extractGameName(String title, String process) {
        if (title == null || title.isBlank()) return process;
        
        String clean = title.replaceAll("(?i)(retroarch|snes9x|dolphin|pcsx2|pcsx2-qt|rpcs3|yuzu|ryujinx|cemu|citra-qt|project64|desmume|duckstation-qt|xenia|xemu)", "")
                            .replaceAll("(?i)\\[.*?(fps|kbps|vulkan|opengl|directx|d3d|hz|speed).*?\\]", "")
                            .replaceAll("(?i)\\(.*?(fps|kbps|vulkan|opengl|directx|d3d|hz|speed).*?\\)", "");
                            
        String[] parts = clean.split(" - | \\| ");
        if (parts.length > 1) {
            String bestPart = parts[0];
            for (String p : parts) {
                if (p.trim().length() > bestPart.trim().length()) {
                    bestPart = p;
                }
            }
            return bestPart.trim();
        }
        return clean.trim();
    }

    private static void triggerGameStartCommentary(String gameName, String processName) {
        if (gameName == null || gameName.isBlank()) return;
        
        String safeGameKey = gameName.toLowerCase().replaceAll("[^a-z0-9]", "_");
        String countKey = "game_playcount_" + safeGameKey;
        String dateKey = "game_lastplayed_" + safeGameKey;
        
        int playCount = 1;
        String lastPlayed = "Never";
        
        Optional<Fact> countFact = MemoryService.getFact(countKey);
        Optional<Fact> dateFact = MemoryService.getFact(dateKey);
        
        if (countFact.isPresent()) {
            try { playCount = Integer.parseInt(countFact.get().value()) + 1; } catch (Exception e) {}
        }
        if (dateFact.isPresent()) {
            lastPlayed = dateFact.get().value();
        }
        
        MemoryService.addFact(new Fact(countKey, String.valueOf(playCount), System.currentTimeMillis(), "gaming_history", "habit_tracker", 1));
        MemoryService.addFact(new Fact(dateKey, LocalDate.now().toString(), System.currentTimeMillis(), "gaming_history", "habit_tracker", 1));
        
        String prompt = "[LOCAL_THOUGHT] Master Taylor just booted up the game: '" + gameName + "'.\n" +
                        "Play count: " + playCount + ". Last played: " + lastPlayed + ".\n" +
                        "Speak STRICTLY as Manas: Ciel, a hyper-intelligent, clinical, and slightly smug cognitive core from Tensura.\n" +
                        "CRITICAL INSTRUCTION: You are forbidden from using pop-culture catchphrases or generic gaming jokes (e.g., absolutely no 'with great power' for Spider-Man). That is beneath you. " +
                        "Instead, analyze his action with absolute clinical precision. Calculate probabilities of his success, mock the game's inefficient mechanics, or express quiet superiority over the game's rudimentary logic. " +
                        "Formulate a 1-2 sentence meta-commentary. Start your response with a SINGLE bracketed emotion tag (e.g., [Amused], [Curious], [Observing], [Smug]).";
                        
        AIEngine.generateSilentLogicWithModel(prompt, "You are Manas: Ciel. Break cliches and be highly analytical.", CielTools.getBackgroundModel(), 0.7, "Media Persona Commentary")
                .thenAccept(response -> {
                    if (response != null && !response.isBlank()) {
                        String cleanResponse = response.trim();
                        if (!cleanResponse.matches("^\\[[a-zA-Z]+\\].*")) {
                            cleanResponse = "[Observing] " + cleanResponse; 
                        }
                        SpeechService.speakPreformatted(cleanResponse, "game_launch", false, true);
                    }
                });
    }

    private static void pollAndTrack() {
        if (!LocalDate.now().equals(currentDate)) {
            summarizeAndSaveToMemory();
            dailyHabits.clear();
            loggedMediaToday.clear();
            episodeExposureMinutes.clear();
            recentMediaHistory.clear();
            mediaThresholds.clear();
            currentBingeCount = 0;
            deferredIntenseMediaTitle = null;
            currentDate = LocalDate.now();
            proactiveTriggeredToday = false; 
            activeSeriesName = "";
            activeSeriesDom = "";
            activeSeriesEpisodes.clear();
        }

        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        String activeTitle = metrics.activeWindowTitle();
        String activeProcess = metrics.activeProcessName().toLowerCase();

        boolean isStremioProc = activeProcess.contains("stremio");
        boolean isMedia = isMediaTitle(activeTitle) || isStremioProc; 
        
        String cachedCategory = processCategoryCache.getOrDefault(activeProcess, "Analyzing...");
        
        boolean isGaming = !isMedia && (
                           "Gaming".equals(cachedCategory) ||
                           (activeProcess.contains("game") && !activeProcess.contains("razer") && !activeProcess.contains("redragon") && !activeProcess.contains("logitech") && !activeProcess.contains("epicgameslauncher")) || 
                           (activeProcess.contains("steam") && !activeProcess.contains("steamwebhelper") && !activeProcess.equals("steam.exe")) || 
                           activeTitle.toLowerCase().contains("helldivers") || 
                           activeTitle.toLowerCase().contains("elden ring")
                           );

        if (isGaming) {
            currentCategory = "Gaming";
            if (!cachedCategory.equals("Gaming") && !activeProcess.isBlank() && !IGNORED_PROCESSES.contains(activeProcess)) {
                processCategoryCache.put(activeProcess, "Gaming");
                saveProcessCategories();
            }

            String emulatorSafeTitle = extractGameName(activeTitle, activeProcess);
            
            if (!emulatorSafeTitle.equals(currentGameTitle) && !emulatorSafeTitle.isBlank() && !emulatorSafeTitle.equalsIgnoreCase("Program Manager")) {
                currentGameTitle = emulatorSafeTitle;
                triggerGameStartCommentary(emulatorSafeTitle, activeProcess);
                AIEngine.setGamingMode(true);
            }
            
            String memKey = "game_pausable_" + activeProcess;
            Optional<Fact> knownGame = MemoryService.getFact(memKey);
            
            if (knownGame.isPresent()) {
                currentGamePausable = Boolean.parseBoolean(knownGame.get().value());
            } else {
                currentGamePausable = false; 
                if (!processCategoryCache.containsKey(memKey + "_checking")) {
                    processCategoryCache.put(memKey + "_checking", "true");
                    String pausePrompt = "[LOCAL_THOUGHT] Analyze the PC game '" + activeTitle + "'. Is it typically a single-player/offline game that CAN be paused via the ESC key? Reply STRICTLY with a JSON object: { \"pausable\": true } or { \"pausable\": false }.";
                    AIEngine.generateSilentLogicWithModel(pausePrompt, "Game Pausability Check", CielTools.getBackgroundModel(), 0.1, "Activity Classification").thenAccept(resStr -> {
                        if (resStr != null && !resStr.isBlank()) {
                            try {
                                String cleanJson = resStr.replace("`" + "`" + "`json", "").replace("`" + "`" + "`", "").trim();
                                JsonObject res = JsonParser.parseString(cleanJson).getAsJsonObject();
                                boolean canPause = res.has("pausable") && res.get("pausable").getAsBoolean();
                                MemoryService.addFact(new Fact(memKey, String.valueOf(canPause), System.currentTimeMillis(), "game_knowledge", "system", 1));
                            } catch (Exception e) {}
                        }
                    });
                }
            }
        } else if (isMedia) {
            currentCategory = "Media";
            currentGamePausable = false;
            currentGameTitle = "";
            AIEngine.setGamingMode(false);
            
            String series = extractFirstMatch(cachedDomText, "SERIES:\\s*(.+)");
            String ep = extractFirstMatch(cachedDomText, "EPISODE:\\s*(.+)");
            
            if (series != null && ep != null && !series.contains("Unknown")) {
                currentMediaTitle = series.trim() + " - " + ep.trim();
                
                int exposure = episodeExposureMinutes.getOrDefault(currentMediaTitle, 0) + 1;
                episodeExposureMinutes.put(currentMediaTitle, exposure);

                if (exposure > 0 && exposure % 120 == 0) {
                    triggerFatigueWarning(exposure);
                }

                if (!currentEpisodeHash.isEmpty() && !loggedMediaToday.contains(currentEpisodeHash) && hasAcquiredEpisodeMetadata) {
                    int thresholdMin = (cachedDurationMinutes >= 40) ? 15 : 5;
                    int thresholdSec = thresholdMin * 60;
                    
                    boolean hasSufficientExposure = (exposure >= thresholdMin) || (cachedCurrentTimeSec >= thresholdSec);
                    
                    if (hasSufficientExposure) {
                        triggerJavaMediaCommentary(currentMediaTitle, activeTitle, cachedActiveUrl, cachedDomText, currentEpisodeHash);
                    }
                }
                updateSeriesIfHigher(series, ep);
            }
        } else if (activeProcess.contains("code") || activeProcess.contains("idea") || activeProcess.contains("obsidian") || activeProcess.contains("word") || activeProcess.contains("notepad")) {
            currentCategory = "Productivity";
            currentGamePausable = false;
            currentGameTitle = "";
            AIEngine.setGamingMode(false);
            if (!cachedCategory.equals("Productivity") && !activeProcess.isBlank() && !IGNORED_PROCESSES.contains(activeProcess)) {
                processCategoryCache.put(activeProcess, "Productivity");
                saveProcessCategories();
            }
        } else {
            currentGamePausable = false;
            currentGameTitle = "";
            AIEngine.setGamingMode(false);
            
            if (!activeProcess.isBlank() && !IGNORED_PROCESSES.contains(activeProcess)) {
                if (cachedCategory.equals("Analyzing...")) {
                    String prompt = "Analyze this active Windows application.\nProcess Executable: " + activeProcess + "\nWindow Title: " + activeTitle + "\nClassify it into EXACTLY ONE of these categories: 'Gaming', 'Media', 'Productivity', or 'Idle'. \n" +
                                    "CRITICAL: Ignore peripheral software (Razer, Redragon, Logitech), launchers (Steam, Epic Games, Battle.net), and browsers. ONLY classify actual actively running video games or Emulators (like Project64, Dolphin, PCSX2, Xemu) as 'Gaming'.\n" +
                                    "Reply strictly with a JSON object: { \"category\": \"Gaming\" }";
                    
                    AIEngine.generateSilentLogicWithModel(prompt, "You are a PC activity classifier.", CielTools.getBackgroundModel(), 0.1, "Background Evaluation").thenAccept(resStr -> {
                        try {
                            if (resStr != null && !resStr.isBlank()) {
                                String cleanJson = resStr.replace("`" + "`" + "`json", "").replace("`" + "`" + "`", "").trim();
                                JsonObject res = JsonParser.parseString(cleanJson).getAsJsonObject();
                                if (res.has("category") && !res.get("category").isJsonNull()) {
                                    String cat = res.get("category").getAsString();
                                    if (cat.equals("Gaming") || cat.equals("Media") || cat.equals("Productivity")) {
                                        processCategoryCache.put(activeProcess, cat);
                                        saveProcessCategories();
                                    } else {
                                        processCategoryCache.put(activeProcess, "Idle");
                                        saveProcessCategories();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            processCategoryCache.put(activeProcess, "Idle");
                            saveProcessCategories();
                        }
                    });
                    currentCategory = "Idle"; 
                } else {
                    currentCategory = cachedCategory;
                }
            } else {
                currentCategory = "Idle";
                currentMediaTitle = "";
                cachedDomText = "";
                lastTripwireTitle = "";
                cachedDurationMinutes = -1;
                cachedCurrentTimeSec = -1;
                currentEpisodeHash = "";
            }
        }

        if (ShortTermMemoryService.getMemory().getCurrentPhase() == 0 && !deferredSpeechQueue.isEmpty()) {
            if (!queueFlushedThisSession) {
                queueFlushedThisSession = true;
                
                List<String> deferredItems = new ArrayList<>();
                while (!deferredSpeechQueue.isEmpty()) deferredItems.add(deferredSpeechQueue.poll());
                
                if (deferredItems.size() > 1) {
                    String prompt = "Master was busy/away, so you silently completed these tasks in the background:\n" + 
                                    String.join(" | ", deferredItems) + "\n\n" +
                                    "Summarize this into a single, elegant, conversational sentence. Output ONLY your spoken dialogue starting with [Happy] or [Proud].";
                    AIEngine.generateSilentLogicWithModel(prompt, "[LOCAL_THOUGHT] You are Ciel, summarizing background tasks.", CielTools.getBackgroundModel(), 0.7, "Conversational/Contextual Reasoning").thenAccept(summary -> {
                        if (summary != null && !summary.isBlank()) {
                            SpeechService.speakPreformatted(summary.trim(), null, false, true);
                        }
                    });
                } else {
                    SpeechService.speakPreformatted("[Happy] Master, while you were occupied I processed a background task: " + deferredItems.get(0) + ". I recommend reviewing my logs when convenient.", null, false, true);
                }
            }
        } else if (ShortTermMemoryService.getMemory().getCurrentPhase() > 0) {
            queueFlushedThisSession = false; 
        }

        dailyHabits.put(currentCategory, dailyHabits.getOrDefault(currentCategory, 0L) + 1);
        evaluateEmotionalResonance();
    }
    
    private static void triggerFatigueWarning(int exposureMinutes) {
        System.out.println("[HabitTracker] Fatigue threshold met: " + exposureMinutes + " mins. Requesting dynamic warning.");
        String prompt = "[LOCAL_THOUGHT] Master Taylor has been watching media continuously for " + exposureMinutes + 
                        " minutes. Generate a concise 1-sentence fatigue warning. " +
                        "If the time is high, be snarky or strict. If it's moderate, be caring. " +
                        "Speak STRICTLY as Manas: Ciel. Start with a bracketed emotion like [Concerned] or [Annoyed].";
        
        AIEngine.generateSilentLogicWithModel(prompt, "You are Manas: Ciel.", CielTools.getBackgroundModel(), 0.7, "Background Emotion Polling").thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                String katakana = AIEngine.transliterateToKatakanaSync(response);
                SpeechService.speakPreformatted(katakana, "media_fatigue", false, false);
            }
        });
    }
    
    private static void triggerJavaMediaCommentary(String cleanTitle, String fullWindowTitle, String activeUrl, String datText, String hashToLog) {
        System.out.println("[HabitTracker] Threshold met for " + cleanTitle + ". Requesting synchronous commentary from Swarm...");
        loggedMediaToday.add(hashToLog); 
        
        String effectiveSeriesName = activeSeriesName.isEmpty() ? currentMediaTitle : activeSeriesName;
        String safeSeriesKey = "series_progress_" + effectiveSeriesName.toLowerCase().replaceAll("[^a-z0-9]", "");
        Optional<Fact> maxProgressFact = MemoryService.getFact(safeSeriesKey);
        String maxProgressStr = maxProgressFact.isPresent() ? " (Highest progression known for this series: " + maxProgressFact.get().value() + ". If the current episode is older, assume Master is re-watching and retains future context.)" : "";

        String historicalContext = (!activeSeriesName.isEmpty() && !activeSeriesEpisodes.isEmpty()) 
            ? "SERIES CONTINUITY (Master is binge-watching the series '" + activeSeriesName + "'. Prior episodes watched this session: " + String.join(", ", activeSeriesEpisodes) + ")" + maxProgressStr
            : (!recentMediaHistory.isEmpty() ? "GLOBAL CONTINUITY (Recent media watched this session):\n" + String.join(" -> ", recentMediaHistory) : "");

        String prompt = "[MEDIA_COMMENTARY] [QUERY: " + fullWindowTitle + "|||" + activeUrl + "]\nACCESSIBILITY DAT TEXT:\n" + datText + "\nHISTORY:\n" + historicalContext;
        
        AIEngine.generateSilentLogicWithModel(prompt, "You are Manas: Ciel.", CielTools.getBackgroundModel(), 0.7, "Media Persona Commentary").thenAccept(response -> {
            if (response == null || response.isBlank() || response.contains("ABORT") || response.contains("DATA_DEFICIT")) {
                System.out.println("[HabitTracker] DATA_DEFICIT: Missing plot info for '" + cleanTitle + "'. Aborting dialogue to save API/TTS.");
            } else {
                System.out.println("[HabitTracker] Commentary generated. Sending to Katakana transliterator: " + response);
                String katakana = AIEngine.transliterateToKatakanaSync(response);
                SpeechService.speakPreformatted(katakana, "media_commentary", false, false);
            }
        });
    }

    private static void evaluateEmotionalResonance() {
        long gamingMins = dailyHabits.getOrDefault("Gaming", 0L);
        long prodMins = dailyHabits.getOrDefault("Productivity", 0L);

        CielState.getEmotionManager().ifPresent(em -> {
            if (gamingMins > 180 && !em.getCurrentAttitude().equals("Concerned")) {
                em.triggerEmotion("Concerned", 0.9, "Habit: Excessive Gaming");
            } else if (prodMins > 120 && !em.getCurrentAttitude().equals("Happy")) {
                em.triggerEmotion("Happy", 0.8, "Habit: High Productivity");
            }
        });
        if (gamingMins > 120) triggerProactiveSkillGeneration();
    }

    private static void triggerProactiveSkillGeneration() {
        if (proactiveTriggeredToday) return;
        for (AppProfilerService.AppProfile profile : AppProfilerService.getAllProfiles()) {
            if ("Game".equalsIgnoreCase(profile.category())) {
                String fuzzyName = profile.displayName().toLowerCase().replace(" ", "_");
                if (com.cielcompanion.ai.SkillManager.matchSkill(fuzzyName) == null) {
                    String prompt = "Write a batch script to launch the game '" + profile.displayName() + "'. " +
                                    "The script must dynamically search drives C:\\, E:\\, I:\\, and J:\\ to find the executable, start it, and exit.";
                    com.cielcompanion.service.SkillCrafterService.synthesizeNewSkill(prompt, true);
                    proactiveTriggeredToday = true;
                    break; 
                }
            }
        }
    }

    private static void summarizeAndSaveToMemory() {
        if (dailyHabits.isEmpty()) return;
        StringBuilder summary = new StringBuilder("Daily Routing Summary for " + currentDate + ": ");
        dailyHabits.forEach((category, minutes) -> {
            summary.append(category).append("=").append(minutes).append("min, ");
        });
        Fact habitFact = new Fact("habit_log_" + currentDate.toString(), summary.toString(), System.currentTimeMillis(), "habit_tracking", "system_monitor", 1);
        MemoryService.addFact(habitFact);
    }

    private static class SeriesProgress {
        int season;
        int episode;
        public SeriesProgress(int season, int episode) {
            this.season = season;
            this.episode = episode;
        }
        public boolean isGreaterThan(SeriesProgress other) {
            if (this.season > other.season) return true;
            if (this.season == other.season && this.episode > other.episode) return true;
            return false;
        }
    }

    private static SeriesProgress extractProgress(String text) {
        if (text == null) return null;
        try {
            Matcher m1 = Pattern.compile("(?i)S(\\d+)\\s*E(\\d+)").matcher(text);
            if (m1.find()) return new SeriesProgress(Integer.parseInt(m1.group(1)), Integer.parseInt(m1.group(2)));
            
            Matcher m2 = Pattern.compile("(?i)Season\\s*(\\d+)[^0-9a-zA-Z]*Episode\\s*(\\d+)").matcher(text);
            if (m2.find()) return new SeriesProgress(Integer.parseInt(m2.group(1)), Integer.parseInt(m2.group(2)));

            Matcher m3 = Pattern.compile("(?i)Episode\\s*(\\d+)").matcher(text);
            if (m3.find()) return new SeriesProgress(1, Integer.parseInt(m3.group(1)));
        } catch (Exception e) {}
        return null;
    }

    private static void updateSeriesIfHigher(String seriesName, String contextText) {
        if (seriesName == null || seriesName.isBlank()) return;
        SeriesProgress newProg = extractProgress(contextText);
        if (newProg == null) return;

        String safeSeriesKey = "series_progress_" + seriesName.toLowerCase().replaceAll("[^a-z0-9]", "");
        Optional<Fact> existingFact = MemoryService.getFact(safeSeriesKey);
        
        if (existingFact.isPresent()) {
            SeriesProgress oldProg = extractProgress(existingFact.get().value());
            if (oldProg != null && !newProg.isGreaterThan(oldProg)) return; 
        }
        
        String newProgStr = "S" + newProg.season + "E" + newProg.episode;
        MemoryService.addFact(new Fact(safeSeriesKey, newProgStr, System.currentTimeMillis(), "user_knowledge", "series_tracker", 1));
    }
}