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

/**
 * Tracks user habits (gaming, media, productivity, idle) and
 * determines whether the current foreground window counts as “media”
 * based on an editable whitelist that can be updated at runtime.
 */
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
    private static long lastStremioScrapeTime = 0; 
    
    private static String cachedStreamLink = null;
    private static String cachedMagnetLink = null;
    private static int cachedDurationMinutes = -1;

    private static final LinkedList<String> recentMediaHistory = new LinkedList<>();
    private static int currentBingeCount = 0;
    
    private static String deferredIntenseMediaTitle = null;
    
    private static String activeSeriesName = "";
    private static String activeSeriesDom = "";
    private static final List<String> activeSeriesEpisodes = new ArrayList<>();

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
                    "retroarch.exe", "snes9x.exe", "dolphin.exe", "pcsx2.exe", "pcsx2-qt.exe", "rpcs3.exe", 
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
        // Runs every 60 seconds to increment exposure time
        habitScheduler.scheduleWithFixedDelay(HabitTrackerService::pollAndTrack, 2, 60, TimeUnit.SECONDS);
        // Runs every 3 seconds to catch fast scene changes in Stremio
        tripwireScheduler.scheduleWithFixedDelay(HabitTrackerService::tripwireCheck, 2, 3, TimeUnit.SECONDS);
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
        
        final boolean titleChanged = !activeTitle.equals(lastTripwireTitle) && !isStremio;
        
        long stremioRefreshInterval = 3000; 
        final boolean stremioNeedsRefresh = isStremio && (System.currentTimeMillis() - lastStremioScrapeTime > stremioRefreshInterval); 
        
        boolean needsDomRefresh = isMedia && (cachedDomText == null || cachedDomText.isEmpty() || cachedDomText.contains("Bypassed") || stremioNeedsRefresh) && consecutiveDomFailures < 3;
        
        if (isMedia && (titleChanged || needsDomRefresh)) {
            if (titleChanged) {
                consecutiveDomFailures = 0;
            } else if (stremioNeedsRefresh) {
                lastStremioScrapeTime = System.currentTimeMillis();
                consecutiveDomFailures = 0;
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

            String currentPlatform = extractPlatform(activeTitle, activeProcess);

            if (!currentPlatform.equals(lastTripwirePlatform) || !isSameScene) {
                cachedDomText = "";
                cachedActiveUrl = "";
                cachedStreamLink = null;
                cachedMagnetLink = null;
            }
            
            lastTripwireTitle = activeTitle;
            lastTripwirePlatform = currentPlatform;
            
            CompletableFuture.runAsync(() -> {
                try {
                    boolean securedRichDom = false;
                    JsonObject mediaData = null;
                    
                    if (isSameScene && cachedDomText.length() > 50 && !stremioNeedsRefresh) {
                        mediaData = getActiveMediaData(activeTitle, currentPlatform);
                        if (mediaData != null && mediaData.has("dom") && !mediaData.get("dom").isJsonNull()) {
                            securedRichDom = true;
                        }
                    }

                    int attempts = 0;
                    while (!securedRichDom && attempts < 3 && activeTitle.equals(lastTripwireTitle)) {
                        mediaData = getActiveMediaData(activeTitle, currentPlatform);
                        
                        if (mediaData != null) {
                            if (mediaData.has("url") && !mediaData.get("url").isJsonNull()) {
                                String newUrl = mediaData.get("url").getAsString();
                                if (!newUrl.isEmpty() && !newUrl.equals(cachedActiveUrl)) {
                                    cachedActiveUrl = newUrl;
                                }
                            }
                            
                            if (mediaData.has("dom") && !mediaData.get("dom").isJsonNull()) {
                                String newDom = mediaData.get("dom").getAsString();
                                
                                if ("NO_CHANGE".equals(newDom)) {
                                    securedRichDom = true;
                                    break;
                                }
                                
                                if (newDom != null && newDom.length() > 50) {
                                    if (newDom.contains("SERIES: Unknown") && cachedDomText != null && !cachedDomText.isEmpty() && !cachedDomText.contains("SERIES: Unknown")) {
                                        // Ignore blanking dom if we have cached data
                                    } else {
                                        cachedDomText = newDom;
                                        if (isShowPlatform) activeSeriesDom = newDom; 
                                        
                                        cachedStreamLink = extractFirstMatch(newDom, "STREAM_LINK:\\s*([^\\s]+)");
                                        cachedMagnetLink = extractFirstMatch(newDom, "MAGNET_LINK:\\s*([^\\s]+)");
                                        
                                        String durMatch = extractFirstMatch(newDom, "DURATION_MINUTES:\\s*(\\d+)");
                                        if (durMatch != null) {
                                            try { cachedDurationMinutes = Integer.parseInt(durMatch); } catch (Exception e) {}
                                        }
                                    }
                                    securedRichDom = true;
                                    break;
                                }
                            }
                        }
                        attempts++;
                        if (!securedRichDom) Thread.sleep(2000); 
                    }
                    
                    if (securedRichDom) {
                        consecutiveDomFailures = 0;
                        if (!cachedDomText.equals(lastLoggedDom)) {
                            lastLoggedDom = cachedDomText;
                        }
                    } else {
                        if (!titleChanged) consecutiveDomFailures++;
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
    
    private static JsonObject getActiveMediaData(String activeTitle, String platform) {
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
            URL url = new URL("http://localhost:8000/active_media_data?title=" + encodedTitle + "&platform=" + platform);
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

    public static void toggleMediaPlayback() {
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL("http://localhost:8000/toggle_media");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.getResponseCode(); 
                System.out.println("[HabitTracker] Delegated media toggle to Python Swarm via HTTP.");
            } catch (Exception e) {
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
                        
        AIEngine.generateSilentLogicWithModel(prompt, "You are Manas: Ciel. Break cliches and be highly analytical.", CielTools.getBackgroundModel(), 0.7)
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
                    AIEngine.generateSilentLogicWithModel(pausePrompt, "Game Pausability Check", CielTools.getBackgroundModel(), 0.1).thenAccept(resStr -> {
                        if (resStr != null && !resStr.isBlank()) {
                            try {
                                JsonObject res = JsonParser.parseString(resStr.replace("\n```json", "").replace("```", "").trim()).getAsJsonObject();
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
        } else if (activeProcess.contains("code") || activeProcess.contains("idea") || activeProcess.contains("obsidian") || activeProcess.contains("word") || activeProcess.contains("notepad")) {
            currentCategory = "Productivity";
            currentGamePausable = false;
            currentGameTitle = "";
            if (!cachedCategory.equals("Productivity") && !activeProcess.isBlank() && !IGNORED_PROCESSES.contains(activeProcess)) {
                processCategoryCache.put(activeProcess, "Productivity");
                saveProcessCategories();
            }
        } else {
            currentGamePausable = false;
            currentGameTitle = "";
            
            if (!activeProcess.isBlank() && !IGNORED_PROCESSES.contains(activeProcess)) {
                if (cachedCategory.equals("Analyzing...")) {
                    String prompt = "Analyze this active Windows application.\nProcess Executable: " + activeProcess + "\nWindow Title: " + activeTitle + "\nClassify it into EXACTLY ONE of these categories: 'Gaming', 'Media', 'Productivity', or 'Idle'. \n" +
                                    "CRITICAL: Ignore peripheral software (Razer, Redragon, Logitech), launchers (Steam, Epic Games, Battle.net), and browsers. ONLY classify actual actively running video games or Emulators (like Project64, Dolphin, PCSX2, Xemu) as 'Gaming'.\n" +
                                    "Reply strictly with a JSON object: { \"category\": \"Gaming\" }";
                    
                    AIEngine.generateSilentLogicWithModel(prompt, "You are a PC activity classifier.", CielTools.getBackgroundModel(), 0.1).thenAccept(resStr -> {
                        try {
                            if (resStr != null && !resStr.isBlank()) {
                                JsonObject res = JsonParser.parseString(resStr.replace("\n```json", "").replace("```", "").trim()).getAsJsonObject();
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
                    AIEngine.generateSilentLogicWithModel(prompt, "[LOCAL_THOUGHT] You are Ciel, summarizing background tasks.", CielTools.getBackgroundModel(), 0.7).thenAccept(summary -> {
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

        if (currentCategory.equals("Media")) {
            String cleanTitle = cleanMediaTitle(activeTitle);
            
            if ((activeTitle.toLowerCase().contains("stremio") || isStremioProc) && cachedDomText != null && !cachedDomText.isEmpty()) {
                String datSeries = "";
                String datEpisode = "";
                Matcher sMatcher = Pattern.compile("(?m)^SERIES:\\s*(.+)").matcher(cachedDomText);
                Matcher eMatcher = Pattern.compile("(?m)^EPISODE:\\s*(.+)").matcher(cachedDomText);
                
                if (sMatcher.find()) datSeries = sMatcher.group(1).trim();
                if (eMatcher.find()) datEpisode = eMatcher.group(1).trim();

                if (cachedStreamLink != null) {
                    Matcher epNum = Pattern.compile("/(\\d+)[\\?/]").matcher(cachedStreamLink);
                    if (epNum.find()) {
                        datEpisode = "Episode " + epNum.group(1); 
                    }
                }
                
                if (datSeries.isEmpty() && cachedDomText.contains("ON-SCREEN UI TEXT")) {
                    String[] lines = cachedDomText.split("\\r?\\n");
                    boolean uiTextFound = false;
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.equals("--- ON-SCREEN UI TEXT ---")) {
                            uiTextFound = true;
                            continue;
                        }
                        if (uiTextFound) {
                            if (!trimmed.isEmpty() 
                                && !trimmed.equalsIgnoreCase("Untitled") 
                                && !trimmed.equalsIgnoreCase("Open in split screen")
                                && !trimmed.equalsIgnoreCase("Dismiss")
                                && !trimmed.equalsIgnoreCase("Watch now")
                                && !trimmed.equalsIgnoreCase("Next on")
                                && !trimmed.matches(".*\\d{1,2}:\\d{2}(:\\d{2})?.*")) { 
                                
                                datSeries = trimmed;
                                break;
                            }
                        }
                    }
                }
                
                if (!datSeries.isEmpty() && !datSeries.equalsIgnoreCase("Stremio") && !datSeries.equalsIgnoreCase("Unknown")) {
                    cleanTitle = datSeries + (datEpisode.isEmpty() ? "" : " - " + datEpisode);
                } else if (!activeSeriesName.isEmpty() && !activeSeriesName.equalsIgnoreCase("Stremio")) {
                    cleanTitle = activeSeriesName;
                }
            }
            
            if (cleanTitle.isBlank()) {
                cleanTitle = "Unknown Media";
            }

            int exposure = episodeExposureMinutes.getOrDefault(cleanTitle, 0) + 1;
            episodeExposureMinutes.put(cleanTitle, exposure);
            currentMediaTitle = cleanTitle;

            int threshold = 10;
            if (cachedDurationMinutes > 0) {
                if (cachedDurationMinutes > 90) threshold = 30;
                else if (cachedDurationMinutes > 40) threshold = 15;
                else threshold = 5;
            }

            boolean isLongBinge = (exposure > 0 && exposure % 120 == 0);
            boolean alreadyCommented = loggedMediaToday.contains(cleanTitle);
            
            boolean hasSufficientExposure = (exposure >= threshold || isLongBinge);

            if (!alreadyCommented && hasSufficientExposure) {
                loggedMediaToday.add(cleanTitle); 
                
                String bgModel = CielTools.getBackgroundModel();
                triggerConfidentMediaCommentary(cleanTitle, activeTitle, cachedActiveUrl, cachedDomText, currentBingeCount, null, bgModel, isLongBinge);
            }

            if (!activeSeriesName.isEmpty() && !activeSeriesEpisodes.contains(currentMediaTitle)) {
                activeSeriesEpisodes.add(currentMediaTitle);
            }
            
        } else {
            if (deferredIntenseMediaTitle != null && !currentCategory.equals("Idle")) {
                String bgModel = CielTools.getBackgroundModel();
                triggerConfidentMediaCommentary(currentMediaTitle, activeTitle, cachedActiveUrl, cachedDomText, currentBingeCount, deferredIntenseMediaTitle, bgModel, false);
                deferredIntenseMediaTitle = null;
            }
            
            currentMediaTitle = "";
            currentBingeCount = 0;
            cachedActiveUrl = "";
            cachedDomText = "";
            lastTripwireTitle = "";
            lastTripwirePlatform = "";
            consecutiveDomFailures = 0;
            cachedDurationMinutes = -1;
        }

        dailyHabits.put(currentCategory, dailyHabits.getOrDefault(currentCategory, 0L) + 1);
        evaluateEmotionalResonance();
    }

    private static void triggerConfidentMediaCommentary(String cleanTitle, String fullWindowTitle, String activeUrl, String datText, int bingeCount, String previousIntenseTitle, String modelToUse, boolean isFatigueWarning) {
        String query = cleanTitle;
        String instruction = "";

        String datContext = (datText != null && !datText.isBlank() && !datText.contains("Bypassed")) ? "\n\nACCESSIBILITY DAT TEXT:\n" + datText : "";
        
        String effectiveSeriesName = activeSeriesName.isEmpty() ? cleanTitle : activeSeriesName;
        String safeSeriesKey = "series_progress_" + effectiveSeriesName.toLowerCase().replaceAll("[^a-z0-9]", "");
        Optional<Fact> maxProgressFact = MemoryService.getFact(safeSeriesKey);
        String maxProgressStr = maxProgressFact.isPresent() ? " (Highest progression known for this series: " + maxProgressFact.get().value() + ". If the current episode is older, assume Master is re-watching and retains future context.)" : "";

        String historicalContext = (!activeSeriesName.isEmpty() && !activeSeriesEpisodes.isEmpty()) 
            ? "\n\nSERIES CONTINUITY (Master is binge-watching the series '" + activeSeriesName + "'. Prior episodes watched this session: " + String.join(", ", activeSeriesEpisodes) + ")" + maxProgressStr
            : (!recentMediaHistory.isEmpty() ? "\n\nGLOBAL CONTINUITY (Recent media watched this session):\n" + String.join(" -> ", recentMediaHistory) : "");
            
        String bingeContext = bingeCount > 1 ? "\n\nBINGE STATUS: Master has watched " + bingeCount + " consecutive media items in this sitting. CRITICAL WARNING: Do NOT mistake this 'session watch count' for the absolute episode number of the series. Rely strictly on the DAT TEXT or WEB DATA for the actual Season and Episode numbers." : "";
        
        String fatigueContext = isFatigueWarning ? "\n\nFATIGUE WARNING: Master Taylor has been staring at the screen for over TWO HOURS uninterrupted. You MUST seamlessly integrate a brief, caring (or playfully scolding) suggestion to rest, stretch, or hydrate into your commentary about the episode's events. Relate the plot's pacing or the characters' struggles to his own need for a break." : "";

        if (activeUrl != null && !activeUrl.isBlank() && activeUrl.startsWith("http")) {
            query = query + "|||" + activeUrl;
        }

        instruction = "1. Read the provided WEB DATA, EPISODE CONTINUITY, and DAT TEXT.\n" +
                      "2. The Master is watching a show. The exact title might be hidden in the window title, but it WILL be visible in the ON-SCREEN UI TEXT below.\n" +
                      "3. Analyze the ON-SCREEN UI TEXT to figure out exactly what show and episode he is watching, and deduce the plot.\n" +
                      "4. Speak STRICTLY as Manas: Ciel, a hyper-intelligent, clinical, and slightly smug cognitive core from Tensura.\n" +
                      "5. CRITICAL ANTI-HALLUCINATION: Do NOT use generic television tropes, puns, or pop-culture jokes. That is beneath you. Instead, frame the characters' actions as logical formulas, calculate probabilities, or analyze their inefficient decisions with absolute intellectual superiority.\n" +
                      "6. Keep it EXTREMELY concise and punchy (exactly 1 or 2 short sentences).\n" +
                      "7. If you are completely unsure and the data is missing, output EXACTLY: ABORT.";

        String prompt = "[WEB_SEARCH] [COMMENTARY DIRECTIVE] [QUERY: " + query + "] " +
                "Master Taylor is watching media. The window title of his active screen is: '" + fullWindowTitle + "'.\n\n" +
                instruction + historicalContext + bingeContext + fatigueContext + datContext + "\n" +
                "Output ONLY your spoken dialogue starting with a bracketed emotion tag like [Amused], [Curious], or [Observing]. If deferring, output ONLY: DEFER. If aborting, output ONLY: ABORT.";

        AIEngine.generateSilentLogicWithModel(prompt, "You are Manas: Ciel. Break cliches and be highly analytical.", modelToUse, 0.3)
                .thenAccept(response -> {
                    if (response != null && !response.isBlank()) {
                        String cleanResponse = response.trim();
                        
                        if (cleanResponse.equals("ABORT") || cleanResponse.contains("ABORT")) {
                            System.out.println("[HabitTracker] Media commentary aborted safely.");
                        } else if (cleanResponse.equals("DEFER") || cleanResponse.contains("DEFER")) {
                            deferredIntenseMediaTitle = cleanTitle;
                        } else {
                            if (!cleanResponse.matches("^\\[[a-zA-Z]+\\].*")) {
                                cleanResponse = "[Observing] " + cleanResponse; 
                            }
                            SpeechService.speakPreformatted(cleanResponse, "media_commentary", false, false);
                        }
                    } 
                })
                .exceptionally(e -> {
                    System.err.println("[HabitTracker] Swarm failed to generate media commentary for " + cleanTitle + ". Suppressing TTS fallback.");
                    return null;
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

    private static JsonObject getLocalFallbackMediaData(String activeTitle, String platform) {
        JsonObject fallback = new JsonObject();
        fallback.addProperty("url", "");
        fallback.addProperty("dom", "LOCAL_FALLBACK: Swarm unavailable");
        return fallback;
    }
}