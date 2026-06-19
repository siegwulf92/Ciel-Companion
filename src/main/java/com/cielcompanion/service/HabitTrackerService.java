package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ContextBuilder;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemory;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.util.CielTools;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HabitTrackerService {

    private static ScheduledExecutorService habitScheduler;
    private static ScheduledExecutorService tripwireScheduler;
    
    private static final Map<String, Long> dailyHabits = new HashMap<>();
    private static String currentCategory = "Idle";
    private static LocalDate currentDate = LocalDate.now();
    
    private static boolean proactiveTriggeredToday = false;
    private static boolean queueFlushedThisSession = true; 

    private static final Map<String, String> processCategoryCache = new ConcurrentHashMap<>();
    
    private static final Set<String> IGNORED_PROCESSES = Set.of(
        "chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe", 
        "explorer.exe", "idle", "discord.exe", "cmd.exe", "powershell.exe", "pwsh.exe", 
        "conhost.exe", "applicationframehost.exe", "razerappengine.exe", "razer central.exe", 
        "razer synapse.exe", "redragon.exe", "lghub.exe", "steamwebhelper.exe", "steam.exe",
        "epicgameslauncher.exe", "battle.net.exe"
    );

    private static String currentMediaTitle = "";
    private static int currentMediaConsecutiveMinutes = 0;
    private static final Set<String> loggedMediaToday = new HashSet<>();

    private static final Queue<String> deferredSpeechQueue = new LinkedList<>();
    private static boolean currentGamePausable = false;

    private static String cachedActiveUrl = "";
    private static String cachedDomText = "";
    private static String lastTripwireTitle = "";
    private static String lastLoggedDom = "";
    private static int consecutiveDomFailures = 0;
    private static long lastStremioScrapeTime = 0; 
    
    private static final LinkedList<String> recentMediaHistory = new LinkedList<>();
    private static int currentBingeCount = 0;
    
    private static String deferredIntenseMediaTitle = null;
    
    // --- SERIES CONTINUITY TRACKERS ---
    private static String activeSeriesName = "";
    private static String activeSeriesDom = "";
    private static final List<String> activeSeriesEpisodes = new ArrayList<>();

    private static final Set<String> STREMIO_NON_SHOW_PAGES = Set.of(
        "discover", "search", "library", "settings", "profile", 
        "home", "browse", "movies", "series", "anime",
        "addons", "account", "logout", "help", "about"
    );

    private static String truncateLog(String text) {
        if (text == null) return "null";
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    public static void initialize() {
        habitScheduler = Executors.newSingleThreadScheduledExecutor();
        tripwireScheduler = Executors.newSingleThreadScheduledExecutor();
        
        habitScheduler.scheduleWithFixedDelay(HabitTrackerService::pollAndTrack, 2, 60, TimeUnit.SECONDS);
        tripwireScheduler.scheduleWithFixedDelay(HabitTrackerService::tripwireCheck, 2, 15, TimeUnit.SECONDS);
        
        System.out.println("Ciel Debug: Habit Tracker Service initialized. Monitoring behavior patterns.");
    }

    private static void performReturnFromIdle(ShortTermMemory memory, int oldPhase) {
        System.out.printf("Ciel Debug: Phase changed from %d to 0 confirmed.%n", oldPhase);
        CielState.getEmotionManager().ifPresent(em -> {
            em.triggerEmotion("Focused", 1.0, "Activity");
            em.triggerEmotion("Happy", 0.5, "UserReturn");
            em.triggerEmotion("Lonely", -1.0, null);
        });

        memory.setCurrentPhase(0);
        CielState.setFinalPlayed(false);

        System.out.println("Ciel Debug: User returned from idle. Forcing reconnect to NVIDIA Broadcast and flushing Vosk audio buffers...");
        SpeechService.getVoiceListener().ifPresent(VoiceListener::forceMicReinitialization);

        SpeechService.stopCurrentPlayback();
        SpeechService.cancelSequentialSpeech();

        if (oldPhase >= 4) {
            String recentMemories = String.join(" ", MemoryService.getRecentEpisodicMemories(3));
            
            List<LineManager.DialogueLine> greetingLines = LineManager.getLoginGreetingLines();
            if (greetingLines != null && !greetingLines.isEmpty()) {
                LineManager.DialogueLine line = greetingLines.get(new Random().nextInt(greetingLines.size()));
                
                // Relies on SpeechService's universal queue for media pausing
                SpeechService.speakPreformatted(line.text(), line.key());
            }

            CompletableFuture.runAsync(() -> {
                String prompt = "Master has returned after a long absence. Welcome him back. Recent memory context: " + recentMemories;
                String context = ContextBuilder.buildActiveContext(null, "");
                AIEngine.chatFast(prompt, context, null);
            });
            loggedMediaToday.clear(); 
        }
    }

    private static String extractPlatform(String title) {
        String lower = title.toLowerCase();
        if (lower.contains("crunchyroll")) return "crunchyroll";
        if (lower.contains("youtube") || lower.contains("youtu.be")) return "youtube";
        if (lower.contains("netflix")) return "netflix";
        if (lower.contains("hulu")) return "hulu";
        if (lower.contains("prime video")) return "prime video";
        if (lower.contains("viz")) return "viz";
        if (lower.contains("hidive")) return "hidive";
        if (lower.contains("stremio")) return "stremio";
        return "unknown";
    }

    public static boolean isMediaTitle(String title) {
        if (title == null || title.length() > 300) return false;
        String lower = title.toLowerCase();
        if (lower.contains("youtube") || 
            lower.contains("netflix") || 
            lower.contains("twitch") || 
            lower.contains("crunchyroll") || 
            lower.contains("hulu") || 
            lower.contains("prime video") || 
            lower.contains("disney+") || 
            lower.contains("max") || 
            lower.contains("peacock") || 
            lower.contains("paramount+") || 
            lower.contains("apple tv") || 
            lower.contains("viz") || 
            lower.contains("hidive") || 
            lower.startsWith("watch ") || 
            lower.startsWith("read ")) {
            return true;
        }
        if (lower.contains("stremio")) {
            String cleaned = cleanMediaTitle(title);
            if (cleaned.isEmpty() || 
                (STREMIO_NON_SHOW_PAGES.contains(cleaned.toLowerCase()) && !cleaned.equalsIgnoreCase("stremio"))) {
                return false;
            }
            return true;
        }
        return false;
    }

    private static void tripwireCheck() {
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        final String activeTitle = metrics.activeWindowTitle();
        if (activeTitle == null || activeTitle.isBlank() || activeTitle.equals("Program Manager")) {
            return;
        }
        
        final boolean isMedia = isMediaTitle(activeTitle);
        final boolean isStremio = activeTitle.toLowerCase().contains("stremio");
        
        final boolean titleChanged = !activeTitle.equals(lastTripwireTitle) && !isStremio;
        
        // 3-Minute Background Sweep to detect episode changes natively during full-screen playback.
        long stremioRefreshInterval = 180000; 
        final boolean stremioNeedsRefresh = isStremio && (System.currentTimeMillis() - lastStremioScrapeTime > stremioRefreshInterval); 
        
        boolean needsDomRefresh = isMedia && (cachedDomText == null || cachedDomText.isEmpty() || cachedDomText.contains("Bypassed") || stremioNeedsRefresh) && consecutiveDomFailures < 3;
        
        if (isMedia && (titleChanged || needsDomRefresh)) {
            if (titleChanged) {
                consecutiveDomFailures = 0;
            } else if (stremioNeedsRefresh) {
                lastStremioScrapeTime = System.currentTimeMillis();
                consecutiveDomFailures = 0; // Ensure Stremio never gets permanently locked out
            }
            
            final boolean isShowPlatform = isMediaTitle(activeTitle);
            
            String rawTitlePrefix = activeTitle.split("-")[0].split("\\|")[0].trim();
            String newSeriesNameTemp = rawTitlePrefix;
            
            if (activeTitle.toLowerCase().startsWith("stremio") && activeTitle.contains("-")) {
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
            
            final boolean isSameSeries = tempIsSameScene;

            if (isShowPlatform && !isSameSeries) {
                activeSeriesName = newSeriesName;
                activeSeriesDom = "";
                activeSeriesEpisodes.clear();
            } else if (!isShowPlatform) {
                activeSeriesName = "";
                activeSeriesDom = "";
                activeSeriesEpisodes.clear();
            }

            if (!extractPlatform(activeTitle).equals(extractPlatform(lastTripwireTitle)) || !isSameSeries) {
                cachedDomText = "";
                cachedActiveUrl = "";
            }
            
            lastTripwireTitle = activeTitle;
            
            CompletableFuture.runAsync(() -> {
                try {
                    boolean securedRichDom = false;
                    JsonObject mediaData = null;
                    
                    if (isSameSeries && cachedDomText.length() > 50 && !stremioNeedsRefresh) {
                        mediaData = getActiveMediaData(activeTitle);
                        if (mediaData != null && mediaData.has("dom") && !mediaData.get("dom").isJsonNull()) {
                            securedRichDom = true;
                        }
                    }

                    int attempts = 0;
                    while (!securedRichDom && attempts < 3 && activeTitle.equals(lastTripwireTitle)) {
                        mediaData = getActiveMediaData(activeTitle);
                        
                        if (mediaData != null) {
                            if (mediaData.has("url") && !mediaData.get("url").isJsonNull()) {
                                String newUrl = mediaData.get("url").getAsString();
                                if (!newUrl.isEmpty() && !newUrl.equals(cachedActiveUrl)) {
                                    cachedActiveUrl = newUrl;
                                }
                            }
                            
                            if (mediaData.has("dom") && !mediaData.get("dom").isJsonNull()) {
                                String newDom = mediaData.get("dom").getAsString();
                                
                                if (newDom != null && newDom.length() > 50) {
                                    if (newDom.contains("SERIES: Unknown") && cachedDomText != null && !cachedDomText.isEmpty() && !cachedDomText.contains("SERIES: Unknown")) {
                                        // Ignore empty data if we already secured the real series name
                                    } else {
                                        cachedDomText = newDom;
                                        if (isShowPlatform) activeSeriesDom = newDom; 
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
                            System.out.println("Ciel Debug: Successfully captured new media context for: " + cleanMediaTitle(activeTitle));
                            lastLoggedDom = cachedDomText;
                        }
                    } else {
                        if (!titleChanged) consecutiveDomFailures++;
                    }
                } catch (Exception e) {
                    System.out.println("Ciel Debug: Background scraper interrupted.");
                }
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

    public static boolean isCurrentGamePausable() {
        return currentGamePausable;
    }
    
    private static JsonObject getActiveMediaData(String activeTitle) {
        try {
            // Strip YouTube notification counts e.g. (9) from the title before hashing
            String cleanTitleForPython = activeTitle.replaceAll("^\\(\\d+\\)\\s*", "").trim();
            String encodedTitle = java.net.URLEncoder.encode(cleanTitleForPython, "UTF-8");
            String platform = extractPlatform(activeTitle);
            URL url = new URL("http://localhost:8000/active_media_data?title=" + encodedTitle + "&platform=" + platform);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(25000); 
            if (conn.getResponseCode() == 200) {
                return JsonParser.parseReader(new InputStreamReader(conn.getInputStream(), "UTF-8")).getAsJsonObject();
            }
        } catch (Exception e) {
            if (!e.getMessage().toLowerCase().contains("timed out")) {
                System.out.println("Ciel Debug: Context Scraper Exception: " + e.getMessage());
            }
        }
        return new JsonObject();
    }
    
    public static void toggleMediaPlayback() {
        try {
            URL url = new URL("http://localhost:8000/toggle_media");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1000);
            conn.getResponseCode(); 
        } catch (Exception e) {}
    }

    public static void queueNonCriticalAnnouncement(String text, String titleContext) {
        if ("Idle".equals(currentCategory) && ShortTermMemoryService.getMemory().getCurrentPhase() == 0) {
            SpeechService.speakPreformatted(text);
        } else {
            System.out.println("Ciel Debug: Master is busy (" + currentCategory + ") or Idle Phase active. Deferring speech to background queue.");
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
                               .trim();
        if (cleaned.equalsIgnoreCase("stremio") || cleaned.isEmpty()) {
            return cleaned; 
        }
        return cleaned;
    }

    private static void pollAndTrack() {
        if (!LocalDate.now().equals(currentDate)) {
            summarizeAndSaveToMemory();
            dailyHabits.clear();
            loggedMediaToday.clear();
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

        boolean isMedia = isMediaTitle(activeTitle);
        boolean isGaming = !isMedia && (
                           (activeProcess.contains("game") && !activeProcess.contains("razer") && !activeProcess.contains("redragon") && !activeProcess.contains("logitech") && !activeProcess.contains("epicgameslauncher")) || 
                           (activeProcess.contains("steam") && !activeProcess.contains("steamwebhelper") && !activeProcess.equals("steam.exe")) || 
                           activeTitle.toLowerCase().contains("helldivers") || 
                           activeTitle.toLowerCase().contains("elden ring")
                           );

        if (isGaming) {
            currentCategory = "Gaming";
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
                                JsonObject res = JsonParser.parseString(resStr.replace("```json", "").replace("```", "").trim()).getAsJsonObject();
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
        } else if (activeProcess.contains("code") || activeProcess.contains("idea") || activeProcess.contains("obsidian") || activeProcess.contains("word") || activeProcess.contains("notepad")) {
            currentCategory = "Productivity";
            currentGamePausable = false;
        } else {
            currentGamePausable = false;
            if (!activeProcess.isBlank() && !IGNORED_PROCESSES.contains(activeProcess) && !processCategoryCache.containsKey(activeProcess)) {
                processCategoryCache.put(activeProcess, "Analyzing..."); 
                
                String prompt = "Analyze this active Windows application.\nProcess Executable: " + activeProcess + "\nWindow Title: " + activeTitle + "\nClassify it into EXACTLY ONE of these categories: 'Gaming', 'Media', 'Productivity', or 'Idle'. \n" +
                                "CRITICAL: Ignore peripheral software (Razer, Redragon, Logitech), launchers (Steam, Epic Games, Battle.net), and browsers. ONLY classify actual actively running video games as 'Gaming'.\n" +
                                "Reply strictly with a JSON object: { \"category\": \"Gaming\" }";
                
                AIEngine.generateSilentLogicWithModel(prompt, "You are a PC activity classifier.", CielTools.getBackgroundModel(), 0.1).thenAccept(resStr -> {
                    try {
                        if (resStr != null && !resStr.isBlank()) {
                            JsonObject res = JsonParser.parseString(resStr.replace("```json", "").replace("```", "").trim()).getAsJsonObject();
                            if (res.has("category") && !res.get("category").isJsonNull()) {
                                String cat = res.get("category").getAsString();
                                if (cat.equals("Gaming") || cat.equals("Media") || cat.equals("Productivity")) {
                                    processCategoryCache.put(activeProcess, cat);
                                } else {
                                    processCategoryCache.put(activeProcess, "Idle");
                                }
                            } else {
                                processCategoryCache.put(activeProcess, "Idle");
                            }
                        } else {
                            processCategoryCache.put(activeProcess, "Idle");
                        }
                    } catch (Exception e) {
                        processCategoryCache.put(activeProcess, "Idle");
                    }
                });
                currentCategory = "Idle"; 
            } else {
                String cachedCat = processCategoryCache.getOrDefault(activeProcess, "Idle");
                currentCategory = cachedCat;
                if ("Analyzing...".equals(currentCategory)) currentCategory = "Idle";
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
            
            // Extract the true Series & Episode Name from the rich DOM metadata
            if (activeTitle.toLowerCase().contains("stremio") && cachedDomText != null && !cachedDomText.isEmpty()) {
                String datSeries = "";
                String datEpisode = "";
                Matcher sMatcher = Pattern.compile("(?m)^SERIES:\\s*(.+)").matcher(cachedDomText);
                Matcher eMatcher = Pattern.compile("(?m)^EPISODE:\\s*(.+)").matcher(cachedDomText);
                
                if (sMatcher.find()) datSeries = sMatcher.group(1).trim();
                if (eMatcher.find()) datEpisode = eMatcher.group(1).trim();
                
                // Fallback for Generic BFS Scraper DOM
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
            
            if (!cleanTitle.isBlank() && cleanTitle.equals(currentMediaTitle)) {
                currentMediaConsecutiveMinutes++;

                boolean isLongBinge = (currentMediaConsecutiveMinutes > 0 && currentMediaConsecutiveMinutes % 120 == 0);

                if ((currentMediaConsecutiveMinutes == 5 && !loggedMediaToday.contains(cleanTitle)) || isLongBinge) {

                    String platform = extractPlatform(activeTitle);
                    String safeDat = "";
                    if (cachedDomText != null && !cachedDomText.isEmpty() && !cachedDomText.contains("Bypassed")) {
                        String flatDat = cachedDomText.replaceAll("\\s+", " ");
                        safeDat = " [Captured Context: " + flatDat.substring(0, Math.min(flatDat.length(), 1000)).trim() + "...]";
                    }
                    
                    if (!platform.equals("youtube")) {
                        String effectiveSeriesName = activeSeriesName.isEmpty() ? cleanTitle : activeSeriesName;
                        updateSeriesIfHigher(effectiveSeriesName, activeTitle + " " + safeDat);
                    }

                    if (currentMediaConsecutiveMinutes == 5) {
                        String memoryText = "Master actively engaged with the media content '" + cleanTitle + "' for over 5 minutes." + safeDat;
                        MemoryService.addFact(new Fact("media_" + System.currentTimeMillis(), memoryText, System.currentTimeMillis(), "episodic_memory", "habit_tracking", 1));
                        loggedMediaToday.add(cleanTitle);
                        
                        recentMediaHistory.add(cleanTitle);
                        if (recentMediaHistory.size() > 50) recentMediaHistory.removeFirst(); // Retain deep history across binges
                    }
                    
                    if (ShortTermMemoryService.getMemory().getCurrentPhase() == 0) {
                        String bgModel = CielTools.getBackgroundModel();
                        triggerConfidentMediaCommentary(cleanTitle, activeTitle, cachedActiveUrl, cachedDomText, currentBingeCount, null, bgModel, isLongBinge);
                    }
                }
            } else {
                // RESET THE Commenting Engine state for show/episode transitions!
                if (!currentMediaTitle.isEmpty() && !currentMediaTitle.equals(cleanTitle)) {
                    if (!cleanTitle.equalsIgnoreCase("stremio") && !currentMediaTitle.equalsIgnoreCase("stremio")) {
                        currentBingeCount++;
                    }
                    currentMediaConsecutiveMinutes = 0; 
                    loggedMediaToday.remove(cleanTitle); 
                    
                    if (!activeSeriesName.isEmpty() && !activeSeriesEpisodes.contains(currentMediaTitle)) {
                        activeSeriesEpisodes.add(currentMediaTitle);
                    }
                    
                    if (deferredIntenseMediaTitle != null) {
                        String bgModel = CielTools.getBackgroundModel();
                        triggerConfidentMediaCommentary(cleanTitle, activeTitle, cachedActiveUrl, cachedDomText, currentBingeCount, deferredIntenseMediaTitle, bgModel, false);
                        deferredIntenseMediaTitle = null; 
                    }
                } else if (currentBingeCount == 0) {
                    currentBingeCount = 1;
                }
                
                currentMediaTitle = cleanTitle;
                currentMediaConsecutiveMinutes = 1;
            }
        } else {
            if (deferredIntenseMediaTitle != null && !currentCategory.equals("Idle")) {
                String bgModel = CielTools.getBackgroundModel();
                triggerConfidentMediaCommentary(currentMediaTitle, activeTitle, cachedActiveUrl, cachedDomText, currentBingeCount, deferredIntenseMediaTitle, bgModel, false);
                deferredIntenseMediaTitle = null;
            }
            
            currentMediaTitle = "";
            currentMediaConsecutiveMinutes = 0;
            currentBingeCount = 0;
            
            cachedActiveUrl = "";
            cachedDomText = "";
            lastTripwireTitle = "";
            consecutiveDomFailures = 0;
        }

        if (!currentCategory.equals("Idle") && !processCategoryCache.containsKey(activeProcess) && !IGNORED_PROCESSES.contains(activeProcess)) {
            processCategoryCache.put(activeProcess, currentCategory);
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
                      "4. Speak STRICTLY as Manas: Ciel from Tensura. Formulate a sharp, opinionated deduction about the plot or character choices. You MUST demonstrate awareness of the OVERARCHING series premise.\n" +
                      "5. CRITICAL ANTI-HALLUCINATION: Do NOT refer to the streaming platform. Do NOT mistake character names for your own. Maintain an external observer perspective. You are NOT in the show.\n" +
                      "6. Keep it EXTREMELY concise and punchy (exactly 1 or 2 short sentences).\n" +
                      "7. If you are completely unsure and the data is missing, output EXACTLY: ABORT.";

        String prompt = "[WEB_SEARCH] [COMMENTARY DIRECTIVE] [QUERY: " + query + "] " +
            "Master Taylor is watching media. The window title of his active screen is: '" + fullWindowTitle + "'.\n\n" +
            instruction + historicalContext + bingeContext + fatigueContext + datContext + "\n" +
            "Output ONLY your spoken dialogue starting with a bracketed emotion tag like [Amused], [Curious], or [Observing]. If deferring, output ONLY: DEFER. If aborting, output ONLY: ABORT.";

        AIEngine.generateSilentLogicWithModel(prompt, "You are Ciel, acting as Master Taylor's analytical AI partner. Use the [WEB_SEARCH] tool natively via python if needed, but return the final commentary.", modelToUse, 0.3).thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                String cleanResponse = response.trim();
                
                if (cleanResponse.equals("ABORT") || cleanResponse.contains("ABORT")) {
                    // Commentary aborted silently
                } else if (cleanResponse.equals("DEFER") || cleanResponse.contains("DEFER")) {
                    deferredIntenseMediaTitle = cleanTitle;
                } else {
                    SpeechService.speakPreformatted(cleanResponse, null, false, true);
                }
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
        StringBuilder summary = new StringBuilder("Daily Routine Summary for " + currentDate + ": ");
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
            if (oldProg != null && !newProg.isGreaterThan(oldProg)) return; // Don't overwrite with older episodes
        }
        
        String newProgStr = "S" + newProg.season + "E" + newProg.episode;
        MemoryService.addFact(new Fact(safeSeriesKey, newProgStr, System.currentTimeMillis(), "user_knowledge", "series_tracker", 1));
        System.out.println("Ciel Debug: Tracked highest progression for series '" + seriesName + "' -> " + newProgStr);
    }
}