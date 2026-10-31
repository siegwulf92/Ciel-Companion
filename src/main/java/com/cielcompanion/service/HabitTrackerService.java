package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.Emotion;
import com.cielcompanion.mood.MoodConfig;
import com.cielcompanion.ui.CielGui;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.util.CielTools;
import com.cielcompanion.util.ProjectPaths;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HabitTrackerService {

    private static ScheduledExecutorService habitScheduler;
    private static ScheduledExecutorService tripwireScheduler;

    private static final Map<String, Long> dailyHabits = new ConcurrentHashMap<>();
    private static volatile String currentCategory = "Idle";
    private static volatile LocalDate currentDate = LocalDate.now();

    private static volatile boolean proactiveTriggeredToday = false;
    private static volatile boolean queueFlushedThisSession = true;

    private static final Map<String, String> processCategoryCache = new ConcurrentHashMap<>();
    private static final Path PROCESS_CACHE_PATH = ProjectPaths.ciel().resolve("process_categories.json");

    private static final Set<String> IGNORED_PROCESSES = Set.of(
            "chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe", 
            "explorer.exe", "idle", "discord.exe", "cmd.exe", "powershell.exe", "pwsh.exe", 
            "conhost.exe", "applicationframehost.exe", "razerappengine.exe", "razer central.exe", 
            "razer synapse.exe", "redragon.exe", "lghub.exe", "steamwebhelper.exe", "steam.exe",
            "epicgameslauncher.exe", "battle.net.exe"
    );

    private static final Path MEDIA_LIST_PATH = ProjectPaths.ciel().resolve("media_whitelist.txt");
    private static final Set<String> MEDIA_KEYWORDS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> mediaThresholds = new ConcurrentHashMap<>();

    private static volatile String currentMediaTitle = "";
    private static volatile String currentGameTitle = "";
    
    private static final Map<String, Integer> episodeExposureMinutes = new ConcurrentHashMap<>();
    private static final Set<String> loggedMediaToday = ConcurrentHashMap.newKeySet();
    private static final Set<String> unverifiedMediaAcknowledgedToday = ConcurrentHashMap.newKeySet();
    /** One spoiler-safe recognition line per identified media item, even while paused. */
    private static final Set<String> mediaRecognitionAcknowledgedToday = ConcurrentHashMap.newKeySet();
    private static final Set<String> mediaCommentaryInFlight = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> mediaCommentaryRetryAfter = new ConcurrentHashMap<>();

    private static final Queue<String> deferredSpeechQueue = new ConcurrentLinkedQueue<>();
    private static volatile boolean currentGamePausable = false;

    private static volatile String cachedActiveUrl = "";
    private static volatile String cachedDomText = "";
    private static volatile String lastTripwireTitle = "";
    private static volatile String lastTripwirePlatform = "";
    private static volatile int consecutiveDomFailures = 0;
    
    private static volatile String cachedStreamLink = null;
    private static volatile String cachedMagnetLink = null;
    private static volatile int cachedDurationMinutes = -1;
    private static volatile int cachedCurrentTimeSec = -1;

    private static volatile long stremioNextDeepScrapeTime = 0;
    private static volatile String cachedPlaybackState = "UNCONFIRMED";
    private static volatile long lastPlaybackEvidenceAt = 0;
    private static volatile long lastMediaDeferredLogAt = 0;
    
    private static volatile long lastExceptionLogTime = 0;
    private static volatile String lastExceptionMessage = "";

    private static final ConcurrentLinkedDeque<String> recentMediaHistory = new ConcurrentLinkedDeque<>();
    private static volatile int currentBingeCount = 0;
    
    private static volatile String activeSeriesName = "";
    private static volatile String activeSeriesDom = "";
    private static final List<String> activeSeriesEpisodes = new CopyOnWriteArrayList<>();
    
    private static volatile String currentEpisodeHash = "";
    private static volatile boolean hasAcquiredEpisodeMetadata = false;

    private static final AtomicBoolean isTripwireActive = new AtomicBoolean(false);

    private static String extractFirstMatch(String text, String regex) {
        if (text == null) return null;
        text = text.split("--- ON-SCREEN UI TEXT ---", 2)[0];
        // Metadata fields are single lines. \s must not consume the next field.
        regex = regex.replace("\\s*", "[ \t]*").replace("\\s+", "[ \t]+");
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        String value = m.find() ? m.group(1) : null;
        return value == null || value.isBlank() ? null : value;
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

    private static synchronized void saveProcessCategories() {
        try {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, String> entry : processCategoryCache.entrySet()) {
                if (!entry.getValue().equals("Analyzing...")) {
                    json.addProperty(entry.getKey(), entry.getValue());
                }
            }
            Files.createDirectories(PROCESS_CACHE_PATH.getParent());
            Path temporary = PROCESS_CACHE_PATH.resolveSibling(PROCESS_CACHE_PATH.getFileName() + ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, PROCESS_CACHE_PATH, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, PROCESS_CACHE_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
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
            MEDIA_KEYWORDS.addAll(Arrays.asList("youtube", "netflix", "hulu", "disney+", "disneyplus", "prime video", "primevideo", "crunchyroll", "hidive", "stremio", "twitch", "max", "hbo max", "peacock", "paramount+", "paramountplus", "apple tv", "tubi"));
        }
    }

    private static synchronized void saveMediaList() {
        try {
            Files.createDirectories(MEDIA_LIST_PATH.getParent());
            List<String> lines = new ArrayList<>(MEDIA_KEYWORDS);
            Collections.sort(lines);
            Path temporary = MEDIA_LIST_PATH.resolveSibling(MEDIA_LIST_PATH.getFileName() + ".tmp");
            Files.write(temporary, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, MEDIA_LIST_PATH, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, MEDIA_LIST_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
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
        if (lower.contains("disney")) return "disneyplus";
        if (lower.contains("prime video") || lower.contains("primevideo")) return "prime video";
        if (lower.contains("viz")) return "viz";
        if (lower.contains("hidive")) return "hidive";
        if (lower.contains("twitch")) return "twitch";
        if (lower.contains("paramount")) return "paramountplus";
        if (lower.contains("peacock")) return "peacock";
        if (lower.contains("apple tv")) return "appletv";
        if (lower.contains("tubi")) return "tubi";
        if (lower.contains("max") || lower.contains("hbo max")) return "max";
        return "unknown";
    }

    private static synchronized void tripwireCheck() {
        if (!isTripwireActive.compareAndSet(false, true)) return;

        try {
            SystemMetrics metrics = SystemMonitor.getSystemMetrics();
            final String activeTitle = metrics.activeWindowTitle();
            final String activeProcess = metrics.activeProcessName().toLowerCase();
            
            if (activeTitle == null || activeTitle.isBlank() || activeTitle.equals("Program Manager")) {
                isTripwireActive.set(false);
                return;
            }
            
            final boolean isStremioProc = activeProcess.contains("stremio");
            final boolean isStremio = isStremioProc || activeTitle.toLowerCase().contains("stremio");
            
            // Browser streaming pages are media too.  The Python media skill
            // validates the active URL before creating an episode identity,
            // so a normal search/new-tab caption cannot trigger commentary.
            // Keeping the foreground-title gate avoids classifying a hidden
            // browser tab or launcher as the active show.
            final boolean isMedia = isStremioProc || isMediaTitle(activeTitle);
            
            // IF SpeechService is actively outputting, suppress new media DOM walks to prevent API loop collisions
            if (com.cielcompanion.service.SpeechService.isActivelySpeaking()) {
                isTripwireActive.set(false);
                return;
            }
            
            boolean titleChanged = !activeTitle.equals(lastTripwireTitle);
            long now = System.currentTimeMillis();
            
            if (isMedia) {
                if (titleChanged) {
                    consecutiveDomFailures = 0;
                    hasAcquiredEpisodeMetadata = false;
                    currentEpisodeHash = "";
                    currentMediaTitle = "";
                    cachedDomText = "";
                    cachedActiveUrl = "";
                    cachedStreamLink = null;
                    cachedMagnetLink = null;
                    cachedDurationMinutes = -1;
                    cachedCurrentTimeSec = -1;
                    cachedPlaybackState = "UNCONFIRMED";
                    lastPlaybackEvidenceAt = 0;
                    stremioNextDeepScrapeTime = now;
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

                if (isShowPlatform && !isSameScene && !hasAcquiredEpisodeMetadata) {
                    activeSeriesName = newSeriesName;
                    activeSeriesDom = "";
                    activeSeriesEpisodes.clear();
                } else if (!isShowPlatform) {
                    activeSeriesName = "";
                    activeSeriesDom = "";
                    activeSeriesEpisodes.clear();
                }

                final String currentPlatform = extractPlatform(activeTitle, activeProcess);

                // A generic window caption is not evidence of a different episode.
                if (!currentPlatform.equals(lastTripwirePlatform)) {
                    cachedDomText = "";
                    cachedActiveUrl = "";
                    cachedStreamLink = null;
                    cachedMagnetLink = null;
                }
                
                lastTripwireTitle = activeTitle;
                lastTripwirePlatform = currentPlatform;
                final boolean doDeepScrape = now >= stremioNextDeepScrapeTime;
                if (doDeepScrape) stremioNextDeepScrapeTime = now + 60_000;
                
                CompletableFuture.runAsync(() -> {
                    synchronized (HabitTrackerService.class) {
                    try {
                        if(doDeepScrape) {
                            JsonObject identity=getActiveMediaData(activeTitle,currentPlatform,true,true);
                            if(identity!=null && identity.has("dom")) ViewingProgress.observe(identity.get("dom").getAsString(),
                                identity.has("playback_state")?identity.get("playback_state").getAsString():"UNCONFIRMED",
                                identity.has("current_time_sec")?identity.get("current_time_sec").getAsInt():-1,
                                identity.has("duration_minutes")?identity.get("duration_minutes").getAsInt():-1,System.currentTimeMillis());
                        }
                        JsonObject mediaData = getActiveMediaData(activeTitle, currentPlatform, doDeepScrape);
                        if (mediaData != null) {
                            long observedAt = System.currentTimeMillis();
                            String previousPlaybackState = cachedPlaybackState;
                            cachedPlaybackState = mediaData.has("playback_state")
                                    ? mediaData.get("playback_state").getAsString() : "UNCONFIRMED";
                            if ("PLAYING".equals(cachedPlaybackState)) lastPlaybackEvidenceAt = observedAt;
                            if(mediaData.has("dom")) {
                                ViewingProgress.observe(mediaData.get("dom").getAsString(),cachedPlaybackState,
                                    mediaData.has("current_time_sec")?mediaData.get("current_time_sec").getAsInt():-1,
                                    mediaData.has("duration_minutes")?mediaData.get("duration_minutes").getAsInt():-1,observedAt);
                            }
                            if (mediaData.has("diagnostic_code") && !mediaData.get("diagnostic_code").getAsString().isBlank()
                                    && observedAt - lastMediaDeferredLogAt > 60_000) {
                                System.out.println("[HabitTracker] Media recovery: " + mediaData.get("diagnostic_code").getAsString()
                                        + "; playback=" + cachedPlaybackState + "; keeping verified identity when available.");
                                lastMediaDeferredLogAt = observedAt;
                            }
                            if (!cachedPlaybackState.equals(previousPlaybackState)
                                    && !"PLAYING".equals(cachedPlaybackState)) stremioNextDeepScrapeTime = 0;
                            
                            if (mediaData.has("url") && !mediaData.get("url").isJsonNull()) {
                                cachedActiveUrl = mediaData.get("url").getAsString();
                            }
                            
                            if (mediaData.has("current_time_sec") && !mediaData.get("current_time_sec").isJsonNull()) {
                                int newTime = mediaData.get("current_time_sec").getAsInt();
                                if (newTime >= 0) {
                                    if (cachedCurrentTimeSec > 60 && newTime < cachedCurrentTimeSec - 60) {
                                        hasAcquiredEpisodeMetadata = false;
                                        stremioNextDeepScrapeTime = now;
                                    }
                                    if (newTime > cachedCurrentTimeSec && cachedCurrentTimeSec >= 0
                                            && !"PAUSED".equals(cachedPlaybackState)) lastPlaybackEvidenceAt = observedAt;
                                    cachedCurrentTimeSec = newTime;
                                }
                            }
                            if (mediaData.has("duration_minutes") && !mediaData.get("duration_minutes").isJsonNull()) {
                                int duration = mediaData.get("duration_minutes").getAsInt();
                                if (duration > 0) cachedDurationMinutes = duration;
                            }
                            
                            if (mediaData.has("dom") && !mediaData.get("dom").isJsonNull()) {
                                String newDom = mediaData.get("dom").getAsString();
                                
                                if (!"NO_CHANGE".equals(newDom) && newDom.length() > 50) {
                                    boolean metadataUpdate = mediaData.has("metadata_update")
                                            ? mediaData.get("metadata_update").getAsBoolean() : doDeepScrape;
                                    cachedDomText = MediaObservation.merge(cachedDomText, newDom, metadataUpdate);
                                    
                                    String oldStream = cachedStreamLink;
                                    String newStream = extractFirstMatch(newDom, "STREAM_LINK:\\s*([^\\s]+)");
                                    if (newStream != null && !newStream.isBlank()) cachedStreamLink = newStream;
                                    cachedMagnetLink = extractFirstMatch(newDom, "MAGNET_LINK:\\s*([^\\s]+)");
                                    
                                    if (oldStream != null && cachedStreamLink != null && !oldStream.equals(cachedStreamLink)) {
                                        hasAcquiredEpisodeMetadata = false;
                                        cachedDomText = "";
                                        currentEpisodeHash = "";
                                        lastPlaybackEvidenceAt = 0;
                                        stremioNextDeepScrapeTime = 0;
                                        cachedDurationMinutes = -1;
                                        cachedCurrentTimeSec = -1;
                                    }
                                    
                                    if (doDeepScrape && metadataUpdate) {
                                        String durMatch = extractFirstMatch(newDom, "DURATION_MINUTES:\\s*(\\d+)");
                                        if (durMatch != null) {
                                            try { cachedDurationMinutes = Integer.parseInt(durMatch); } catch (Exception e) {}
                                        }
                                        
                                        String extractedSeries = extractFirstMatch(newDom, "(?m)^SERIES:[\\t ]*([^\\r\\n]*)");
                                        String extractedEp = extractFirstMatch(newDom, "(?m)^EPISODE:[\\t ]*([^\\r\\n]*)");
                                        
                                        String metadataStatus = extractFirstMatch(newDom, "(?m)^METADATA_STATUS:[\\t ]*([^\\r\\n]*)");
                                        String normalizedSeries = extractedSeries == null ? "" : extractedSeries.trim().toLowerCase(Locale.ROOT);
                                        boolean invalidSeries = normalizedSeries.isBlank() || normalizedSeries.contains("unknown")
                                                || normalizedSeries.contains("to open this link") || normalizedSeries.contains("choose an app");
                                        boolean verifiedMetadata = hasUsableMediaMetadata(newDom);
                                        boolean validEpisodeIdentity = extractedSeries != null && extractedEp != null
                                                && !extractedEp.isBlank() && !invalidSeries;
                                        if (!verifiedMetadata && validEpisodeIdentity) {
                                            String candidateHash = extractedSeries.trim() + "_" + extractedEp.trim();
                                            activeSeriesName = extractedSeries.trim();
                                            currentMediaTitle = activeSeriesName + " - " + extractedEp.trim();
                                            if (!candidateHash.equals(currentEpisodeHash)) {
                                                currentEpisodeHash = candidateHash;
                                                hasAcquiredEpisodeMetadata = false;
                                                System.out.println("[HabitTracker] Retaining playback-confirmed episode title while synopsis verification is pending: "
                                                        + currentMediaTitle);
                                            }
                                        }
                                        if (verifiedMetadata && extractedSeries != null && extractedEp != null
                                                && !extractedEp.isBlank() && !invalidSeries) {
                                            hasAcquiredEpisodeMetadata = true;
                                            activeSeriesName = extractedSeries.trim();
                                            String hash = extractedSeries.trim() + "_" + extractedEp.trim();
                                            
                                            if (!hash.equals(currentEpisodeHash)) {
                                                currentEpisodeHash = hash;
                                                System.out.println("[HabitTracker] Detected New Episode: " + currentEpisodeHash);
                                                if (cachedCurrentTimeSec > 0) {
                                                    String cleanTitle = extractedSeries.trim() + " - " + extractedEp.trim();
                                                    episodeExposureMinutes.put(cleanTitle, cachedCurrentTimeSec / 60);
                                                }
                                            }
                                            stremioNextDeepScrapeTime = now + (15 * 60 * 1000L);
                                        } else {
                                            String mediaKind = extractFirstMatch(newDom, "(?m)^MEDIA_KIND:[\\t ]*([^\\r\\n]*)");
                                            String filmTitle = extractFirstMatch(newDom, "(?m)^MEDIA_TITLE:[\\t ]*([^\\r\\n]*)");
                                            String filmMetadataStatus = extractFirstMatch(newDom, "(?m)^METADATA_STATUS:[\\t ]*([^\\r\\n]*)");
                                            if (("FILM".equalsIgnoreCase(mediaKind) || "VIDEO".equalsIgnoreCase(mediaKind)) && hasUsableMediaMetadata(newDom)
                                                    && filmTitle != null && !filmTitle.isBlank()) {
                                                hasAcquiredEpisodeMetadata = true;
                                                activeSeriesName = "";
                                                String filmKey = "FILM_" + filmTitle.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
                                                if (!filmKey.equals(currentEpisodeHash)) {
                                                    currentEpisodeHash = filmKey;
                                                    currentMediaTitle = filmTitle.trim();
                                                    System.out.println("[HabitTracker] Detected Feature Film: " + filmTitle.trim()
                                                            + " (duration=" + cachedDurationMinutes + "m, playback=" + cachedCurrentTimeSec + "s)");
                                                }
                                                stremioNextDeepScrapeTime = now + (15 * 60 * 1000L);
                                            } else {
                                                stremioNextDeepScrapeTime = now + (60 * 1000L);
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            consecutiveDomFailures++;
                        }
                    } catch (Exception e) {
                        consecutiveDomFailures++;
                        if (consecutiveDomFailures == 1 || consecutiveDomFailures % 5 == 0) {
                            System.err.println("[HabitTracker] Media DOM scrape failed " + consecutiveDomFailures
                                    + " time(s) for '" + activeTitle + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    } finally {
                        isTripwireActive.set(false);
                    }
                    }
                });
            } else if (!isMedia && !lastTripwireTitle.isEmpty()) {
                lastTripwireTitle = ""; 
                isTripwireActive.set(false);
            } else {
                isTripwireActive.set(false);
            }
        } catch (Exception e) {
            isTripwireActive.set(false);
        }
    }

    public static String getProcessCategory(String processName) {
        if (processName == null) return "Idle";
        return processCategoryCache.getOrDefault(processName.toLowerCase(), "Idle");
    }

    public static String getCurrentCategory() {
        return currentCategory;
    }

    public static String getCurrentMediaTitle() {
        return currentMediaTitle == null ? "" : currentMediaTitle;
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
        return getActiveMediaData(activeTitle,platform,deepScrape,false);
    }

    private static JsonObject getActiveMediaData(String activeTitle, String platform, boolean deepScrape, boolean identityOnly) {
        long now = System.currentTimeMillis();
        String cleanTitleForPython = activeTitle.replaceAll("^\\(\\d+\\)\\s*", "").trim();
        
        try {
            String encodedTitle = java.net.URLEncoder.encode(cleanTitleForPython, "UTF-8");
            URL url = new URL("http://localhost:" + Integer.getInteger("ciel.routerPort", 8000) + "/active_media_data?title=" + encodedTitle + "&platform=" + platform + "&deep_scrape=" + deepScrape + "&identity_only=" + identityOnly);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            
            // CRITICAL FIX: Limit Java HTTP read timeout so it never locks up the executor thread pool!
            if(identityOnly) conn.setReadTimeout(15000);
            else if (deepScrape) {
                // Deep media identification may perform metadata consensus lookups;
                // 10 seconds caused the caller to abandon valid in-flight results.
                conn.setReadTimeout(65000);
            } else {
                conn.setReadTimeout(3000);   
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject result = JsonParser.parseReader(reader).getAsJsonObject();
                    if (!result.has("dom") || !result.get("dom").isJsonPrimitive()
                            || !result.get("dom").getAsJsonPrimitive().isString())
                        throw new IOException("Media contract: dom must be a string");
                    if (result.has("contract_version")) {
                        for (String numeric : List.of("current_time_sec", "duration_minutes")) {
                            if (!result.has(numeric) || !result.get(numeric).isJsonPrimitive()
                                    || !result.get(numeric).getAsJsonPrimitive().isNumber()
                                    || result.get(numeric).getAsDouble() != result.get(numeric).getAsInt()
                                    || result.get(numeric).getAsInt() < -1)
                                throw new IOException("Media contract: " + numeric + " must be an integer >= -1");
                        }
                        if (result.get("contract_version").getAsInt() != 1
                                || !result.has("playback_state") || !result.has("metadata_update")
                                || !result.get("metadata_update").isJsonPrimitive()
                                || !result.get("metadata_update").getAsJsonPrimitive().isBoolean()
                                || !Set.of("PLAYING", "PAUSED", "VISIBLE", "UNCONFIRMED", "UNAVAILABLE")
                                        .contains(result.get("playback_state").getAsString()))
                            throw new IOException("Media contract: unsupported or malformed observation");
                    }
                    return result;
                } finally {
                    conn.disconnect();
                }
            } else {
                conn.disconnect();
                recordMediaContractFailure(activeTitle, "HTTP " + responseCode);
                return getLocalFallbackMediaData(activeTitle, platform);
            }
        } catch (Exception e) {
            String exceptionMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            recordMediaContractFailure(activeTitle, exceptionMsg);
            
            if (!exceptionMsg.equals(lastExceptionMessage) || (now - lastExceptionLogTime) > 300000) {
                System.out.println("[HabitTracker] Scraper API Exception: " + exceptionMsg + " for platform='"
                        + platform + "', title='" + cleanTitleForPython + "', deep=" + deepScrape
                        + " (Suppressing repeats for 5 mins)");
                lastExceptionMessage = exceptionMsg;
                lastExceptionLogTime = now;
            }
            
            return getLocalFallbackMediaData(activeTitle, platform);
        }
    }

    private static void recordMediaContractFailure(String title, String detail) {
        try {
            Path directory = ProjectPaths.ciel().resolve("diagnostics").resolve("media");
            Files.createDirectories(directory);
            // One bounded report per error type, compatible with Architect's queue.
            String fingerprint = String.format("%020x", Integer.toUnsignedLong(detail.hashCode()));
            Path report = directory.resolve(fingerprint + ".json");
            if (Files.exists(report)) return;
            try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
                if (entries.filter(p -> p.toString().endsWith(".json")).limit(100).count() >= 100) return;
            }
            JsonObject issue = new JsonObject();
            issue.addProperty("code", "JAVA_SCRAPER_CONTRACT_FAILURE");
            issue.addProperty("fingerprint", fingerprint);
            issue.addProperty("component", "media_contract");
            issue.addProperty("contract_version", 1);
            issue.addProperty("status", "pending");
            issue.addProperty("created_at", System.currentTimeMillis() / 1000);
            issue.addProperty("evidence", title == null ? "" : title.substring(0, Math.min(title.length(), 500)));
            issue.addProperty("detail", detail.substring(0, Math.min(detail.length(), 1000)));
            Files.writeString(report, issue.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception ignored) {
            // Recovery reporting must never stop the media poll.
        }
    }

    private static boolean hasUsableMediaMetadata(String dom) {
        String status = extractFirstMatch(dom, "(?m)^METADATA_STATUS:[\t ]*([^\r\n]*)");
        String kind = extractFirstMatch(dom, "(?m)^MEDIA_KIND:[\t ]*([^\r\n]*)");
        String sources = extractFirstMatch(dom, "(?m)^METADATA_SOURCES:[\t ]*([^\r\n]*)");
        return "VERIFIED".equalsIgnoreCase(status == null ? "" : status.trim())
                || ("VIDEO".equalsIgnoreCase(kind == null ? "" : kind.trim())
                    && "PUBLISHER".equalsIgnoreCase(status == null ? "" : status.trim())
                    && "youtube_publisher".equals(sources == null ? "" : sources.trim()));
    }

    private static JsonObject getLocalFallbackMediaData(String activeTitle, String platform) {
        JsonObject fallback = new JsonObject();
        fallback.addProperty("url", "");
        fallback.addProperty("dom", "LOCAL_FALLBACK: Swarm unavailable");
        fallback.addProperty("contract_version", 1);
        fallback.addProperty("metadata_update", false);
        fallback.addProperty("playback_state", "UNAVAILABLE");
        fallback.addProperty("current_time_sec", -1);
        fallback.addProperty("duration_minutes", -1);
        fallback.addProperty("diagnostic_code", "SCRAPER_UNAVAILABLE");
        return fallback;
    }

    public static void toggleMediaPlayback() {
        System.err.println("[Media Transport] Blind toggle disabled; explicit pause/resume required.");
    }

    public static boolean pauseMediaForSpeech() { return mediaTransport("pause",true); }
    public static void resumeMediaAfterSpeech() { mediaTransport("resume",false); }
    /** True when the latest media observation already confirms the player is paused. */
    public static boolean isMediaPlaybackPaused() { return "PAUSED".equalsIgnoreCase(cachedPlaybackState); }

    private static boolean mediaTransport(String action,boolean requireChanged) {
        Process process=null;
        try {
            java.nio.file.Path root=ProjectPaths.root();
            process=new ProcessBuilder(ProjectPaths.pythonBackgroundExecutable(),
                root.resolve("Ciel/skills/ciel_media_transport.py").toString(),action)
                .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final Process running=process;
            var output=CompletableFuture.supplyAsync(() -> {
                try {return new String(running.getInputStream().readNBytes(16384),StandardCharsets.UTF_8);}
                catch(Exception e){return "";}
            });
            if(!process.waitFor(25,TimeUnit.SECONDS))throw new IOException("Transport timeout");
            JsonObject result=JsonParser.parseString(output.get(1,TimeUnit.SECONDS)).getAsJsonObject();
            boolean confirmed=result.has("confirmed")&&result.get("confirmed").getAsBoolean();
            boolean changed=result.has("changed")&&result.get("changed").getAsBoolean();
            System.out.println("[Media Transport] action="+action+" exit="+process.exitValue()+" confirmed="+confirmed+" changed="+changed
                +" state="+(result.has("state")?result.get("state").getAsString():"unknown")
                +" method="+(result.has("method")?result.get("method").getAsString():"unknown")
                +" target="+(result.has("target")?result.get("target").getAsString():"unknown")
                +" reason="+(result.has("reason")?result.get("reason").getAsString():"state verified"));
            return process.exitValue()==0 && confirmed && (!requireChanged||changed);
        } catch(Exception error) {
            System.err.println("[Media Transport] "+action+" failed: "+error.getClass().getSimpleName());return false;
        } finally {if(process!=null)process.destroyForcibly();}
    }

    public static void queueNonCriticalAnnouncement(String text, String titleContext) {
        boolean isGamingNow = ShortTermMemoryService.getMemory().isInGamingSession() || "Gaming".equalsIgnoreCase(currentCategory);
        
        if (!isGamingNow && "Idle".equals(currentCategory) && ShortTermMemoryService.getMemory().getCurrentPhase() == 0) {
            SpeechService.speakPreformatted(text);
        } else {
            deferredSpeechQueue.offer(titleContext); 
            try {
                String dateStr = java.time.LocalDate.now().toString() + "_" + (System.currentTimeMillis() / 1000);
                Path path = ProjectPaths.ciel().resolve("memory_core").resolve("deferred_speech").resolve("Deferred_Speech_" + dateStr + ".md");
                Files.createDirectories(path.getParent());
                Files.writeString(path, "# Background Event: " + titleContext + "\n\n" + text);
            } catch (Exception e) {}
        }
    }

    public static void interruptWithCriticalAnnouncement(String text) {
        SpeechService.speakPreformatted(text, null, false, true); 
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

    private static synchronized void pollAndTrack() {
        if (!LocalDate.now().equals(currentDate)) {
            summarizeAndSaveToMemory();
            dailyHabits.clear();
            loggedMediaToday.clear();
            unverifiedMediaAcknowledgedToday.clear();
            mediaRecognitionAcknowledgedToday.clear();
            episodeExposureMinutes.clear();
            recentMediaHistory.clear();
            mediaThresholds.clear();
            currentBingeCount = 0;
            currentDate = LocalDate.now();
            proactiveTriggeredToday = false; 
            activeSeriesName = "";
            activeSeriesDom = "";
            activeSeriesEpisodes.clear();
            
            CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Observing", 0.5, "Day Reset"));
            HabitTrackerService.hasAcquiredEpisodeMetadata = false;
        }

        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        String activeTitle = metrics.activeWindowTitle();
        String activeProcess = metrics.activeProcessName().toLowerCase();

        boolean isStremioProc = activeProcess.contains("stremio");
        boolean isMedia = isMediaTitle(activeTitle) || isStremioProc; 
        
        String cachedCategory = processCategoryCache.getOrDefault(activeProcess, "Analyzing...");
        
        Optional<GameWindowClassifier.Game> foregroundGame = isMedia
                ? Optional.empty() : GameWindowClassifier.identify(metrics);
        boolean isGaming = foregroundGame.isPresent();

        if (isGaming) {
            currentCategory = "Gaming";
            currentGameTitle = foregroundGame.get().displayName();
            
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
                                JsonObject res = JsonParser.parseString(resStr.replace("`" + "`" + "`json", "").replace("`" + "`" + "`", "").trim()).getAsJsonObject();
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
            
            String series = extractFirstMatch(cachedDomText, "(?m)^SERIES:[\\t ]*([^\\r\\n]*)");
            String ep = extractFirstMatch(cachedDomText, "(?m)^EPISODE:[\\t ]*([^\\r\\n]*)");
            String mediaKind = extractFirstMatch(cachedDomText, "(?m)^MEDIA_KIND:[\\t ]*([^\\r\\n]*)");
            String filmTitle = extractFirstMatch(cachedDomText, "(?m)^MEDIA_TITLE:[\\t ]*([^\\r\\n]*)");
            String metadataStatus = extractFirstMatch(cachedDomText, "(?m)^METADATA_STATUS:[\\t ]*([^\\r\\n]*)");
            boolean verifiedMetadata = hasUsableMediaMetadata(cachedDomText);
            boolean invalidSeries = series == null || series.isBlank() || series.toLowerCase(Locale.ROOT).contains("unknown")
                    || series.toLowerCase(Locale.ROOT).contains("to open this link") || series.toLowerCase(Locale.ROOT).contains("choose an app");
            boolean isEpisode = verifiedMetadata && series != null && ep != null && !ep.isBlank() && !invalidSeries;
            boolean isFilm = !isEpisode && ("FILM".equalsIgnoreCase(mediaKind == null ? "" : mediaKind.trim())
                    || "VIDEO".equalsIgnoreCase(mediaKind == null ? "" : mediaKind.trim()))
                    && verifiedMetadata && filmTitle != null && !filmTitle.isBlank();
            boolean playbackFresh = MediaObservation.freshPlayback(
                    cachedPlaybackState, lastPlaybackEvidenceAt, System.currentTimeMillis());

            if (isEpisode || isFilm) {
                if (isEpisode) {
                    currentMediaTitle = series.trim() + " - " + ep.trim();
                } else {
                    currentMediaTitle = filmTitle.trim();
                    String filmKey = "FILM_" + currentMediaTitle.toLowerCase().replaceAll("[^a-z0-9]", "");
                    if (!filmKey.equals(currentEpisodeHash)) currentEpisodeHash = filmKey;
                }

                int exposure = playbackFresh ? episodeExposureMinutes.merge(currentMediaTitle, 1, Integer::sum)
                        : episodeExposureMinutes.getOrDefault(currentMediaTitle, 0);
                if (!playbackFresh) {
                    System.out.println("[HabitTracker] Media timing deferred: verified identity retained; playback="
                            + cachedPlaybackState + ", last position=" + cachedCurrentTimeSec + "s. Rechecking player; not counting unconfirmed time.");
                }
                if (playbackFresh && exposure > 0 && exposure % 120 == 0) triggerFatigueWarning(exposure);

                long retryAfter = mediaCommentaryRetryAfter.getOrDefault(currentEpisodeHash, 0L);
                // A paused player still needs a useful recognition response. Do
                // not advance exposure or perform timed plot commentary until
                // playback resumes, but acknowledge the verified title once so
                // opening a video is not met with silence.
                if (!playbackFresh && "PAUSED".equalsIgnoreCase(cachedPlaybackState)
                        && !currentEpisodeHash.isEmpty()
                        && !mediaRecognitionAcknowledgedToday.contains(currentEpisodeHash)
                        && !mediaCommentaryInFlight.contains(currentEpisodeHash)
                        && hasAcquiredEpisodeMetadata
                        && System.currentTimeMillis() >= retryAfter) {
                    triggerPausedMediaRecognition(currentMediaTitle, activeTitle, cachedActiveUrl,
                            cachedDomText, currentEpisodeHash);
                }
                if (playbackFresh && !currentEpisodeHash.isEmpty() && !loggedMediaToday.contains(currentEpisodeHash)
                        && !mediaCommentaryInFlight.contains(currentEpisodeHash) && hasAcquiredEpisodeMetadata
                        && System.currentTimeMillis() >= retryAfter) {
                    int targetSec = MediaObservation.commentaryTargetSeconds(isFilm, cachedDurationMinutes);
                    boolean reached = MediaObservation.commentaryTimeReached(cachedCurrentTimeSec, exposure, targetSec, cachedDurationMinutes);
                    System.out.println("[HabitTracker] Media timing: kind=" + (isFilm ? "FILM" : "EPISODE")
                            + ", title='" + currentMediaTitle + "', runtime=" + cachedDurationMinutes
                            + "m, playback=" + cachedCurrentTimeSec + "s, target=" + targetSec
                            + "s, exposure=" + exposure + "m, timingSource="
                            + (cachedCurrentTimeSec >= 0 ? "player-position" : "confirmed-playback-estimate")
                            + ", reached=" + reached);
                    if (reached) triggerSequentialMediaCommentary(currentMediaTitle, activeTitle, cachedActiveUrl, cachedDomText, currentEpisodeHash);
                } else if (currentEpisodeHash.isEmpty() || !hasAcquiredEpisodeMetadata) {
                    System.out.println("[HabitTracker] Media dialogue skipped: metadata/hash not ready (kind=" + (isFilm ? "FILM" : "EPISODE")
                            + ", metadata=" + hasAcquiredEpisodeMetadata + ", duration=" + cachedDurationMinutes + "m, playback=" + cachedCurrentTimeSec + "s)");
                }
                if (isEpisode) updateSeriesIfHigher(series, ep);
            } else if (!verifiedMetadata && "EPISODE".equalsIgnoreCase(mediaKind == null ? "" : mediaKind.trim())
                    && isStremioProc && series != null && !invalidSeries && ep != null && !ep.isBlank()) {
                String episodeKey = series.trim() + "_" + ep.trim();
                String episodeTitle = series.trim() + " - " + ep.trim();
                int exposure = playbackFresh ? episodeExposureMinutes.merge(episodeTitle, 1, Integer::sum)
                        : episodeExposureMinutes.getOrDefault(episodeTitle, 0);
                if (!unverifiedMediaAcknowledgedToday.contains(episodeKey)) {
                    System.out.println("[HabitTracker] Plot commentary deferred for '" + series.trim() + " - " + ep.trim()
                            + "': episode synopsis remains unverified; metadata retry stays eligible.");
                }
                if (playbackFresh
                        && episodeKey.equals(currentEpisodeHash)
                        && !unverifiedMediaAcknowledgedToday.contains(episodeKey)
                        && !loggedMediaToday.contains(episodeKey)
                        && !mediaCommentaryInFlight.contains(episodeKey)
                        && System.currentTimeMillis() >= mediaCommentaryRetryAfter.getOrDefault(episodeKey, 0L)
                        && MediaObservation.commentaryTimeReached(cachedCurrentTimeSec, exposure,
                                MediaObservation.commentaryTargetSeconds(false, cachedDurationMinutes), cachedDurationMinutes)
                        && mediaCommentaryInFlight.add(episodeKey)) {
                    String safeSeries = series.trim().replaceAll("[\\r\\n\\t]", " ")
                            .replace("[", "").replace("]", "");
                    if (safeSeries.length() > 90) safeSeries = safeSeries.substring(0, 90).trim();
                    String line = "[Curious] Master, I see you're watching " + safeSeries
                            + ". I'll stay with this episode and leave its surprises for you to discover.";
                    System.out.println("[HabitTracker] Speaking spoiler-safe series acknowledgment; synopsis verification remains pending.");
                    try {
                        SpeechService.speakMediaCommentary(line, spoken -> {
                            if (spoken) {
                                unverifiedMediaAcknowledgedToday.add(episodeKey);
                                mediaCommentaryRetryAfter.put(episodeKey, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
                            } else {
                                mediaCommentaryRetryAfter.put(episodeKey, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3));
                            }
                            mediaCommentaryInFlight.remove(episodeKey);
                        });
                    } catch (RuntimeException error) {
                        mediaCommentaryRetryAfter.put(episodeKey, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3));
                        mediaCommentaryInFlight.remove(episodeKey);
                        System.err.println("[HabitTracker] Could not queue spoiler-safe series acknowledgment: "
                                + error.getClass().getSimpleName());
                    }
                }
            }
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
                    
                    AIEngine.generateSilentLogicWithModel(prompt, "You are a PC activity classifier.", CielTools.getBackgroundModel(), 0.1, "Background Evaluation").thenAccept(resStr -> {
                        try {
                            if (resStr != null && !resStr.isBlank()) {
                                JsonObject res = JsonParser.parseString(resStr.replace("`" + "`" + "`json", "").replace("`" + "`" + "`", "").trim()).getAsJsonObject();
                                if (res.has("category") && !res.get("category").isJsonNull()) {
                                    String cat = res.get("category").getAsString();
                                    if (cat.equals("Gaming") || cat.equals("Media") || cat.equals("Productivity")) {
                                        // Only GameMonitor's foreground PID check can grant Gaming.
                                        processCategoryCache.put(activeProcess, cat.equals("Gaming") ? "Idle" : cat);
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
                    currentCategory = "Gaming".equals(cachedCategory) ? "Idle" : cachedCategory;
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

        boolean isGamingNow = ShortTermMemoryService.getMemory().isInGamingSession() || "Gaming".equalsIgnoreCase(currentCategory);

        if (!isGamingNow && ShortTermMemoryService.getMemory().getCurrentPhase() == 0 && !deferredSpeechQueue.isEmpty()) {
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

        dailyHabits.merge(currentCategory, 1L, Long::sum);
        evaluateEmotionalResonance();
    }
    
    private static void triggerFatigueWarning(int exposureMinutes) {
        System.out.println("[HabitTracker] Fatigue threshold met: " + exposureMinutes + " mins. Requesting dynamic warning.");
        
        com.cielcompanion.ai.ObserverService.setHasWarnedForFatigue(true);
        
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
    
    private static void triggerPausedMediaRecognition(String cleanTitle, String fullWindowTitle, String activeUrl,
                                                      String datText, String hashToLog) {
        if (!mediaCommentaryInFlight.add(hashToLog)) return;
        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
        System.out.println("[HabitTracker] Verified paused media identity for " + cleanTitle
                + "; requesting one spoiler-safe recognition line.");
        String prompt = "[MEDIA_RECOGNITION] [QUERY: " + fullWindowTitle + "|||" + activeUrl + "]\n"
                + "The player is paused. Use the verified title and publisher metadata below to acknowledge what the Master opened.\n"
                + datText + "\nOUTPUT CONTRACT: Write exactly one concise, natural English sentence. "
                + "Mention the identified title naturally and say you will wait for playback; do not summarize plot, "
                + "invent details, or use Japanese script. Latin letters only.";
        try {
            AIEngine.generateSilentLogicWithModel(prompt, "You are Manas: Ciel.", CielTools.getBackgroundModel(),
                    0.5, "Paused Media Recognition").whenComplete((response, error) -> {
                boolean handedToSpeech = false;
                try {
                    if (error != null || response == null || response.isBlank()
                            || response.contains("ABORT") || response.contains("DATA_DEFICIT")
                            || containsJapaneseScript(response)) {
                        mediaCommentaryRetryAfter.put(hashToLog, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
                        System.err.println("[HabitTracker] Paused media recognition unavailable for '" + cleanTitle + "'; retry deferred.");
                    } else if (!hashToLog.equals(currentEpisodeHash)) {
                        System.out.println("[HabitTracker] Paused media recognition became stale; suppressing response.");
                    } else {
                        SpeechService.speakMediaCommentary(response.trim(), spoken -> {
                            if (spoken) {
                                mediaRecognitionAcknowledgedToday.add(hashToLog);
                                mediaCommentaryRetryAfter.remove(hashToLog);
                                recentMediaHistory.add(cleanTitle);
                                if (recentMediaHistory.size() > 5) recentMediaHistory.removeFirst();
                            } else {
                                mediaCommentaryRetryAfter.put(hashToLog, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3));
                            }
                            mediaCommentaryInFlight.remove(hashToLog);
                        });
                        handedToSpeech = true;
                    }
                } finally {
                    if (!handedToSpeech) {
                        mediaCommentaryInFlight.remove(hashToLog);
                        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
                    }
                }
            });
        } catch (Exception error) {
            mediaCommentaryRetryAfter.put(hashToLog, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
            mediaCommentaryInFlight.remove(hashToLog);
            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
            System.err.println("[HabitTracker] Could not start paused media recognition for '" + cleanTitle + "': " + error.getMessage());
        }
    }

    private static void triggerSequentialMediaCommentary(String cleanTitle, String fullWindowTitle, String activeUrl, String datText, String hashToLog) {
        if (!mediaCommentaryInFlight.add(hashToLog)) return;
        CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.THINKING));
        System.out.println("[HabitTracker] Threshold met for " + cleanTitle + ". Requesting commentary (runtime=" + cachedDurationMinutes
                + "m, playback=" + cachedCurrentTimeSec + "s, hash=" + hashToLog + ")...");
        
        String effectiveSeriesName = activeSeriesName.isEmpty() ? currentMediaTitle : activeSeriesName;
        String safeSeriesKey = "series_progress_" + effectiveSeriesName.toLowerCase().replaceAll("[^a-z0-9]", "");
        Optional<Fact> maxProgressFact = MemoryService.getFact(safeSeriesKey);
        String maxProgressStr = maxProgressFact.isPresent() ? " (Highest episode previously recorded for this series: " + maxProgressFact.get().value() + ". This does not prove completion or that intermediate episodes were watched. Use exact-episode history for possible rewatch context; never assume knowledge of later plot events.)" : "";

        String historicalContext = (!activeSeriesName.isEmpty() && !activeSeriesEpisodes.isEmpty()) 
            ? "SERIES CONTINUITY (Master is binge-watching the series '" + activeSeriesName + "'. Prior episodes watched this session: " + String.join(", ", activeSeriesEpisodes) + ")" + maxProgressStr
            : (!recentMediaHistory.isEmpty() ? "GLOBAL CONTINUITY (Recent media watched this session):\n" + String.join(" -> ", recentMediaHistory) : "");

        String watchContext=ViewingProgress.context(MediaObservation.field(datText,"SERIES"),MediaObservation.field(datText,"EPISODE"),cachedCurrentTimeSec);
        String prompt = "[MEDIA_COMMENTARY] [QUERY: " + fullWindowTitle + "|||" + activeUrl + "]\nACCESSIBILITY DAT TEXT:\n" + datText + "\nHISTORY:\n" + historicalContext
            +"\nEXACT EPISODE PLAYBACK HISTORY:\n"+watchContext
            + "\nOUTPUT CONTRACT: Write one concise, natural English commentary sentence. "
            + "Use English semantics and Latin letters only; do not translate into Japanese and do not use hiragana, katakana, kanji, or Japanese punctuation. "
            + "Short romaji affection or praise such as 'daisuki' is allowed only when it fits naturally. "
            + "Do not describe the output contract or return a language label.";
        
        try {
            AIEngine.generateSilentLogicWithModel(prompt, "You are Manas: Ciel.", CielTools.getBackgroundModel(), 0.7, "Media Persona Commentary")
                .whenComplete((response, error) -> {
                    boolean handedToSpeech = false;
                    try {
                        if (error != null) {
                            mediaCommentaryRetryAfter.put(hashToLog, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15));
                            System.err.println("[HabitTracker] Media commentary failed for '" + cleanTitle + "' (" + error.getClass().getSimpleName() + "): " + error.getMessage());
                        } else if (response == null || response.isBlank() || response.contains("ABORT") || response.contains("DATA_DEFICIT")) {
                            mediaCommentaryRetryAfter.put(hashToLog, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15));
                            System.out.println("[HabitTracker] Media commentary returned no speakable response for '" + cleanTitle + "'; retry deferred for 15 minutes.");
                        } else if (containsJapaneseScript(response)) {
                            mediaCommentaryRetryAfter.put(hashToLog, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15));
                            System.err.println("[HabitTracker] Media commentary rejected non-English script for '" + cleanTitle + "'; retry deferred for 15 minutes.");
                        } else {
                            if (!hashToLog.equals(currentEpisodeHash) || (cachedDurationMinutes > 0
                                    && cachedCurrentTimeSec >= cachedDurationMinutes * 60)) {
                                System.out.println("[HabitTracker] Commentary completed after playback ended/changed; suppressing stale response.");
                                return;
                            }
                            System.out.println("[HabitTracker] Commentary generated; passing response to SpeechService for '" + cleanTitle + "'.");
                            SpeechService.speakMediaCommentary(response, spoken -> {
                                if (spoken) {
                                    loggedMediaToday.add(hashToLog);
                                    mediaCommentaryRetryAfter.remove(hashToLog);
                                    recentMediaHistory.add(cleanTitle);
                                    currentBingeCount++;
                                    if (recentMediaHistory.size() > 5) recentMediaHistory.removeFirst();
                                } else {
                                    mediaCommentaryRetryAfter.put(hashToLog, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3));
                                    System.out.println("[HabitTracker] Commentary not spoken; retry scheduled for '" + cleanTitle + "'.");
                                }
                                mediaCommentaryInFlight.remove(hashToLog);
                            });
                            handedToSpeech = true;
                            return;
                        }
                    } finally {
                        if (!handedToSpeech) {
                            mediaCommentaryInFlight.remove(hashToLog);
                            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
                        }
                    }
                });
        } catch (Exception e) {
            mediaCommentaryRetryAfter.put(hashToLog, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15));
            mediaCommentaryInFlight.remove(hashToLog);
            CielState.getCielGui().ifPresent(gui -> gui.setState(CielGui.GuiState.IDLE));
            System.err.println("[HabitTracker] Could not start media commentary for '" + cleanTitle + "': " + e.getMessage());
        }
    }

    private static boolean containsJapaneseScript(String text) {
        if (text == null) return false;
        return text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x3040 && codePoint <= 0x30ff) ||
                (codePoint >= 0x3400 && codePoint <= 0x9fff) ||
                (codePoint >= 0xff66 && codePoint <= 0xff9f));
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
