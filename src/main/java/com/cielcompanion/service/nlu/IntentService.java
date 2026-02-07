package com.cielcompanion.service.nlu;

import com.cielcompanion.service.LineManager;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A simple Natural Language Understanding service to determine user intent.
 * REWORKED: Now includes robust D&D Mastery and Audit support while maintaining 
 * your extensive mishearing and easter egg library.
 */
public class IntentService {

    private final Map<Intent, Pattern> intentPatterns = new LinkedHashMap<>();
    private static final Pattern MISHEARD_TRIGGER_PATTERN = Pattern.compile("^(ciel|cl|seal|seo|see i|hey allison|he see our|cl what|see how can you want|how can you open|he see our launch|hunter|so listen|ceo listen)\\s+", Pattern.CASE_INSENSITIVE);
    private final Map<String, List<String>> mishearingCorrections = new LinkedHashMap<>();
    private static final Pattern JUNK_PREFIX_PATTERN = Pattern.compile("^(the|a|an)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were", "in", "of", "to", "and", "i", "you", "it");

    public void initialize() {
        // --- Easter Egg Mishearing Corrections ---
        mishearingCorrections.put("allons-y", List.of("alone the", "along the", "on the", "hell on the"));
        mishearingCorrections.put("aschente", List.of("action bay", "a shanty", "action to", "a champagne", "has shield i", "action think", "shantae", "a shantae"));
        mishearingCorrections.put("set your heart ablaze", List.of("such your heart ablaze", "set the"));
        mishearingCorrections.put("beam me up scotty", List.of("be me up scotty"));
        mishearingCorrections.put("raphael", List.of("rafael"));
        mishearingCorrections.put("kaneda", List.of("canada"));
        mishearingCorrections.put("are you my mummy", List.of("are you my mommy"));
        mishearingCorrections.put("execute order 66", List.of("execute order systems", "execute order sixty six", "execute order significant"));
        mishearingCorrections.put("geronimo", List.of("geronimo"));
        mishearingCorrections.put("shadow clone jutsu", List.of("shadow clone do to", "shadow clone", "shadow clone do too"));
        mishearingCorrections.put("pot of greed", List.of("heart of greed", "pod of greed", "the pod of greed"));
        mishearingCorrections.put("yggdrasil", List.of("you your so", "you do a so", "you do so", "good the ciel", "you there so", "you do a so", "give yourself", "you are so", "gigged yourself"));
        mishearingCorrections.put("hadouken", List.of("who you that i so", "hell do king", "hadoop can", "budokan", "a dog can"));
        mishearingCorrections.put("gomu gomu no", List.of("go more go more no", "go more go no", "go my go no", "go more go me no", "the go more go more no", "gomo gomo", "gum gum no"));
        mishearingCorrections.put("it's a trap", List.of("what is a trap"));

        // --- Standard Intent Patterns (Ordered by specificity) ---
        intentPatterns.put(Intent.TOGGLE_LISTENING, Pattern.compile("(?i)toggle listening"));
        intentPatterns.put(Intent.SET_MODE_ATTENTIVE, Pattern.compile("(?i)(enter|start|begin) (recording|attentive) mode"));
        intentPatterns.put(Intent.SET_MODE_DND, Pattern.compile("(?i)(enter|start|begin) d and d mode"));
        intentPatterns.put(Intent.SET_MODE_INTEGRATED, Pattern.compile("(?i)(return to|enter|resume) (integrated|standard|normal) mode"));
        
        // --- Astronomy Intents ---
        intentPatterns.put(Intent.GET_MOON_PHASE, Pattern.compile("(?i)(what is|what's) the moon phase( tonight)?"));
        intentPatterns.put(Intent.GET_VISIBLE_PLANETS, Pattern.compile("(?i)(what|which) planets are visible( tonight)?"));
        intentPatterns.put(Intent.GET_CONSTELLATIONS, Pattern.compile("(?i)(what|which) constellations are visible( tonight)?"));
        intentPatterns.put(Intent.GET_ECLIPSES, Pattern.compile("(?i)(are there|is there) any eclipses?( happening| soon| tonight)?"));

        // --- D&D Specific Intents ---
        intentPatterns.put(Intent.DND_RUN_AUDIT, Pattern.compile("(?i)run (campaign|folder) audit"));
        intentPatterns.put(Intent.DND_RECORD_MASTERY, Pattern.compile("(?i)record meaningful (?<skill>.+) (use|success) for (?<player>.+)"));
        
        intentPatterns.put(Intent.DND_GET_RULE, Pattern.compile("(?i)(what are the rules for|what's the rule for|how does) (?<topic>.+)"));
        intentPatterns.put(Intent.DND_API_SEARCH, Pattern.compile("(?i)(look up|search for the) (?<type>spell|item|monster) (?<query>.+)"));
        intentPatterns.put(Intent.DND_PLAY_SOUND, Pattern.compile("(?i)play (the sound of )?(a |an )?(?<soundName>\\w+)( sound| noise)?"));
        intentPatterns.put(Intent.DND_ROLL_DICE, Pattern.compile("(?i)roll (?<dice>.+)"));
        intentPatterns.put(Intent.DND_REVEAL_LORE, Pattern.compile("(?i)reveal that (?<subject>.+?) is (?<content>.+)"));
        intentPatterns.put(Intent.DND_CREATE_SESSION_NOTE, Pattern.compile("(?i)(create|make) a session note for (?<subject>.+)"));
        intentPatterns.put(Intent.DND_ADD_TO_SESSION_NOTE, Pattern.compile("(?i)add to (?<subject>.+?)(s'|'s)? session note (?<content>.+)"));
        intentPatterns.put(Intent.DND_LINK_SESSION_NOTE, Pattern.compile("(?i)link session notes (?<subjectA>.+) and (?<subjectB>.+)"));
        intentPatterns.put(Intent.DND_RECALL_SESSION_LINKS, Pattern.compile("(?i)(what is linked to|get connections for) (?<subject>.+)"));
        intentPatterns.put(Intent.DND_RECALL_SESSION_NOTE, Pattern.compile("(?i)(what do we know about|recall the session note for|tell me about) (?<subject>.+)"));
        intentPatterns.put(Intent.DND_ANALYZE_LORE, Pattern.compile("(?i)(analyze my notes on|what are my notes on) (?<subject>.+)"));

        // --- General Utility Intents ---
        intentPatterns.put(Intent.GET_WEATHER_FORECAST, Pattern.compile("(?i)(weather|forecast).*(tomorrow|later|tonight)"));
        intentPatterns.put(Intent.GET_WEATHER, Pattern.compile("(?i)(weather|forecast|temperature|hot|cold|outside)"));
        intentPatterns.put(Intent.GET_TIME, Pattern.compile("(?i)^what (time|date) is it$|^what's the (time|date)$|\\b(time|date)\\b"));
        intentPatterns.put(Intent.GET_DAILY_REPORT, Pattern.compile("(?i)(daily report|astronomy)"));
        intentPatterns.put(Intent.GET_TOP_MEMORY_PROCESS, Pattern.compile("(?i)((top|which) process.*(memory|ram))|((most|highest) (memory|ram))"));
        intentPatterns.put(Intent.GET_TOP_CPU_PROCESS, Pattern.compile("(?i)((top|which) process.*cpu)|((most|highest) cpu)"));
        intentPatterns.put(Intent.GET_SYSTEM_STATUS, Pattern.compile("(?i)(status|system report|system status)"));
        intentPatterns.put(Intent.TERMINATE_PROCESS_FORCE, Pattern.compile("(?i)(force close|force quit|force terminate|horse close) (?<appName>.+)"));
        intentPatterns.put(Intent.TERMINATE_PROCESS, Pattern.compile("(?i)(close|quit|terminate) (?<appName>.+)"));
        intentPatterns.put(Intent.INITIATE_REBOOT, Pattern.compile("(?i)(reboot|restart)( the)? (pc|computer|system)?"));
        intentPatterns.put(Intent.INITIATE_SHUTDOWN, Pattern.compile("(?i)shut ?down( the)? (pc|computer|system)?"));
        intentPatterns.put(Intent.CANCEL_SHUTDOWN, Pattern.compile("(?i)cancel (shutdown|reboot)"));
        intentPatterns.put(Intent.SCAN_FOR_APPS, Pattern.compile("(?i)scan for new (apps|applications|games)"));
        intentPatterns.put(Intent.FIND_APP_PATH, Pattern.compile("(?i)(find|locate|learn|save path for) (?<appName>.+)"));
        intentPatterns.put(Intent.START_ROUTINE, Pattern.compile("(?i)(start|initiate|begin|run) (?<routineName>\\w+) routine"));
        intentPatterns.put(Intent.OPEN_APPLICATION, Pattern.compile("(?i)(open|launch|start) (?<appName>.+)"));
        intentPatterns.put(Intent.REMEMBER_FACT, Pattern.compile("(?i)remember that (?<key>.+) is (?<value>.+)"));
        intentPatterns.put(Intent.REMEMBER_FACT_SIMPLE, Pattern.compile("(?i)remember (?<key>.+)"));
        intentPatterns.put(Intent.RECALL_FACT, Pattern.compile("(?i)(what is|who is|what's) (?<key>.+)"));
        
        System.out.println("Ciel Debug: IntentService initialized with " + intentPatterns.size() + " patterns.");
    }

    public CommandAnalysis analyze(String text) {
        String cleanedText = MISHEARD_TRIGGER_PATTERN.matcher(text).replaceFirst("").trim();
        cleanedText = JUNK_PREFIX_PATTERN.matcher(cleanedText).replaceFirst("").trim();
        String lowerText = cleanedText.toLowerCase().replace("[unk]", "").trim();

        // Step 1: Mishearing Corrections
        for (Map.Entry<String, List<String>> entry : mishearingCorrections.entrySet()) {
            for (String mishearing : entry.getValue()) {
                if (lowerText.equals(mishearing)) {
                    String correctedText = entry.getKey();
                    if (correctedText.startsWith("shut down")) return new CommandAnalysis(Intent.INITIATE_SHUTDOWN, new HashMap<>());
                    if (correctedText.startsWith("reboot")) return new CommandAnalysis(Intent.INITIATE_REBOOT, new HashMap<>());
                    Map<String, String> entities = new HashMap<>();
                    entities.put("key", correctedText);
                    return new CommandAnalysis(Intent.EASTER_EGG, entities);
                }
            }
        }
        
        // Step 2: Exact Easter Egg
        final String finalText = lowerText;
        if (LineManager.getEasterEggKeys().stream().anyMatch(key -> key.equalsIgnoreCase(finalText))) {
            Map<String, String> entities = new HashMap<>();
            entities.put("key", finalText);
            return new CommandAnalysis(Intent.EASTER_EGG, entities);
        }

        // Step 3: Fuzzy Easter Egg
        String bestMatch = findBestKeywordMatch(lowerText);
        if (bestMatch != null) {
            Map<String, String> entities = new HashMap<>();
            entities.put("key", bestMatch);
            return new CommandAnalysis(Intent.EASTER_EGG, entities);
        }

        // Step 4: Standard patterns
        for (Map.Entry<Intent, Pattern> entry : intentPatterns.entrySet()) {
            Matcher matcher = entry.getValue().matcher(lowerText);
            if (matcher.find()) {
                return new CommandAnalysis(entry.getKey(), extractEntities(matcher));
            }
        }

        // Step 5: Web Fallback
        if (lowerText.contains("?") || lowerText.startsWith("who") || lowerText.startsWith("what") || lowerText.startsWith("how")) {
            Map<String, String> entities = new HashMap<>();
            entities.put("query", cleanedText);
            return new CommandAnalysis(Intent.SEARCH_WEB, entities);
        }

        return new CommandAnalysis(Intent.UNKNOWN, new HashMap<>());
    }

    private String findBestKeywordMatch(String heardText) {
        Set<String> heardKeywords = Arrays.stream(heardText.split("\\s+")).filter(w -> !STOP_WORDS.contains(w)).collect(Collectors.toSet());
        if (heardKeywords.isEmpty()) return null;
        String bestMatch = null;
        double bestRatio = 0.0;
        for (String key : LineManager.getEasterEggKeys()) {
            Set<String> keyKeywords = Arrays.stream(key.toLowerCase().split("\\s+")).filter(w -> !STOP_WORDS.contains(w)).collect(Collectors.toSet());
            if (keyKeywords.isEmpty()) continue;
            long currentScore = heardKeywords.stream().filter(keyKeywords::contains).count();
            double currentRatio = (double) currentScore / keyKeywords.size();
            if (currentRatio > bestRatio) {
                bestRatio = currentRatio;
                bestMatch = key;
            }
        }
        return (bestMatch != null && bestRatio >= 0.6) ? bestMatch : null;
    }

    private Map<String, String> extractEntities(Matcher matcher) {
        Map<String, String> entities = new HashMap<>();
        String[] possibleGroups = {"appName", "routineName", "key", "value", "dice", "soundName", "subject", "subjectA", "subjectB", "content", "topic", "type", "query", "player", "skill"};
        for (String group : possibleGroups) {
            try {
                String val = matcher.group(group);
                if (val != null) entities.put(group, val.trim());
            } catch (IllegalArgumentException | IllegalStateException e) { /* Group not in regex */ }
        }
        return entities;
    }
}