package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.dnd.LoreService;
import com.cielcompanion.dnd.RulebookService;
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
import com.cielcompanion.util.AstroUtils; // ADDED
import com.cielcompanion.util.EnglishNumber;
import com.cielcompanion.astronomy.CombinedAstronomyData;
import com.cielcompanion.service.AstronomyService.AstronomyReport;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId; // ADDED
import java.time.ZonedDateTime; // ADDED
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
    private VoiceListener voiceListener;

    public CommandService(IntentService intentService, AppLauncherService appLauncherService, ConversationService conversationService, RoutineService routineService, WebService webService, AppFinderService appFinderService, AppScannerService appScannerService, EmotionManager emotionManager, SoundService soundService, LoreService loreService, RulebookService rulebookService) {
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
    }

    public void setVoiceListener(VoiceListener voiceListener) {
        this.voiceListener = voiceListener;
    }

    public boolean isBusy() {
        return isBusy.get();
    }

    public void handleCommand(String text, Runnable onComplete) {
        if (!isBusy.compareAndSet(false, true)) {
            if(onComplete != null) onComplete.run();
            return;
        }

        commandExecutor.submit(() -> {
            try {
                if (text == null || text.isBlank()) return;

                emotionManager.recordUserInteraction();

                CommandAnalysis analysis = conversationService.checkForFollowUp(text);
                if (analysis == null) {
                    analysis = intentService.analyze(text);
                }

                conversationService.updateConversationTopic(analysis);

                if (analysis.intent() != Intent.UNKNOWN && analysis.intent() != Intent.SEARCH_WEB && analysis.intent() != Intent.EASTER_EGG) {
                    LineManager.getCommandConfirmationLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
                }

                processCommand(analysis);
            } catch (Exception e) {
                System.err.println("Ciel FATAL Error: Uncaught exception in CommandService. This indicates a serious problem.");
                e.printStackTrace();
            } finally {
                isBusy.set(false);
                if (onComplete != null) onComplete.run();
            }
        });
    }

    public void handleExplicitSearch(String query) {
       if (!isBusy.compareAndSet(false, true)) {
           return;
       }
        commandExecutor.submit(() -> {
            try {
                emotionManager.recordUserInteraction();
                webService.answerQuestion(query);
            } finally {
                isBusy.set(false);
            }
        });
    }

    private void processCommand(CommandAnalysis analysis) {
        switch (analysis.intent()) {
            case GET_TIME: handleTimeCommand(); break;
            case GET_DAILY_REPORT: handleDailyReportCommand(); break;
            case GET_WEATHER: handleWeatherCommand(); break;
            case GET_WEATHER_FORECAST: handleWeatherForecastCommand(); break;
            case SEARCH_WEB: handleWebSearchCommand(analysis); break;
            case FIND_APP_PATH: handleFindAppPathCommand(analysis); break;
            case SCAN_FOR_APPS: handleScanForAppsCommand(); break;
            case GET_SYSTEM_STATUS: handleSystemStatusCommand(); break;
            case GET_TOP_MEMORY_PROCESS: handleGetTopProcessCommand("memory"); break;
            case GET_TOP_CPU_PROCESS: handleGetTopProcessCommand("cpu"); break;
            case TERMINATE_PROCESS: handleTerminateProcessCommand(analysis, false); break;
            case TERMINATE_PROCESS_FORCE: handleTerminateProcessCommand(analysis, true); break;
            case INITIATE_SHUTDOWN: handleShutdownRebootCommand("shutdown"); break;
            case INITIATE_REBOOT: handleShutdownRebootCommand("reboot"); break;
            case CANCEL_SHUTDOWN: handleCancelShutdownCommand(); break;
            case REMEMBER_FACT: case REMEMBER_FACT_SIMPLE: handleRememberCommand(analysis); break;
            case RECALL_FACT: handleRecallCommand(analysis); break;
            case OPEN_APPLICATION: handleOpenApplicationCommand(analysis); break;
            case START_ROUTINE: handleStartRoutineCommand(analysis); break;
            case SET_MODE_ATTENTIVE: handleSetModeCommand(OperatingMode.ATTENTIVE); break;
            case SET_MODE_DND: handleSetModeCommand(OperatingMode.DND_ASSISTANT); break;
            case SET_MODE_INTEGRATED: handleSetModeCommand(OperatingMode.INTEGRATED); break;
            
            case DND_ROLL_DICE: handleDiceRollCommand(analysis); break;
            case DND_PLAY_SOUND: handlePlaySoundCommand(analysis); break;
            case DND_CREATE_SESSION_NOTE: loreService.createNote(analysis.entities().get("subject")); break;
            case DND_ADD_TO_SESSION_NOTE: loreService.addToNote(analysis.entities().get("subject"), analysis.entities().get("content")); break;
            case DND_RECALL_SESSION_NOTE: loreService.recallNote(analysis.entities().get("subject")); break;
            case DND_LINK_SESSION_NOTE: loreService.linkNote(analysis.entities().get("subjectA"), analysis.entities().get("subjectB")); break;
            case DND_RECALL_SESSION_LINKS: loreService.getConnections(analysis.entities().get("subject")); break;
            case DND_REVEAL_LORE: loreService.revealLore(analysis.entities().get("subject")); break;
            case DND_ANALYZE_LORE: loreService.analyzeLore(analysis.entities().get("subject")); break;
            case DND_GET_RULE: handleGetRule(analysis); break;
            case DND_API_SEARCH: handleApiSearch(analysis); break;

            case GET_MOON_PHASE: handleGetMoonPhase(); break;
            case GET_VISIBLE_PLANETS: handleGetVisiblePlanets(); break;
            case GET_CONSTELLATIONS: handleGetConstellations(); break;
            case GET_ECLIPSES: handleGetEclipses(); break;

            case TOGGLE_LISTENING: 
                if (voiceListener != null) {
                    voiceListener.toggleListening();
                }
                break;
            case EASTER_EGG: handleEasterEggCommand(analysis); break;
            case UNKNOWN: default: speakRandomLine(LineManager.getUnrecognizedLines()); break;
        }
    }
    
    // ... [D&D Methods] ...
    
    private void handleApiSearch(CommandAnalysis analysis) {
        if (CielState.getCurrentMode() != OperatingMode.DND_ASSISTANT) return;
        String category = analysis.entities().get("type"); 
        String query = analysis.entities().get("query");
        if (category == null || query == null || query.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }

        commandExecutor.submit(() -> {
            String result = rulebookService.searchApi(category, query);
            if (result != null) {
                SpeechService.speak("Analysis of external databanks complete. " + result);
            } else {
                LineManager.getDndRuleNotFoundLine().ifPresent(line ->
                    SpeechService.speak(line.text().replace("{topic}", query)));
            }
        });
    }

    private void handleGetRule(CommandAnalysis analysis) {
        if (CielState.getCurrentMode() != OperatingMode.DND_ASSISTANT) return;
        String topic = analysis.entities().get("topic");
        if (topic == null || topic.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }

        String ruleText = rulebookService.findRule(topic);
        if (ruleText != null) {
            LineManager.getDndRuleFoundLine().ifPresent(line ->
                SpeechService.speak(line.text().replace("{topic}", topic) + " " + ruleText));
        } else {
            LineManager.getDndRuleNotFoundLine().ifPresent(line ->
                SpeechService.speak(line.text().replace("{topic}", topic)));
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
    
    private void handlePlaySoundCommand(CommandAnalysis analysis) {
        if (CielState.getCurrentMode() != OperatingMode.DND_ASSISTANT) {
            return;
        }
        String soundName = analysis.entities().get("soundName");
        if (soundName != null && !soundName.isBlank()) {
            soundService.playSound(soundName);
            LineManager.getPlaySoundConfirmLine()
                .ifPresent(line -> SpeechService.speakPreformatted(line.text().replace("{sound_name}", soundName)));
        }
    }

    private void handleScanForAppsCommand() {
        LineManager.getScanAppsStartLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
        
        int newAppsFound = appScannerService.scanAndLearnNewApps();
        if (newAppsFound > 0) {
            LineManager.getScanAppsSuccessLine().ifPresent(line ->
                SpeechService.speakPreformatted(line.text().replace("{count}", String.valueOf(newAppsFound)))
            );
        } else {
            LineManager.getScanAppsFailLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
        }
    }

    private void handleWebSearchCommand(CommandAnalysis analysis) {
        String query = analysis.entities().get("query");
        if (query == null || query.isBlank()) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        webService.answerQuestion(query);
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
                    SpeechService.speakPreformatted(line.text().replace("{app_name}", appName))
                );
            },
            () -> LineManager.getFindAppFailLine().ifPresent(line ->
                SpeechService.speakPreformatted(line.text().replace("{app_name}", appName))
            )
        );
    }

    private void handleWeatherCommand() {
        String weatherReport = WeatherService.getCurrentWeather();
        SpeechService.speak(weatherReport);
    }
    
    private void handleWeatherForecastCommand() {
        String forecastReport = WeatherService.getWeatherForecast();
        SpeechService.speak(forecastReport);
    }

    private void handleDailyReportCommand() {
        if (CielState.needsAstronomyApiFetch()) {
            System.out.println("Ciel Debug (CommandService): Proactively fetching fresh astronomy data for daily report.");
            AstronomyService.performApiFetch();
        }

        AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
        List<String> linesToSpeak = new ArrayList<>();
        linesToSpeak.addAll(report.sequentialEvents().values());
        linesToSpeak.addAll(report.reportAmbientLines());

        CielController.speakSpecialEventsSequentially(linesToSpeak, null);
    }

    private void handleTimeCommand() {
        LocalDateTime now = LocalDateTime.now();
        
        if (now.getMinute() == 20 && (now.getHour() == 4 || now.getHour() == 16)) {
            LineManager.get420Line().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
            return;
        }

        String month = now.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String day = EnglishNumber.convert(String.valueOf(now.getDayOfMonth()));
        String formattedTime = now.format(DateTimeFormatter.ofPattern("h:mm a"));
        String spokenTime = EnglishNumber.convertTimeToWords(formattedTime);
        DialogueLine line = LineManager.getTimeLines().get(random.nextInt(LineManager.getTimeLines().size()));
        
        String finalLine = line.text()
            .replace("{month}", month)
            .replace("{day}", day)
            .replace("{time}", spokenTime);
        SpeechService.speakPreformatted(finalLine);
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
            } catch (IOException e) {
                e.printStackTrace();
            }
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

    private void handleSystemStatusCommand() {
        SystemMetrics metrics = SystemMonitor.getSystemMetrics();
        List<DialogueLine> reportLines = new ArrayList<>();
        reportLines.add(LineManager.getStatusIntroLines().get(random.nextInt(LineManager.getStatusIntroLines().size())));
        
        reportLines.add(new DialogueLine(null, LineManager.getStatusCpuLine().text().replace("{cpu_load}", EnglishNumber.convert(String.valueOf(metrics.cpuLoadPercent())))));
        reportLines.add(new DialogueLine(null, LineManager.getStatusMemLine().text().replace("{mem_usage}", EnglishNumber.convert(String.valueOf(metrics.memoryUsagePercent())))));
        
        reportLines.add(LineManager.getStatusOutroLines().get(random.nextInt(LineManager.getStatusOutroLines().size())));
        
        SpeechService.speakSequentially(reportLines, 1500, true, null);
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
        String response = LineManager.getRememberSuccessLine().text()
            .replace("{key}", key)
            .replace("{value}", value);
        SpeechService.speakPreformatted(response);
    }

    private void handleRecallCommand(CommandAnalysis analysis) {
        final String key = analysis.entities().get("key");
        if (key == null) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        MemoryService.getFact(key).ifPresentOrElse(
            fact -> {
                String response = LineManager.getRecallSuccessLine().text()
                    .replace("{key}", fact.key())
                    .replace("{value}", fact.value());
                SpeechService.speakPreformatted(response);
            },
            () -> SpeechService.speakPreformatted(LineManager.getRecallFailLine().text().replace("{key}", key))
        );
    }

    private void handleOpenApplicationCommand(CommandAnalysis analysis) {
        String potentialAppName = analysis.entities().get("appName");
        if (potentialAppName == null) {
            speakRandomLine(LineManager.getUnrecognizedLines());
            return;
        }
        Set<String> knownApps = appLauncherService.getKnownAppNames();
        String matchedAppName = knownApps.stream()
            .filter(potentialAppName::contains)
            .findFirst()
            .orElse(potentialAppName);
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

    private void handleGetTopProcessCommand(String resourceType) {
        Optional<ProcessInfo> processInfoOpt = "memory".equals(resourceType)
            ? SystemMonitor.getTopProcessByMemory()
            : SystemMonitor.getTopProcessByCpu();
        processInfoOpt.ifPresentOrElse(
            info -> {
                String lineTemplate = "memory".equals(resourceType)
                    ? LineManager.getTopMemoryProcessLine().text()
                    : LineManager.getTopCpuProcessLine().text();

                String usageText = "memory".equals(resourceType)
                    ? info.usage() + " megabytes"
                    : info.usage() + " percent";
                String response = lineTemplate
                    .replace("{process_name}", info.name())
                    .replace("{usage}", usageText);
                SpeechService.speak(response);
            },
            () -> SpeechService.speak("I was unable to determine the top process at this time.")
        );
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
        String template = success
            ? LineManager.getTerminateAppSuccessLine().text()
            : LineManager.getTerminateAppFailLine().text();
        SpeechService.speakPreformatted(template.replace("{app_name}", appName));
    }

    private void speakRandomLine(List<DialogueLine> pool) {
        if (pool != null && !pool.isEmpty()) {
            DialogueLine line = pool.get(random.nextInt(pool.size()));
            SpeechService.speakPreformatted(line.text(), line.key());
        }
    }

    // --- NEW On-Demand Astronomy Handlers (Dynamic Calculation) ---
    private void ensureFreshAstronomyData() {
        if (CielState.needsAstronomyApiFetch()) {
            System.out.println("Ciel Debug (CommandService): Proactively fetching fresh astronomy data for on-demand query.");
            AstronomyService.performApiFetch();
        }
    }
    
    private void handleGetMoonPhase() {
        ensureFreshAstronomyData();
        Optional<CombinedAstronomyData> data = AstronomyService.getTodaysApiData();
        if (data.isPresent() && data.get().moonPhase != null) {
            String simplifiedPhase = data.get().moonPhase.toLowerCase().replace(" ", "").replace("_", "");
            LineManager.getMoonPhaseLine(simplifiedPhase)
                .ifPresent(line -> SpeechService.speak(line.text(), line.key())); 
        } else {
            SpeechService.speak("I am currently unable to retrieve the moon phase information.");
        }
    }

    private void handleGetVisiblePlanets() {
        ensureFreshAstronomyData();
        Optional<CombinedAstronomyData> data = AstronomyService.getTodaysApiData();
        
        if (data.isPresent() && data.get().planetCoordinates != null && !data.get().planetCoordinates.isEmpty()) {
            List<String> visibleLines = new ArrayList<>();
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(LocationService.getTimezone()));
            
            // Calculate dynamic visibility based on cached coordinates
            for (CombinedAstronomyData.PlanetCoordinate p : data.get().planetCoordinates) {
                double alt = AstroUtils.getAltitude(p.ra(), p.dec(), LocationService.getLatitude(), LocationService.getLongitude(), now);
                if (alt > 10.0) {
                     LineManager.getPlanetLine(p.id()).ifPresent(line -> visibleLines.add(line.text()));
                }
            }
            
            // Merge Jupiter/Saturn logic
            boolean jupiter = visibleLines.stream().anyMatch(s -> s.contains("Jupiter") || s.contains("ジュピター"));
            boolean saturn = visibleLines.stream().anyMatch(s -> s.contains("Saturn") || s.contains("サターン"));
            if (jupiter && saturn) {
                visibleLines.removeIf(s -> s.contains("Jupiter") || s.contains("ジュピター") || s.contains("Saturn") || s.contains("サターン"));
                LineManager.getPlanetLine("jupitersaturn").ifPresent(line -> visibleLines.add(0, line.text()));
            }

            if (!visibleLines.isEmpty()) {
                SpeechService.speak(String.join(" ", visibleLines));
            } else {
                SpeechService.speak("I could not detect any major planets visible in the sky at this time.");
            }
        } else {
            SpeechService.speak("I could not detect any major planets visible in the sky at this time.");
        }
    }

    private void handleGetConstellations() {
        ensureFreshAstronomyData();
        Optional<CombinedAstronomyData> data = AstronomyService.getTodaysApiData();
        if (data.isPresent() && data.get().prominentConstellationLines != null && !data.get().prominentConstellationLines.isEmpty()) {
            SpeechService.speak(data.get().prominentConstellationLines.get(0));
        } else {
            SpeechService.speak("I am unable to determine the visible constellations at this moment.");
        }
    }

    private void handleGetEclipses() {
        ensureFreshAstronomyData();
        AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
        String eclipseInfo = report.sequentialEvents().get("eclipse");
        if (eclipseInfo != null) {
            SpeechService.speak(eclipseInfo);
        } else {
            SpeechService.speak("My analysis shows no significant solar or lunar eclipses occurring around this date.");
        }
    }
}