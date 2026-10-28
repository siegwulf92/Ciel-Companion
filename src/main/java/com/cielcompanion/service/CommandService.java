package com.cielcompanion.service;

import com.cielcompanion.CielCompanion;
import com.cielcompanion.CielState;
import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.ai.ContextBuilder;
import com.cielcompanion.ai.ObserverService;
import com.cielcompanion.ai.PhonoKanaSanitizer; 
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
import com.cielcompanion.util.PhonoKana;
import com.cielcompanion.util.ProjectPaths;
import com.cielcompanion.astronomy.CombinedAstronomyData;
import com.cielcompanion.service.AstronomyService.AstronomyReport;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandService {

    private static final Random random = new Random();

    // -----------------------------------------------------------------
    //  Fields for abort-able countdown (shutdown / reboot / update)
    // -----------------------------------------------------------------
    private static ScheduledExecutorService shutdownScheduler;
    private static final Object shutdownLock = new Object();
    private static final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

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

    private void handleDynamicPcControl(CommandAnalysis analysis) {
        String query = analysis.entities().get("query");
        
        if (!ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
            System.out.println("Ciel Debug: Holding dynamic PC control request pending authorization.");
            ShortTermMemoryService.getMemory().setPendingSystemTask(query);
            SpeechService.speakPreformatted("System alteration requested. Please authenticate your Voice Attack credentials to authorize skill synthesis.");
            return;
        }

        com.cielcompanion.service.SkillCrafterService.synthesizeNewSkill(query);
    }

    public void setVoiceListener(VoiceListener voiceListener) {
        this.voiceListener = voiceListener;
    }

    public boolean isBusy() {
        return isBusy.get();
    }

    // NEW: Aggressive Failsafe STT Engine for garbled microphone input.
    private CommandAnalysis verifyIntentWithAI(String activeText) {
        System.out.println("Ciel Debug: Initiating Aggressive Failsafe STT Feedback Loop for unrecognized command...");
        String knownSkills = "";
        try { knownSkills = com.cielcompanion.ai.SkillManager.getAvailableSkillsString(); } catch(Exception e){}
        
        String prompt = "You are a critical STT (Speech-to-Text) error-correction engine for Ciel's core. The microphone captured garbled audio.\n" +
            "Transcript: \"" + activeText + "\"\n\n" +
            "Your objective is to map this to the CLOSEST valid system command if the user is clearly trying to give an order, rather than just chatting.\n" +
            "Valid intents:\n" +
            "1. INITIATE_SHUTDOWN (phonetically similar to: shut down, turn off, take down, power off, should town)\n" +
            "2. INITIATE_REBOOT (phonetically similar to: reboot, restart, re boot)\n" +
            "3. CANCEL_SHUTDOWN (phonetically similar to: abort, stop shutdown, cancel it)\n" +
            "4. UPDATE_SYSTEM (phonetically similar to: update, upgrade, up date)\n" +
            "5. TOGGLE_LISTENING (phonetically similar to: mute mic, stop listening, wake up)\n" +
            "6. EASTER_EGG (phonetically similar to: rimuru, ciel, raphael, joke)\n" +
            "7. EXECUTE_SKILL (phonetically similar to any of these skills: [" + knownSkills + "])\n\n" +
            "Return JSON ONLY. If it matches a command, return: {\"confidence\": 95, \"intent\": \"[INTENT_NAME]\", \"corrected_text\": \"[The fixed phrase]\"}\n" +
            "If it's just normal conversation, return: {\"confidence\": 0, \"intent\": \"UNKNOWN\"}";

        try {
            String response = AIEngine.generateSilentLogic(activeText, prompt, "Voice Command Context Analysis").join();
            if (response != null) {
                int confidence = 0;
                String intentStr = "UNKNOWN";
                String corrected = activeText;

                java.util.regex.Matcher confMatcher = java.util.regex.Pattern.compile("\"confidence\"\\s*:\\s*(\\d+)").matcher(response);
                if (confMatcher.find()) confidence = Integer.parseInt(confMatcher.group(1));

                java.util.regex.Matcher intMatcher = java.util.regex.Pattern.compile("\"intent\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
                if (intMatcher.find()) intentStr = intMatcher.group(1);

                java.util.regex.Matcher corrMatcher = java.util.regex.Pattern.compile("\"corrected_text\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
                if (corrMatcher.find()) corrected = corrMatcher.group(1);

                if (confidence >= 80 && !intentStr.equals("UNKNOWN") && !intentStr.isEmpty()) {
                    System.out.println("Ciel Debug: Failsafe Loop recovered intent [" + intentStr + "] with " + confidence + "% confidence. Corrected to: " + corrected);
                    Map<String, String> entities = new HashMap<>();
                    entities.put("query", corrected);
                    if (intentStr.equals("EASTER_EGG")) entities.put("key", corrected);
                    if (intentStr.equals("EXECUTE_SKILL")) entities.put("skill", corrected);
                    
                    return new CommandAnalysis(Intent.valueOf(intentStr), entities);
                } else {
                    System.out.println("Ciel Debug: Failsafe Loop confidence too low (" + confidence + "%). Remaining UNKNOWN.");
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failsafe Loop execution failed.");
        }
        return null;
    }

    public void handleCommand(String originalText, boolean hasWakeWord, Runnable onComplete) {
        if (originalText == null || originalText.isBlank()) {
            if(onComplete != null) onComplete.run();
            return;
        }

        Matcher codexRequest = Pattern.compile("(?i)^\\s*(?:ciel[, ]+)?(?:(?:can|could|would)\\s+you\\s+)?(?:ask|tell|have|use)\\s+codex(?:\\s+to)?\\s+(.+)$").matcher(originalText);
        if (codexRequest.matches()) {
            boolean explicitlyRequestsEdits = Pattern.compile("(?i)\\b(fix|change|edit|implement|modify|refactor|write|update)\\b")
                    .matcher(codexRequest.group(1)).find();
            if (!hasWakeWord && !ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
                System.out.println("Ciel Debug: Ignored background speech that resembled a Codex task; direct address is required.");
                if (onComplete != null) onComplete.run();
                return;
            }
            if (!isBusy.compareAndSet(false, true)) {
                SpeechService.speakPreformatted("[Impatient] I'm already handling another request, Master.");
                if (onComplete != null) onComplete.run();
                return;
            }
            String task = codexRequest.group(1).trim();
            AIEngine.recordExternalTurn("user", task);
            String access = explicitlyRequestsEdits ? "workspace-write" : "read-only";
            System.out.println("Ciel Debug: Explicit Codex request routed with " + access + " access.");
            SpeechService.speakPreformatted("[Focused] I'll ask Codex to handle that. Its access is limited to this project, and I'll report back when it finishes.");
            CodexAgentService.runUserTask(task, explicitlyRequestsEdits).whenComplete((result, error) -> {
                try {
                    if (error != null) {
                        String reason = error.getCause() != null && error.getCause().getMessage() != null
                                ? error.getCause().getMessage() : error.getMessage();
                        System.err.println("Ciel Error: Codex request failed: " + reason);
                        SpeechService.speakPreformatted("[Concerned] Codex could not complete that task. The details are in the Ciel log.");
                    } else {
                        SpeechService.speakPreformatted("[Focused] " + result);
                    }
                } finally {
                    isBusy.set(false);
                    if (onComplete != null) onComplete.run();
                }
            });
            return;
        }

        System.out.printf("Ciel STT [Raw]: \"%s\"%n", originalText);

        CommandAnalysis preAnalysis = intentService.analyze(originalText);
        String lowerText = originalText.toLowerCase();
        
        // Expanded regex to aggressively catch all shutdown variations instantly natively in Java
        boolean isCriticalOverride = (
            preAnalysis.intent() == Intent.INITIATE_REBOOT || 
            preAnalysis.intent() == Intent.INITIATE_SHUTDOWN || 
            preAnalysis.intent() == Intent.CANCEL_SHUTDOWN ||
            preAnalysis.intent() == Intent.UPDATE_SYSTEM ||
            lowerText.contains("update") || lowerText.contains("upgrade") ||
            lowerText.matches(".*(shut\\s*down|turn\\s*off|take\\s*down|took\\s*down|power\\s*off).*") ||
            lowerText.contains("reboot") || lowerText.contains("restart")
        );

        if (!isCriticalOverride) {
            if (!isBusy.compareAndSet(false, true)) {
                System.out.println("Ciel Debug: Command dropped. AI is currently busy.");
                if(onComplete != null) onComplete.run();
                return;
            }
        } else {
            System.out.println("Ciel Debug: Critical Command Detected. Piercing AI Busy Lock!");
            isBusy.set(true); 
        }

        commandExecutor.submit(() -> {
            boolean releaseBusySynchronously = true;
            try {
                String activeText = originalText;

                emotionManager.recordUserInteraction();

                CommandAnalysis analysis = conversationService.checkForFollowUp(activeText);
                if (analysis == null) {
                    analysis = preAnalysis;
                }

                String speaker = analysis.entities().get("speaker");
                if (speaker != null) {
                    dndCampaignService.checkQuirks(speaker, activeText);
                }

                conversationService.updateConversationTopic(analysis);

                boolean isPrivileged = ShortTermMemoryService.getMemory().isInPrivilegedMode();
                boolean isDirectlyAddressed = hasWakeWord || isPrivileged;

                if (lowerText.contains("update") || lowerText.contains("upgrade")) {
                    analysis = new CommandAnalysis(Intent.UPDATE_SYSTEM, new HashMap<>());
                } else if (lowerText.matches(".*(shut\\s*down|turn\\s*off|take\\s*down|took\\s*down|power\\s*off).*")) {
                    analysis = new CommandAnalysis(Intent.INITIATE_SHUTDOWN, new HashMap<>());
                } else if (lowerText.contains("reboot") || lowerText.contains("restart")) {
                    analysis = new CommandAnalysis(Intent.INITIATE_REBOOT, new HashMap<>());
                } else if (analysis.intent() == Intent.UNKNOWN || analysis.intent() == Intent.SEARCH_WEB || analysis.intent() == Intent.DYNAMIC_PC_CONTROL) {
                    
                    if (!isDirectlyAddressed) {
                        System.out.printf("Ciel STT [Background]: \"%s\"%n", activeText);
                        ObserverService.appendTranscript(activeText);
                        isBusy.set(false);
                        if (onComplete != null) onComplete.run();
                        return;
                    }

                    System.out.println("Ciel Debug: Triggering AI Semantic Router for intent clarification...");
                    String intentStr = AIEngine.determineIntentSynchronously(activeText);
                    CommandAnalysis semanticAnalysis;
                    if (intentStr == null || intentStr.isEmpty()) {
                        semanticAnalysis = new CommandAnalysis(Intent.UNKNOWN, new HashMap<>());
                    } else {
                        try {
                            Intent intent = Intent.valueOf(intentStr);
                            Map<String, String> entities = new HashMap<>();
                            entities.put("query", activeText);
                            semanticAnalysis = new CommandAnalysis(intent, entities);
                        } catch (IllegalArgumentException e) {
                            System.out.println("Ciel Debug: Unknown intent returned from AI: " + intentStr);
                            semanticAnalysis = new CommandAnalysis(Intent.UNKNOWN, new HashMap<>());
                        }
                    }
                    
                    if (semanticAnalysis.intent() == Intent.UNKNOWN) {
                        // NLU FEEDBACK LOOP: Swarm failed to find intent. Ask the AI if it was an STT typo.
                        CommandAnalysis recoveredAnalysis = verifyIntentWithAI(activeText);
                        if (recoveredAnalysis != null) {
                            analysis = recoveredAnalysis;
                            activeText = recoveredAnalysis.entities().get("query");
                        } else {
                            System.out.println("Ciel Debug: Routing general chat to Personality Core (Gemma).");
                            String context = ContextBuilder.buildActiveContext(loreService, activeText);
                            
                            ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
                            AIEngine.chatFast(activeText, context, () -> isBusy.set(false));
                            releaseBusySynchronously = false;
                            if (onComplete != null) onComplete.run();
                            return; 
                        }
                    } else if (semanticAnalysis.intent() == Intent.DND_ANALYZE_LORE) {
                        System.out.println("Ciel Debug: Routing deep analysis to Logic Core (Phi-4).");
                        String subject = semanticAnalysis.entities().get("query");
                        
                        String loreData = loreService.getExpandedNoteContentForDm(subject)
                                                      .orElse("No specific campaign notes found for: " + subject);
                        String sourceExcerpts = LoreDocumentStore.search(subject);

                        String context = ContextBuilder.buildActiveContext(loreService, activeText) +
                                         "\n\n[D&D CAMPAIGN NOTES FOR ANALYSIS]\n" + loreData +
                                         "\n\n" + sourceExcerpts +
                                         "\n\nINSTRUCTION: Cross-check Obsidian notes against cited LN source excerpts. "
                                         + "AI review candidates are not approved canon; respect the campaign's spoiler ceiling.";
                        
                        ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
                        AIEngine.reasonDeeply(semanticAnalysis.entities().get("query"), context, () -> isBusy.set(false));
                        releaseBusySynchronously = false;
                        if (onComplete != null) onComplete.run();
                        return;
                    } else {
                        analysis = semanticAnalysis;
                        activeText = semanticAnalysis.entities().get("query"); 
                    }
                }

                System.out.printf("Ciel STT: Command Matched [%s]: \"%s\"%n", analysis.intent(), activeText);
                
                if (analysis.intent() != Intent.EASTER_EGG && 
                    analysis.intent() != Intent.INITIATE_SHUTDOWN && 
                    analysis.intent() != Intent.INITIATE_REBOOT && 
                    analysis.intent() != Intent.CANCEL_SHUTDOWN &&
                    analysis.intent() != Intent.UPDATE_SYSTEM) {
                    LineManager.getCommandConfirmationLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
                }

                releaseBusySynchronously = processCommand(analysis, activeText);
                
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
       String context = ContextBuilder.buildActiveContext(loreService, query);
       ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
       AIEngine.chatFast(query, context, () -> isBusy.set(false));
    }

    private boolean sendToAiWithData(String userQuery, String systemData) {
        String context = ContextBuilder.buildActiveContext(loreService, userQuery) + 
            "\n\n[SYSTEM DATA REPOSITORY]\n" + systemData + 
            "\n\nINSTRUCTION: Formulate a natural, conversational answer to the user's query using the SYSTEM DATA provided above.";
        
        ShortTermMemoryService.getMemory().setPrivilegedMode(true, 15);
        AIEngine.chatFast(userQuery, context, () -> isBusy.set(false));
        return false; 
    }

    private boolean processCommand(CommandAnalysis analysis, String userText) {
        switch (analysis.intent()) {
            case GET_TIME: return handleTimeCommand(userText);
            case GET_WEATHER: return handleWeatherCommand(userText);
            case GET_WEATHER_FORECAST: return handleWeatherForecastCommand(userText);
            case GET_SYSTEM_STATUS: return handleSystemStatusCommand(userText);
            
            case GET_DAILY_REPORT: return handleDailyReportCommand(userText);
            case GET_TOP_MEMORY_PROCESS: return handleGetTopProcessCommand("memory", userText);
            case GET_TOP_CPU_PROCESS: return handleGetTopProcessCommand("cpu", userText);
            case RECALL_FACT: return handleRecallCommand(analysis, userText);
            case DND_GET_RULE: return handleGetRule(analysis, userText);
            case DND_API_SEARCH: return handleApiSearch(analysis, userText);
            case GET_MOON_PHASE: return handleGetMoonPhase(userText);
            case GET_VISIBLE_PLANETS: return handleGetVisiblePlanets(userText);
            case GET_CONSTELLATIONS: return handleGetConstellations(userText);
            case GET_ECLIPSES: return handleGetEclipses(userText);
            
            case DYNAMIC_PC_CONTROL: 
                com.cielcompanion.ai.DynamicScriptEngine.executeChantAnnulment(userText, () -> isBusy.set(false));
                return false;
                
            case EXECUTE_SKILL: 
                String skillName = analysis.entities().get("query");
                if (skillName == null || skillName.isBlank()) skillName = analysis.entities().get("skill");
                String arguments = analysis.entities().get("arguments");
                
                com.cielcompanion.ai.SkillManager.executeSkill(skillName, arguments, () -> isBusy.set(false));
                return false;
            
            case FIND_APP_PATH: handleFindAppPathCommand(analysis); return true;
            case SCAN_FOR_APPS: handleScanForAppsCommand(); return true;
            case TERMINATE_PROCESS: handleTerminateProcessCommand(analysis, false); return true;
            case TERMINATE_PROCESS_FORCE: handleTerminateProcessCommand(analysis, true); return true;
            case INITIATE_SHUTDOWN: executeGracefulShutdown("shutdown"); return true;
            case INITIATE_REBOOT: executeGracefulShutdown("reboot"); return true;
            case CANCEL_SHUTDOWN: abortShutdownSequence(); return true;
            case UPDATE_SYSTEM: handleUpdateSelfCommand(); return true; 
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
            case DND_ANALYZE_LORE: 
                String subject = analysis.entities().get("subject");
                if (subject == null) subject = userText;
                String loreData = loreService.getExpandedNoteContentForDm(subject).orElse("No specific notes found.");
                String sourceExcerpts = LoreDocumentStore.search(subject);
                return sendToAiWithData(userText, "[D&D CAMPAIGN NOTES]\n" + loreData + "\n\n"
                        + sourceExcerpts + "\n\nINSTRUCTION: Cross-check Obsidian notes against cited LN passages. "
                        + "Review candidates are not approved campaign canon; respect the spoiler ceiling.");
            case TOGGLE_LISTENING: 
                if (voiceListener != null) {
                    voiceListener.toggleListening();
                }
                return true;
            case EASTER_EGG: handleEasterEggCommand(analysis); return true;
            default: return true; 
        }
    }
    
    private boolean handleWeatherCommand(String userText) {
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            String prompt = "The party is asking about the current weather. Invent a highly thematic, atmospheric description of the fantasy weather based on recent context.";
            return sendToAiWithData(userText, prompt);
        }
        String weatherReport = WeatherService.getCurrentWeather();
        return sendToAiWithData(userText, weatherReport);
    }
    
    private boolean handleWeatherForecastCommand(String userText) {
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            String prompt = "The party is asking for a weather forecast. Invent a thematic, slightly ominous or mystical prediction of the upcoming fantasy weather.";
            return sendToAiWithData(userText, prompt);
        }
        String forecastReport = WeatherService.getWeatherForecast();
        return sendToAiWithData(userText, forecastReport);
    }

    private boolean handleTimeCommand(String userText) {
        LocalDateTime now = LocalDateTime.now();
        
        if (now.getMinute() == 20 && (now.getHour() == 4 || now.getHour() == 16)) {
            LineManager.get420Line().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
            isBusy.set(false);
            return false; 
        }
        
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            String prompt = "The party is asking for the current time. Do not give them a digital clock time. Describe the position of the sun, moons, or shadows to indicate the passage of time.";
            return sendToAiWithData(userText, prompt);
        }
        
        String timeKatakana = PhonoKanaSanitizer.getCurrentTimeKatakana();
        String dateKatakana = PhonoKanaSanitizer.getCurrentDateKatakana();
        
        CielState.getEmotionManager().ifPresent(em -> em.triggerEmotion("Focused", 0.8, "Telling Time"));
        SpeechService.speakPreformatted("ザ カレント タイム イズ " + timeKatakana + "。イット イズ " + dateKatakana + "。");
        
        isBusy.set(false);
        return false;
    }

    private boolean handleSystemStatusCommand(String userText) {
        if (CielState.getCurrentMode() == OperatingMode.DND_ASSISTANT) {
            String prompt = "The party is asking for a 'System Status'. Interpret this as them asking for a status report on their physical health, magical reserves, or party morale.";
            return sendToAiWithData(userText, prompt);
        }
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
        List<String> linesToSpeak = new ArrayList<>();
        String savedReportKey = "ciel.idle_report.generated." + java.time.LocalDate.now();
        Optional<Fact> savedReport = MemoryService.getFact(savedReportKey);
        if (savedReport.isPresent()) {
            try {
                com.google.gson.JsonArray entries = com.google.gson.JsonParser.parseString(savedReport.get().value()).getAsJsonArray();
                for (com.google.gson.JsonElement entry : entries) {
                    if (entry.isJsonObject() && entry.getAsJsonObject().has("text")) {
                        linesToSpeak.add(entry.getAsJsonObject().get("text").getAsString());
                    }
                }
                System.out.println("Ciel Debug: Reusing saved idle report " + savedReportKey + " for the requested daily report.");
            } catch (Exception e) {
                System.err.println("Ciel Warning: Saved idle report could not be read; rebuilding from cached astronomy data: " + e.getMessage());
                linesToSpeak.clear();
            }
        }
        if (linesToSpeak.isEmpty()) {
            AstronomyReport report = AstronomyService.getTodaysAstronomyReport();
            linesToSpeak.addAll(report.sequentialEvents().values());
            linesToSpeak.addAll(report.reportAmbientLines());
        }
        
        String data = String.join(" ", linesToSpeak);
        if (data.isBlank()) data = "No significant daily events detected.";
        
        String financeData = FinanceService.getDailyFinanceReport();
        if (financeData != null && !financeData.isBlank()) {
            data = data + "\n\n[FINANCIAL REPORT]\n" + financeData;
        }
        
        return sendToAiWithData(userText, "Daily Report Data: " + data);
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
            String rawData = visible.isEmpty() ? "No major planets are currently above the horizon." : "Planets currently above the horizon: " + String.join(", ", visible);
            
            String weather = WeatherService.getCurrentWeather();
            String time = now.format(DateTimeFormatter.ofPattern("h:mm a"));
            
            String combinedPrompt = "RAW ASTRONOMY DATA: " + rawData + "\nCURRENT WEATHER: " + weather + "\nCURRENT TIME: " + time +
                "\n\nINSTRUCTION: Based on the current time and weather conditions, are these planets actually visible to the naked eye right now? If it is daytime, raining, or heavily clouded, tell the user visibility is poor or zero. Be conversational and concise.";
        
            return sendToAiWithData(userText, combinedPrompt);
        }
        return sendToAiWithData(userText, "Planet visibility data is unavailable.");
    }

    private boolean handleGetConstellations(String userText) {
        ensureFreshAstronomyData();
        Optional<CombinedAstronomyData> dataOpt = AstronomyService.getTodaysApiData();
        if (dataOpt.isPresent() && dataOpt.get().prominentConstellationLines != null && !dataOpt.get().prominentConstellationLines.isEmpty()) {
            String rawData = dataOpt.get().prominentConstellationLines.get(0);
            
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(com.cielcompanion.service.LocationService.getTimezone()));
            String weather = WeatherService.getCurrentWeather();
            String time = now.format(DateTimeFormatter.ofPattern("h:mm a"));
            
            String combinedPrompt = "RAW CONSTELLATION DATA: " + rawData + "\nCURRENT WEATHER: " + weather + "\nCURRENT TIME: " + time +
                "\n\nINSTRUCTION: Based on the current time and weather conditions, are these constellations actually visible right now? If it is daytime, raining, or overcast, inform the user they cannot be seen. Be conversational.";
        
            return sendToAiWithData(userText, combinedPrompt);
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
                String fullText = line.text();
                
                if (fullText.contains("{result}")) {
                    String[] parts = fullText.split("\\{result\\}");
                    
                    if (parts.length > 0 && !parts[0].isBlank()) {
                        SpeechService.speakPreformatted(parts[0], line.key() + "_prefix", false, true); 
                    }
                    
                    String numberStr = String.valueOf(finalTotal);
                    SpeechService.speakPreformatted(numberStr, "number_" + finalTotal, false, false); 
                    
                    if (parts.length > 1 && !parts[1].isBlank()) {
                        SpeechService.speakPreformatted(parts[1], line.key() + "_suffix", false, false); 
                    }
                } else {
                    String finalLine = fullText.replace("{result}", String.valueOf(finalTotal));
                    SpeechService.speakPreformatted(finalLine, null, false, true);
                }
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

    // -----------------------------------------------------------------
    //  Called from the voice pipeline when a CANCEL_SHUTDOWN intent is seen.
    // -----------------------------------------------------------------
    private static void abortShutdownSequence() {
        if (!isShuttingDown.get()) return;
        isShuttingDown.set(false);
        System.out.println("[Ciel Debug] Cancel command received - aborting shutdown sequence.");
        
        try {
            Runtime.getRuntime().exec("shutdown -a");
        } catch (IOException ignored) {}

        synchronized (shutdownLock) {
            if (shutdownScheduler != null && !shutdownScheduler.isShutdown()) {
                shutdownScheduler.shutdownNow();
            }
        }

        // Reset flags so the system can come back online cleanly
        CielState.setDiaryWrittenThisSession(false);
        VaultService.resetFinalLogFlag();

        // The cancellable portion does not stop OpenJarvis. Do not launch a
        // second server here; the prior implementation created duplicate Swarms.

        LineManager.getDialogueLine("shutdown_abort").ifPresent(line -> {
            SpeechService.speakPreformatted(line.text(), line.key(), false, false);
        });
    }

    // -----------------------------------------------------------------
    //  Graceful Java shutdown - stop speech, cancel pending tasks, then exit.
    // -----------------------------------------------------------------
    private static void shutdownJavaGracefully() {
        try {
            SpeechService.stopCurrentPlayback();
            SpeechService.cancelSequentialSpeech();
        } catch (Exception ignored) {}
        System.out.println("[Ciel Debug] Java side exiting now - no further work will be done.");
        System.exit(0);
    }

    // -----------------------------------------------------------------
    //  Dummy method - the real wiring is: when the voice pipeline detects
    //  Intent.CANCEL_SHUTDOWN it should call
    //   CommandService.abortShutdownSequence();
    // -----------------------------------------------------------------
    private static void startCancelListener(Runnable onCancel) {
        // No extra thread needed - the existing voice pipeline already
        // routes utterances to CommandService.handleCommand.
        // This method exists only so the lambda in executeGracefulShutdown
        // can compile; the actual callback is supplied from outside.
    }

    /**
     * Centralised, completely silent shutdown/reboot flow.
     * The OS Shutdown command is perfectly timed to fire ONLY after speech physically completes.
     */
    public static void executeGracefulShutdown(String commandType) {
        boolean isReboot   = "reboot".equalsIgnoreCase(commandType);
        boolean isLogout   = "logout".equalsIgnoreCase(commandType);

        System.out.println("[Ciel Debug] Initiating FAST " + commandType + " sequence. Securing memory core.");
        isShuttingDown.set(true);
        CielState.setPerformingColdShutdown(!isReboot);

        CompletableFuture.runAsync(() -> {
            // 1. Initial Speech (Using static keys so Azure caches/offline files trigger)
            LineManager.getDialogueLine("shutdown_init").ifPresent(line -> {
                SpeechService.speakPreformatted(line.text(), line.key(), false, true);
            });

            // Wait for Ciel to physically finish speaking before locking the thread
            try { Thread.sleep(1000); } catch (Exception ignored) {} 
            while (SpeechService.isActivelySpeaking()) {
                if (!isShuttingDown.get()) return;
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }

            // 2. Write the diary while the inference service is still available.
            if (!CielState.isDiaryWrittenThisSession()) {
                String context = "The Master initiated a " + commandType + " sequence. Please synthesize your final thoughts and next-cycle action plan.";
                System.out.println("[Ciel Debug] Blocking thread to write final system diary entry...");
                VaultService.generateSystemDiaryEntryBlocking(context, isReboot);
                CielState.setDiaryWrittenThisSession(true);
            }

            // 3. Abort check before committing to the countdown
            if (!isShuttingDown.get()) {
                System.out.println("[Ciel Debug] Shutdown aborted during memory secure phase.");
                return;
            }

            // 4. Second Speech: Countdown Warning (Using static keys)
            LineManager.getDialogueLine("shutdown_countdown").ifPresent(line -> {
                SpeechService.speakPreformatted(line.text(), line.key(), false, false);
            });

            // Wait for countdown speech to finish physically
            try { Thread.sleep(1000); } catch (Exception ignored) {} 
            while (SpeechService.isActivelySpeaking()) {
                if (!isShuttingDown.get()) return;
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }

            if (!isShuttingDown.get()) return;

            // 5. Start internal countdown timer
            synchronized (shutdownLock) {
                if (shutdownScheduler != null && !shutdownScheduler.isShutdown()) {
                    shutdownScheduler.shutdownNow();
                }
                shutdownScheduler = Executors.newSingleThreadScheduledExecutor();

                startCancelListener(() -> {
                    abortShutdownSequence();
                });

                // At T=30, schedule the OS action with time for the JVM shutdown
                // hook to flush Ciel state and stop OpenJarvis cleanly.
                shutdownScheduler.schedule(() -> {
                    if (!isShuttingDown.get()) return;
                    System.out.println("[Ciel Debug] 30s mark. Scheduling OS shutdown/reboot after Java cleanup.");
                    try {
                        if (isReboot) {
                            Runtime.getRuntime().exec("shutdown -r -t 20");
                        } else if (isLogout) {
                            new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                                    "Start-Sleep -Seconds 20; shutdown.exe -l").start();
                        } else {
                            Runtime.getRuntime().exec("shutdown -s -t 20");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    shutdownJavaGracefully();
                }, 30, TimeUnit.SECONDS);
            }
        });
    }

    public void handleShutdownRebootCommand(String commandType) {
        if (!ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
            LineManager.getPrivilegedCommandRequiredLine().ifPresent(line ->
                    SpeechService.speakPreformatted(line.text(), line.key()));
            return;
        }
        
        SpeechService.getVoiceListener().ifPresent(VoiceListener::forceMicReinitialization);
        
        CielState.setPerformingColdShutdown(!"reboot".equalsIgnoreCase(commandType));
        executeGracefulShutdown(commandType);
    }

    /**
     * Completely decoupled Update Sequence. 
     * No OS shutdown, launches visible console for recompilation.
     */
    public void handleUpdateSelfCommand() {
        if (!ShortTermMemoryService.getMemory().isInPrivilegedMode()) {
            LineManager.getPrivilegedCommandRequiredLine().ifPresent(line -> SpeechService.speakPreformatted(line.text(), line.key()));
            return;
        }
        
        SpeechService.getVoiceListener().ifPresent(VoiceListener::forceMicReinitialization);
        
        CielState.setPerformingColdShutdown(true); 

        // Step 1: Speak initial phrase
        LineManager.getDialogueLine("update_init").ifPresent(line -> {
            SpeechService.speakPreformatted(line.text(), line.key(), false, true);
        });
        
        CompletableFuture.runAsync(() -> {
            try {
                // Wait for speech to finish physically
                Thread.sleep(1000);
                while (SpeechService.isActivelySpeaking()) { Thread.sleep(500); }

                // Step 2: Synchronous Diary Logging
                String contextSummary = "The Master initiated a self-update sequence. I am shutting down my interface to assimilate new code, while leaving his PC running.";
                if (!CielState.isDiaryWrittenThisSession()) {
                    System.out.println("Ciel Debug: Blocking thread to write final system diary entry...");
                    VaultService.generateSystemDiaryEntryBlocking(contextSummary, false);
                    CielState.setDiaryWrittenThisSession(true);
                }

                // Step 3: Final Speech
                LineManager.getDialogueLine("update_final").ifPresent(line -> {
                    SpeechService.speakPreformatted(line.text(), line.key(), false, false);
                });
                
                // Wait for final speech to complete physically
                Thread.sleep(1000);
                while (SpeechService.isActivelySpeaking()) { Thread.sleep(500); }

                // Step 4: Stop Ciel's helper service and wait for its port to close.
                if (!CielCompanion.killJarvisAndWait(8)) {
                    System.err.println("Ciel Warning: OpenJarvis did not confirm shutdown before update launch; updater will record the result.");
                }

                // Step 5: Execute Recompilation Batch Script
                triggerMavenRecompileBatch();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void triggerMavenRecompileBatch() {
        try {
            Path script = ProjectPaths.root().resolve("update_ciel.bat");
            if (!Files.isRegularFile(script)) throw new IOException("Updater script is missing: " + script);
            String escapedScript = script.toAbsolutePath().toString().replace("'", "''");
            String escapedRoot = ProjectPaths.root().toAbsolutePath().toString().replace("'", "''");
            String launch = "Start-Process -FilePath '" + escapedScript
                    + "' -WorkingDirectory '" + escapedRoot + "' -WindowStyle Hidden";
            new ProcessBuilder("powershell.exe", "-WindowStyle", "Hidden", "-NoProfile",
                    "-NonInteractive", "-Command", launch)
                    .directory(ProjectPaths.root().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception e) {
            System.err.println("Ciel Error: Could not launch updater: " + e.getMessage());
            return;
        }
        
        System.out.println("Ciel Debug: Self-update sequence complete. Exiting JVM to allow recompilation.");
        System.exit(0);
    }
}
