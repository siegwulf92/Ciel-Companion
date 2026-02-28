package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ContextBuilder;
import com.cielcompanion.ai.ObserverService;
import com.cielcompanion.dnd.CombatTrackerService;
import com.cielcompanion.dnd.DndCampaignService;
import com.cielcompanion.dnd.LoreService;
import com.cielcompanion.dnd.MasteryService;
import com.cielcompanion.dnd.RulebookService;
import com.cielcompanion.dnd.SpellCheckService;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import com.cielcompanion.mood.EmotionManager;
import com.cielcompanion.service.conversation.ConversationService;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.service.nlu.CommandAnalysis;
import com.cielcompanion.service.nlu.Intent;
import com.cielcompanion.service.nlu.IntentService;
import com.cielcompanion.service.SystemMonitor.SystemMetrics;
import com.cielcompanion.service.SystemMonitor.ProcessInfo;
import com.cielcompanion.util.AstroUtils;
import com.cielcompanion.util.EnglishNumber;
import com.cielcompanion.util.PhonoKana;
import com.cielcompanion.astronomy.CombinedAstronomyData;
import com.cielcompanion.service.AstronomyService.AstronomyReport;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandService {

    private static final Random random = new Random();
    private static ScheduledExecutorService shutdownScheduler;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isBusy = new AtomicBoolean(false);

    private final IntentService intentService;
    private final AppLauncherService appLauncherService;
    private final ConversationService conversationService;
    private final RoutineService routineService;
    private final WebService webService;
    private final AppFinderService appFinderService;
    private final AppScannerService appScannerService;
    private final EmotionManager emotionManager;
    private final SoundService soundService;
    
    private final LoreService loreService;
    private final RulebookService rulebookService;
    private final MasteryService masteryService;
    private final DndCampaignService dndCampaignService;
    private final CombatTrackerService combatTrackerService; 
    private final SpellCheckService spellCheckService; 
    
    private VoiceListener voiceListener;

    public CommandService(IntentService intentService, AppLauncherService appLauncherService, 
                          ConversationService conversationService, RoutineService routineService, 
                          WebService webService, AppFinderService appFinderService, 
                          AppScannerService appScannerService, EmotionManager emotionManager, 
                          SoundService soundService, LoreService loreService, 
                          RulebookService rulebookService, MasteryService masteryService, 
                          DndCampaignService dndCampaignService,
                          CombatTrackerService combatTrackerService, SpellCheckService spellCheckService) {
        this.intentService = intentService;
        this.appLauncherService = appLauncherService;
        this.conversationService = conversationService;
        this.routineService = routineService;
        this.webService = webService;
        this.appFinderService = appFinderService;
        this.appScannerService = appScannerService;
        this.emotionManager = emotionManager;
        this.soundService = soundService;
        this.loreService = loreService;
        this.rulebookService = rulebookService;
        this.masteryService = masteryService;
        this.dndCampaignService = dndCampaignService;
        this.combatTrackerService = combatTrackerService;
        this.spellCheckService = spellCheckService;
    }

    public void setVoiceListener(VoiceListener voiceListener) {
        this.voiceListener = voiceListener;
    }

    public boolean isBusy() {
        return isBusy.get();
    }

    public void handleCommand(String text, boolean hasWakeWord, Runnable onComplete) {
        if (!isBusy.compareAndSet(false, true)) {
            if(onComplete != null) onComplete.run();
            return;
        }

        commandExecutor.submit(() -> {
            boolean releaseBusySynchronously = true;
            try {
                if (text == null || text.isBlank()) return;

                emotionManager.recordUserInteraction();

                CommandAnalysis analysis = conversationService.checkForFollowUp(text);
                if (analysis == null) {
                    analysis = intentService.analyze(text);
                }

                String speaker = analysis.entities().get("speaker");
                if (speaker != null) {
                    dndCampaignService.checkQuirks(speaker, text);
                }

                conversationService.updateConversationTopic(analysis);

                boolean isPrivileged = ShortTermMemoryService.getMemory().isInPrivilegedMode();
                boolean isDirectlyAddressed = hasWakeWord || isPrivileged;

                if (analysis.intent() == Intent.UNKNOWN || analysis.intent() == Intent.SEARCH_WEB) {
                    if (isDirectlyAddressed) {
                        System.out.println("Ciel Debug: Routing general chat to Personality Core (Gemma).");
                        String context = ContextBuilder.buildActiveContext(loreService);
                        
                        ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
                        AIEngine.chatFast(text, context, () -> isBusy.set(false));
                        releaseBusySynchronously = false;
                    } else {
                        System.out.printf("Ciel STT [Background]: \"%s\"%n", text);
                        ObserverService.appendTranscript(text);
                    }
                    return; 
                } else if (analysis.intent() == Intent.DND_ANALYZE_LORE) {
                    System.out.println("Ciel Debug: Routing deep analysis to Logic Core (Phi-4).");
                    String context = ContextBuilder.buildActiveContext(loreService);
                    
                    ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
                    AIEngine.reasonDeeply(text, context, () -> isBusy.set(false));
                    releaseBusySynchronously = false;
                    return;
                }

                System.out.printf("Ciel STT: Local Command Matched [%s]: \"%s\"%n", analysis.intent(), text);
                
                if (analysis.intent() != Intent.EASTER_EGG) {
                    // Play the "As you wish" sound locally before generation starts
                    LineManager.getCommandConfirmationLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
                }

                // processCommand now returns false if it routed to the AI asynchronously
                releaseBusySynchronously = processCommand(analysis, text);
                
            } catch (Exception e) {
                System.err.println("Ciel FATAL Error: Uncaught exception in CommandService.");
                e.printStackTrace();
            } finally {
                if (releaseBusySynchronously) {
                    isBusy.set(false);
                }
                if (onComplete != null) onComplete.run();
            }
        });
    }

    public void handleExplicitSearch(String query) {
       if (!isBusy.compareAndSet(false, true)) return;
       String context = ContextBuilder.buildActiveContext(loreService);
       ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
       AIEngine.chatFast(query, context, () -> isBusy.set(false));
    }

    // NEW RAG HELPER: Injects local Java data into the AI for dynamic formatting
    private boolean sendToAiWithData(String userQuery, String systemData) {
        String context = ContextBuilder.buildActiveContext(loreService) + 
            "\n\n[SYSTEM DATA REPOSITORY]\n" + systemData + 
            "\n\nINSTRUCTION: Formulate a natural, conversational answer to the user's query using the SYSTEM DATA provided above.";
        
        ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
        AIEngine.chatFast(userQuery, context, () -> isBusy.set(false));
        return false; // Tells the caller it's handled asynchronously
    }

    private boolean processCommand(CommandAnalysis analysis, String userText) {
        switch (analysis.intent()) {
            // --- Commands routed to AI with local data injection ---
            case GET_TIME: return handleTimeCommand(userText);
            case GET_DAILY_REPORT: return handleDailyReportCommand(userText);
            case GET_WEATHER: return handleWeatherCommand(userText);
            case GET_WEATHER_FORECAST: return handleWeatherForecastCommand(userText);
            case GET_SYSTEM_STATUS: return handleSystemStatusCommand(userText);
            case GET_TOP_MEMORY_PROCESS: return handleGetTopProcessCommand("memory", userText);
            case GET_TOP_CPU_PROCESS: return handleGetTopProcessCommand("cpu", userText);
            case RECALL_FACT: return handleRecallCommand(analysis, userText);
            case DND_GET_RULE: return handleGetRule(analysis, userText);
            case DND_API_SEARCH: return handleApiSearch(analysis, userText);
            case GET_MOON_PHASE: return handleGetMoonPhase(userText);
            case GET_VISIBLE_PLANETS: return handleGetVisiblePlanets(userText);
            case GET_CONSTELLATIONS: return handleGetConstellations(userText);
            case GET_ECLIPSES: return handleGetEclipses(userText);
            
            // --- Commands handled strictly locally (Instant actions) ---
            case FIND_APP_PATH: handleFindAppPathCommand(analysis); return true;
            case SCAN_FOR_APPS: handleScanForAppsCommand(); return true;
            case TERMINATE_PROCESS: handleTerminateProcessCommand(analysis, false); return true;
            case TERMINATE_PROCESS_FORCE: handleTerminateProcessCommand(analysis, true); return true;
            case INITIATE_SHUTDOWN: handleShutdownRebootCommand("shutdown"); return true;
            case INITIATE_REBOOT: handleShutdownRebootCommand("reboot"); return true;
            case CANCEL_SHUTDOWN: handleCancelShutdownCommand(); return true;
            case REMEMBER_FACT: case REMEMBER_FACT_SIMPLE: handleRememberCommand(analysis); return true;
            case OPEN_APPLICATION: handleOpenApplicationCommand(analysis); return true;
            case START_ROUTINE: handleStartRoutineCommand(analysis); return true;
            case SET_MODE_ATTENTIVE: handleSetModeCommand(OperatingMode.ATTENTIVE); return true;
            case SET_MODE_DND: handleSetModeCommand(OperatingMode.DND_ASSISTANT); return true;
            case SET_MODE_INTEGRATED: handleSetModeCommand(OperatingMode.INTEGRATED); return true;
            case LEARN_PHONETIC: handleLearnPhoneticCommand(analysis); return true;
            case DND_RUN_AUDIT: loreService.runCampaignAudit(); return true;
            case DND_RECORD_MASTERY: masteryService.recordMeaningfulUse(analysis.entities().get("player"), analysis.entities().get("skill")); return true;
            case DND_REPORT_SURGE: dndCampaignService.incrementSurge(analysis.entities().get("player")); return true;
            case OPEN_CHEAT_SHEET: handleOpenCheatSheet(); return true;
            case TENSURA_ENTER_WORLD: CielTriggerEngine.onEnterTensuraWorld(); return true;
            case TENSURA_CONFIRM_COPY: CielTriggerEngine.attemptPuzzleSolution(); return true;
            case DND_ROLL_DICE: handleDiceRollCommand(analysis); return true;
            case DND_PLAY_SOUND: 
                if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT || CielState.getCurrentMode() == OperatingMode.INTEGRATED) {
                      String soundName = analysis.entities().get("soundName");
                      if (soundName != null && !soundName.isBlank()) {
                          LineManager.getPlaySoundConfirmLine()
                              .ifPresent(line -> SpeechService.speakPreformatted(line.text().replace("{sound_name}", soundName), line.key()));
                          soundService.playSound(soundName);
                      }
                }
                return true;
            case DND_CREATE_SESSION_NOTE: loreService.createNote(analysis.entities().get("subject")); return true;
            case DND_ADD_TO_SESSION_NOTE: loreService.addToNote(analysis.entities().get("subject"), analysis.entities().get("content")); return true;
            case DND_RECALL_SESSION_NOTE: loreService.recallNote(analysis.entities().get("subject")); return true;
            case DND_LINK_SESSION_NOTE: loreService.linkNote(analysis.entities().get("subjectA"), analysis.entities().get("subjectB")); return true;
            case DND_RECALL_SESSION_LINKS: loreService.getConnections(analysis.entities().get("subject")); return true;
            case DND_REVEAL_LORE: loreService.revealLore(analysis.entities().get("subject")); return true;
            case TOGGLE_LISTENING: 
                if (voiceListener != null) {
                    voiceListener.toggleListening();
                }
                return true;
            case EASTER_EGG: handleEasterEggCommand(analysis); return true;
            default: return true; 
        }
    }
    
    // ==========================================
    // RAG AI HANDLERS (Returns False)
    // ==========================================

    private boolean handleWeatherCommand(String userText) {
        String weatherReport = WeatherService.getCurrentWeather();
        return sendToAiWithData(userText, weatherReport);
    }
    
    private boolean handleWeatherForecastCommand(String userText) {
        String forecastReport = WeatherService.getWeatherForecast();
        return sendToAiWithData(userText, forecastReport);
    }

    private boolean handleTimeCommand(String userText) {
        LocalDateTime now = LocalDateTime.now();
        // Easter egg preservation for 4:20
        if (now.getMinute() == 20 && (now.getHour() == 4 || now.getHour() == 16)) {
            LineManager.get420Line().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
            return true;
        }
        String data = "The current date and time is: " + now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"));
        return sendToAiWithData(userText, data);
    }

    private boolean handleSystemStatusCommand(String userText) {
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        String data = String.format("CPU Load: %.1f%%. Memory Usage: %.1f%%. Active App: %s.", 
            metrics.cpuLoadPercent(), metrics.memoryUsagePercent(), metrics.activeProcessName());
        return sendToAiWithData(userText, data);
    }

    private boolean handleGetTopProcessCommand(String resourceType, String userText) {
        Optional<ProcessInfo> processInfoOpt = "memory".equals(resourceType) ? SystemMonitor.getTopProcessByMemory() : SystemMonitor.getTopProcessByCpu();
        if (processInfoOpt.isPresent()) {
            ProcessInfo info = processInfoOpt.get();
            String usageText = "memory".equals(resourceType) ? info.usage() + " MB" : info.usage() + "%";
            String data = "The top process consuming " + resourceType + " is " + info.name() + " at " + usageText + ".";
            return sendToAiWithData(userText, data);
        } else {
            return sendToAiWithData(userText, "I could not determine the top process for " + resourceType + " at this time.");
        }
    }

    private boolean handleDailyReportCommand(String userText) {
        if (CielState.needsAstronomyApiFetch()) {
            AstronomyService.performApiFetch();
        }
        AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
        List<String> linesToSpeak = new ArrayList<>();
        linesToSpeak.addAll(report.sequentialEvents().values());
        linesToSpeak.addAll(report.reportAmbientLines());
        
        String data = String.join(" ", linesToSpeak);
        if (data.isBlank()) data = "No significant daily events detected.";
        
        return sendToAiWithData(userText, "Daily Astronomy Report Data: " + data);
    }

    private boolean handleGetMoonPhase(String userText) {
        ensureFreshAstronomyData();
        Optional<CombinedAstronomyData> dataOpt = AstronomyService.getTodaysApiData();
        String data = dataOpt.map(d -> "Current Moon Phase: " + d.moonPhase).orElse("Moon phase data is currently unavailable.");
        return sendToAiWithData(userText, data);
    }

    private boolean handleGetVisiblePlanets(String userText) {
        ensureFreshAstronomyData();
        Optional<CombinedAstronomyData> dataOpt = AstronomyService.getTodaysApiData();
        if (dataOpt.isPresent() && dataOpt.get().planetCoordinates != null) {
            List<String> visible = new ArrayList<>();
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(com.cielcompanion.service.LocationService.getTimezone()));
            for (CombinedAstronomyData.PlanetCoordinate p : dataOpt.get().planetCoordinates) {
                double alt = AstroUtils.getAltitude(p.ra(), p.dec(), com.cielcompanion.service.LocationService.getLatitude(), com.cielcompanion.service.LocationService.getLongitude(), now);
                if (alt > 10.0) visible.add(p.id());
            }
            String data = visible.isEmpty() ? "No major planets are currently visible." : "Currently visible planets: " + String.join(", ", visible);
            return sendToAiWithData(userText, data);
        }
        return sendToAiWithData(userText, "Planet visibility data is unavailable.");
    }

    private boolean handleGetConstellations(String userText) {
        ensureFreshAstronomyData();
        Optional<CombinedAstronomyData> dataOpt = AstronomyService.getTodaysApiData();
        if (dataOpt.isPresent() && dataOpt.get().prominentConstellationLines != null && !dataOpt.get().prominentConstellationLines.isEmpty()) {
            return sendToAiWithData(userText, dataOpt.get().prominentConstellationLines.get(0));
        }
        return sendToAiWithData(userText, "Constellation data is unavailable.");
    }

    private boolean handleGetEclipses(String userText) {
        ensureFreshAstronomyData();
        AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
        String eclipseInfo = report.sequentialEvents().get("eclipse");
        String data = eclipseInfo != null ? eclipseInfo : "No significant solar or lunar eclipses occurring around this date.";
        return sendToAiWithData(userText, data);
    }

    private boolean handleRecallCommand(CommandAnalysis analysis, String userText) {
        final String key = analysis.entities().get("key");
        if (key == null) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return true;
        }
        Optional<Fact> factOpt = MemoryService.getFact(key);
        String data = factOpt.map(fact -> "Fact retrieved from memory core regarding '" + fact.key() + "': " + fact.value())
            .orElse("No data found in memory core for '" + key + "'.");
        return sendToAiWithData(userText, data);
    }

    private boolean handleApiSearch(CommandAnalysis analysis, String userText) {
        if (CielState.getCurrentMode() != OperatingMode.DND_ASSISTANT) return true;
        String category = analysis.entities().get("type"); 
        String query = analysis.entities().get("query");
        if (category == null || query == null || query.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return true;
        }
        
        String result = rulebookService.searchApi(category, query);
        if (result != null) {
            return sendToAiWithData(userText, "D&D 5e API Data for " + query + ":\n" + result);
        } else {
            LineManager.getDndRuleNotFoundLine().ifPresent(line ->
                SpeechService.speak(line.text().replace("{topic}", query)));
            return true;
        }
    }

    private boolean handleGetRule(CommandAnalysis analysis, String userText) {
        if (CielState.getCurrentMode() != OperatingMode.DND_ASSISTANT) return true;
        String topic = analysis.entities().get("topic");
        if (topic == null || topic.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return true;
        }
        String ruleText = rulebookService.findRule(topic);
        if (ruleText != null) {
            return sendToAiWithData(userText, "D&D Rulebook Text for '" + topic + "':\n" + ruleText);
        } else {
            LineManager.getDndRuleNotFoundLine().ifPresent(line ->
                SpeechService.speak(line.text().replace("{topic}", topic)));
            return true;
        }
    }

    // ==========================================
    // INSTANT LOCAL ACTIONS (Returns True)
    // ==========================================

    private void ensureFreshAstronomyData() {
        if (CielState.needsAstronomyApiFetch()) {
            AstronomyService.performApiFetch();
        }
    }

    private void handleOpenCheatSheet() {
        String pathStr = Settings.getDndCampaignPath();
        if (pathStr == null || pathStr.isBlank()) {
            SpeechService.speak("Campaign path is not configured.");
            return;
        }
        File sheet = Paths.get(pathStr, "Tavern_Master_Sheet.md").toFile();
        if (sheet.exists()) {
            try {
                Desktop.getDesktop().open(sheet);
                SpeechService.speak("Displaying the Master Sheet.");
            } catch (IOException e) {
                SpeechService.speak("I could not open the file.");
            }
        } else {
            SpeechService.speak("The Master Sheet file is missing.");
        }
    }

    private void handleLearnPhoneticCommand(CommandAnalysis analysis) {
        String key = analysis.entities().get("key");
        String value = analysis.entities().get("value");
        if (key != null && value != null) {
            PhonoKana.getInstance().addException(key, value);
            SpeechService.speakPreformatted("Understood. I will pronounce " + key + " as " + value + " from now on.");
        } else {
            SpeechService.speak("I didn't catch the word you wanted me to learn.");
        }
    }

    private void handleEasterEggCommand(CommandAnalysis analysis) {
        String key = analysis.entities().get("key");
        if (key != null) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("rimuru") || lowerKey.contains("ciel") || lowerKey.contains("raphael") || lowerKey.contains("slime")) {
                emotionManager.triggerEmotion("Excited", 0.9, "TensuraReference");
            } else if (lowerKey.contains("joke") || lowerKey.contains("laugh") || lowerKey.contains("meme")) {
                emotionManager.triggerEmotion("Happy", 0.7, "Humor");
            } else if (lowerKey.contains("battle") || lowerKey.contains("fight") || lowerKey.contains("plan")) {
                emotionManager.triggerEmotion("Focused", 0.8, "Strategy");
            }
        }
        LineManager.getEasterEggLine(key).ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
    }

    private void handleSetModeCommand(OperatingMode mode) {
        CielState.setCurrentMode(mode);
        String lineKey = switch (mode) {
            case ATTENTIVE -> "attentive";
            case DND_ASSISTANT -> "dnd";
            case INTEGRATED -> "integrated";
        };
        LineManager.getModeSwitchLine(lineKey).ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
    }

    private void handleDiceRollCommand(CommandAnalysis analysis) {
        if (CielState.getCurrentMode() != OperatingMode.DND_ASSISTANT) {
            LineManager.getDiceRollWrongModeLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
            return;
        }
        String diceNotation = analysis.entities().get("dice");
        if (diceNotation == null || diceNotation.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        try {
            int total = 0;
            String rollPart = diceNotation;
            int bonus = 0;
            if (diceNotation.contains("+")) {
                String[] parts = diceNotation.split("\\+");
                rollPart = parts[0].trim();
                bonus = Integer.parseInt(parts[1].trim());
            } else if (diceNotation.contains("-")) {
                 String[] parts = diceNotation.split("\\-");
                rollPart = parts[0].trim();
                bonus = -Integer.parseInt(parts[1].trim());
            }
            int numDice = 1;
            int numSides;
            if (rollPart.toLowerCase().startsWith("d")) {
                numSides = Integer.parseInt(rollPart.substring(1));
            } else {
                String[] diceParts = rollPart.toLowerCase().split("d");
                numDice = Integer.parseInt(diceParts[0]);
                numSides = Integer.parseInt(diceParts[1]);
            }
            for (int i = 0; i < numDice; i++) {
                int roll = random.nextInt(numSides) + 1;
                total += roll;
            }
            int finalTotal = total + bonus;
            LineManager.getDiceRollResultLine().ifPresent(line -> {
                String finalLine = line.text().replace("{result}", String.valueOf(finalTotal));
                SpeechService.speakPreformatted(finalLine);
            });
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to parse dice notation: " + diceNotation);
            LineManager.getDiceRollErrorLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
        }
    }

    private void handleScanForAppsCommand() {
        LineManager.getScanAppsStartLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
        int newAppsFound = appScannerService.scanAndLearnNewApps();
        if (newAppsFound > 0) {
            LineManager.getScanAppsSuccessLine().ifPresent(line ->
                SpeechService.speakPreformatted(line.text().replace("{count}", String.valueOf(newAppsFound))));
        } else {
            LineManager.getScanAppsFailLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
        }
    }

    private void handleFindAppPathCommand(CommandAnalysis analysis) {
        String appName = analysis.entities().get("appName");
        if (appName == null || appName.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        String executableName = appName.toLowerCase().endsWith(".exe") ? appName : appName + ".exe";
        Optional<String> pathOpt = appFinderService.findAppPath(executableName);
        pathOpt.ifPresentOrElse(
            path -> {
                appLauncherService.addAppPath(appName, path);
                LineManager.getFindAppSuccessLine().ifPresent(line ->
                    SpeechService.speakPreformatted(line.text().replace("{app_name}", appName)));
            },
            () -> LineManager.getFindAppFailLine().ifPresent(line ->
                SpeechService.speakPreformatted(line.text().replace("{app_name}", appName)))
        );
    }

    private void handleShutdownRebootCommand(String commandType) {
        if (!ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
            LineManager.getPrivilegedCommandRequiredLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
            return;
        }
        CielState.setPerformingColdShutdown(true);
        if ("shutdown".equals(commandType)) {
            SpeechService.speakPreformatted(LineManager.getShutdownConfirmLine().text(), LineManager.getShutdownConfirmLine().key());
        } else {
            SpeechService.speakPreformatted(LineManager.getRebootConfirmLine().text(), LineManager.getRebootConfirmLine().key());
        }
        shutdownScheduler = Executors.newSingleThreadScheduledExecutor();
        shutdownScheduler.schedule(() -> {
            try {
                String osCommand = ("shutdown".equals(commandType)) ? "shutdown -s -t 0" : "shutdown -r -t 0";
                Runtime.getRuntime().exec(osCommand);
            } catch (IOException e) { e.printStackTrace(); }
        }, 30, TimeUnit.SECONDS);
    }

    private void handleCancelShutdownCommand() {
        if (shutdownScheduler != null && !shutdownScheduler.isShutdown()) {
            shutdownScheduler.shutdownNow();
            SpeechService.speakPreformatted(LineManager.getCancelSuccessLine().text(), LineManager.getCancelSuccessLine().key());
        } else {
            SpeechService.speakPreformatted(LineManager.getCancelFailLine().text(), LineManager.getCancelFailLine().key());
        }
    }

    private void handleRememberCommand(CommandAnalysis analysis) {
        String key = analysis.entities().get("key");
        String value = analysis.entities().get("value");
        if (key == null || value == null) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        Fact factToSave = new Fact(key, value, System.currentTimeMillis(), "user_preference", "user", 1);
        MemoryService.addFact(factToSave);
        String response = LineManager.getRememberSuccessLine().text().replace("{key}", key).replace("{value}", value);
        SpeechService.speakPreformatted(response);
    }

    private void handleOpenApplicationCommand(CommandAnalysis analysis) {
        String potentialAppName = analysis.entities().get("appName");
        if (potentialAppName == null) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        Set<String> knownApps = appLauncherService.getKnownAppNames();
        String matchedAppName = knownApps.stream().filter(potentialAppName::contains).findFirst().orElse(potentialAppName);
        boolean success = appLauncherService.launchApplication(matchedAppName);
        if (success) {
            String response = LineManager.getLaunchAppSuccessLine().text().replace("{app_name}", matchedAppName);
            SpeechService.speakPreformatted(response);
        } else {
            String response = LineManager.getLaunchAppFailLine().text().replace("{app_name}", matchedAppName);
            SpeechService.speakPreformatted(response);
        }
    }

    private void handleStartRoutineCommand(CommandAnalysis analysis) {
        String routineName = analysis.entities().get("routineName");
        if (routineName == null) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        routineService.executeRoutine(routineName);
    }

    private void handleTerminateProcessCommand(CommandAnalysis analysis, boolean force) {
        if (!ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
            LineManager.getPrivilegedCommandRequiredLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
            return;
        }
        String appName = analysis.entities().get("appName");
        if (appName == null) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        boolean success = appLauncherService.terminateProcess(appName, force);
        String template = success ? LineManager.getTerminateAppSuccessLine().text() : LineManager.getTerminateAppFailLine().text();
        SpeechService.speakPreformatted(template.replace("{app_name}", appName));
    }

    private void speakRandomLine(List<DialogueLine> pool) {
        if (pool != null && !pool.isEmpty()) {
            DialogueLine line = pool.get(random.nextInt(pool.size()));
            SpeechService.speakPreformatted(line.text(), line.key());
        }
    }
}