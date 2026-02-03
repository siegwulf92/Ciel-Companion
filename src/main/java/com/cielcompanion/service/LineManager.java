package com.cielcompanion.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Month;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LineManager {

    public record DialogueLine(String key, String text) {}

    private static final Random random = new Random();

    private static List<DialogueLine> BOOT_GREETING_LINES;
    private static List<DialogueLine> LOGIN_GREETING_LINES;
    private static List<DialogueLine> WARM_LOGIN_GREETING_LINES;
    private static List<DialogueLine> RETURN_FROM_IDLE_LINES;
    private static List<DialogueLine> RETURN_TO_GAME_LINES;
    private static List<DialogueLine> PHASE4_INTERRUPT_LINES;
    private static List<DialogueLine> WAKE_WORD_ACK_LINES;
    private static List<DialogueLine> COMMAND_CONFIRMATION_LINES;
    private static List<DialogueLine> phase1LinesCommon;
    private static List<DialogueLine> phase1LinesRare;
    private static List<DialogueLine> phase2LinesCommon;
    private static List<DialogueLine> phase2LinesRare;
    private static List<DialogueLine> phase3LinesCommon;
    private static List<DialogueLine> phase3LinesRare;
    private static List<DialogueLine> phase3LinesGameRare;
    private static List<DialogueLine> phase4Chunks;

    private static Map<Month, DialogueLine> SEASONAL_LINES;
    private static Map<String, DialogueLine> MOON_PHASE_LINES;
    private static Map<String, List<DialogueLine>> ECLIPSE_LINES;
    private static Map<String, DialogueLine> PLANET_LINES;
    private static List<DialogueLine> WEATHER_REPORT_LINES;
    private static List<DialogueLine> WEATHER_FORECAST_LINES;

    private static DialogueLine CPU_ALERT_LINE;
    private static DialogueLine MEM_ALERT_LINE;
    private static DialogueLine LOGOUT_WARNING_LINE;
    private static DialogueLine NVIDIA_MIC_FALLBACK_LINE;
    private static List<DialogueLine> TIME_LINES;
    private static List<DialogueLine> TIME_420_LINES;
    private static List<DialogueLine> STATUS_INTRO_LINES;
    private static DialogueLine STATUS_CPU_LINE;
    private static DialogueLine STATUS_MEM_LINE;
    private static List<DialogueLine> STATUS_OUTRO_LINES;
    private static DialogueLine SHUTDOWN_CONFIRM_LINE;
    private static DialogueLine REBOOT_CONFIRM_LINE;
    private static DialogueLine CANCEL_SUCCESS_LINE;
    private static DialogueLine CANCEL_FAIL_LINE;
    private static DialogueLine REMEMBER_SUCCESS_LINE;
    private static DialogueLine RECALL_SUCCESS_LINE;
    private static DialogueLine RECALL_FAIL_LINE;
    private static List<DialogueLine> UNRECOGNIZED_LINES;
    private static List<DialogueLine> APP_AWARENESS_GAME_LINES;
    private static List<DialogueLine> APP_AWARENESS_WORK_LINES;
    private static List<DialogueLine> APP_AWARENESS_GENERIC_LINES;
    private static List<DialogueLine> LAUNCH_APP_SUCCESS_LINES;
    private static List<DialogueLine> LAUNCH_APP_FAIL_LINES;
    private static List<DialogueLine> DAILY_REPORT_INTRO_LINES;
    private static List<DialogueLine> TOP_MEMORY_PROCESS_LINES;
    private static List<DialogueLine> TOP_CPU_PROCESS_LINES;
    private static List<DialogueLine> PRIVILEGED_COMMAND_REQUIRED_LINES;
    private static List<DialogueLine> TERMINATE_APP_SUCCESS_LINES;
    private static List<DialogueLine> TERMINATE_APP_FAIL_LINES;
    private static List<DialogueLine> ROUTINE_START_LINES;
    private static List<DialogueLine> WEB_SEARCH_LINES;
    private static List<DialogueLine> WEB_SEARCH_FALLBACK_LINES;
    private static List<DialogueLine> FIND_APP_SUCCESS_LINES;
    private static List<DialogueLine> FIND_APP_FAIL_LINES;
    private static List<DialogueLine> SCAN_APPS_START_LINES;
    private static List<DialogueLine> SCAN_APPS_SUCCESS_LINES;
    private static List<DialogueLine> SCAN_APPS_FAIL_LINES;
    private static Map<String, DialogueLine> MODE_SWITCH_LINES;
    private static List<DialogueLine> DND_DICE_ROLL_WRONG_MODE_LINES;
    private static List<DialogueLine> DND_DICE_ROLL_RESULT_LINES;
    private static List<DialogueLine> DND_DICE_ROLL_ERROR_LINES;
    private static List<DialogueLine> DND_PLAY_SOUND_CONFIRM_LINES;
    private static List<DialogueLine> LORE_CREATE_LINES;
    private static List<DialogueLine> LORE_ADD_LINES;
    private static List<DialogueLine> LORE_RECALL_SUCCESS_LINES;
    private static List<DialogueLine> LORE_RECALL_FAIL_LINES;
    private static List<DialogueLine> LORE_LINK_SUCCESS_LINES;
    private static List<DialogueLine> LORE_RECALL_LINKS_SUCCESS_LINES;
    private static List<DialogueLine> LORE_RECALL_LINKS_FAIL_LINES;
    private static List<DialogueLine> DND_REVEAL_SUCCESS_LINES;
    private static List<DialogueLine> DND_ANALYZE_SUCCESS_LINES;
    private static List<DialogueLine> DND_ANALYZE_FAIL_LINES;
    private static List<DialogueLine> DND_RULE_FOUND_LINES;
    private static List<DialogueLine> DND_RULE_NOT_FOUND_LINES;
    private static List<DialogueLine> TOGGLE_LISTENING_LINES;
    private static Properties easterEggProps;

    public static void load() {
        Properties props = new Properties();
        try (InputStream is = LineManager.class.getResourceAsStream("/ciel_lines_ja.properties")) {
            if (is == null) throw new IOException("Resource not found: /ciel_lines_ja.properties");
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));

            DAILY_REPORT_INTRO_LINES = getLinesByKeyPrefix(props, "command.daily_report_intro.");
            BOOT_GREETING_LINES = getLinesByKeyPrefix(props, "boot_greeting.");
            LOGIN_GREETING_LINES = getLinesByKeyPrefix(props, "login_greeting.");
            WARM_LOGIN_GREETING_LINES = getLinesByKeyPrefix(props, "warm_login_greeting.");
            RETURN_FROM_IDLE_LINES = getLinesByKeyPrefix(props, "return_from_idle.");
            RETURN_TO_GAME_LINES = getLinesByKeyPrefix(props, "return_to_game.");
            PHASE4_INTERRUPT_LINES = getLinesByKeyPrefix(props, "phase4_interrupt.");
            WAKE_WORD_ACK_LINES = getLinesByKeyPrefix(props, "wake_word_ack.");
            COMMAND_CONFIRMATION_LINES = getLinesByKeyPrefix(props, "command_confirmation.");
            phase1LinesCommon = getLinesByKeyPrefix(props, "phase1.common.");
            phase1LinesRare = getLinesByKeyPrefix(props, "phase1.rare.");
            phase2LinesCommon = getLinesByKeyPrefix(props, "phase2.common.");
            phase2LinesRare = getLinesByKeyPrefix(props, "phase2.rare.");
            phase3LinesCommon = getLinesByKeyPrefix(props, "phase3.common.");
            phase3LinesRare = getLinesByKeyPrefix(props, "phase3.rare.");
            phase3LinesGameRare = getLinesByKeyPrefix(props, "phase3.game_rare.");
            phase4Chunks = getLinesByKeyPrefix(props, "phase4.chunk.");
            CPU_ALERT_LINE = new DialogueLine("alert.cpu.high", props.getProperty("alert.cpu.high"));
            MEM_ALERT_LINE = new DialogueLine("alert.mem.high", props.getProperty("alert.mem.high"));
            LOGOUT_WARNING_LINE = new DialogueLine("logout.warning", props.getProperty("logout.warning"));
            NVIDIA_MIC_FALLBACK_LINE = new DialogueLine("alert.mic.nvidia_fallback", props.getProperty("alert.mic.nvidia_fallback"));
            TIME_LINES = getLinesByKeyPrefix(props, "command.time.");
            TIME_420_LINES = getLinesByKeyPrefix(props, "command.time.420.");
            STATUS_INTRO_LINES = getLinesByKeyPrefix(props, "command.status.intro.");
            STATUS_CPU_LINE = new DialogueLine("command.status.cpu", props.getProperty("command.status.cpu"));
            STATUS_MEM_LINE = new DialogueLine("command.status.mem", props.getProperty("command.status.mem"));
            STATUS_OUTRO_LINES = getLinesByKeyPrefix(props, "command.status.outro.");
            SHUTDOWN_CONFIRM_LINE = new DialogueLine("command.shutdown.confirm", props.getProperty("command.shutdown.confirm"));
            REBOOT_CONFIRM_LINE = new DialogueLine("command.reboot.confirm", props.getProperty("command.reboot.confirm"));
            CANCEL_SUCCESS_LINE = new DialogueLine("command.cancel.success", props.getProperty("command.cancel.success"));
            CANCEL_FAIL_LINE = new DialogueLine("command.cancel.fail", props.getProperty("command.cancel.fail"));
            REMEMBER_SUCCESS_LINE = new DialogueLine("command.remember.success", props.getProperty("command.remember.success"));
            RECALL_SUCCESS_LINE = new DialogueLine("command.recall.success", props.getProperty("command.recall.success"));
            RECALL_FAIL_LINE = new DialogueLine("command.recall.fail", props.getProperty("command.recall.fail"));
            UNRECOGNIZED_LINES = getLinesByKeyPrefix(props, "command.unrecognized.");
            APP_AWARENESS_GAME_LINES = getLinesByKeyPrefix(props, "awareness.game");
            APP_AWARENESS_WORK_LINES = getLinesByKeyPrefix(props, "awareness.work");
            APP_AWARENESS_GENERIC_LINES = getLinesByKeyPrefix(props, "awareness.generic");
            LAUNCH_APP_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.launch_app.success");
            LAUNCH_APP_FAIL_LINES = getLinesByKeyPrefix(props, "command.launch_app.fail");
            WEATHER_REPORT_LINES = getLinesByKeyPrefix(props, "command.weather_report.");
            WEATHER_FORECAST_LINES = getLinesByKeyPrefix(props, "command.weather_forecast.");
            TOP_MEMORY_PROCESS_LINES = getLinesByKeyPrefix(props, "command.top_memory_process");
            TOP_CPU_PROCESS_LINES = getLinesByKeyPrefix(props, "command.top_cpu_process");
            PRIVILEGED_COMMAND_REQUIRED_LINES = getLinesByKeyPrefix(props, "command.privileged_required");
            TERMINATE_APP_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.terminate_app.success");
            TERMINATE_APP_FAIL_LINES = getLinesByKeyPrefix(props, "command.terminate_app.fail");
            ROUTINE_START_LINES = getLinesByKeyPrefix(props, "command.routine_start");
            WEB_SEARCH_LINES = getLinesByKeyPrefix(props, "command.web_search");
            WEB_SEARCH_FALLBACK_LINES = getLinesByKeyPrefix(props, "command.web_search_fallback");
            FIND_APP_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.find_app.success");
            FIND_APP_FAIL_LINES = getLinesByKeyPrefix(props, "command.find_app.fail");
            SCAN_APPS_START_LINES = getLinesByKeyPrefix(props, "command.scan_apps.start");
            SCAN_APPS_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.scan_apps.success");
            SCAN_APPS_FAIL_LINES = getLinesByKeyPrefix(props, "command.scan_apps.fail");
            DND_DICE_ROLL_WRONG_MODE_LINES = getLinesByKeyPrefix(props, "command.dnd.dice_roll_wrong_mode");
            DND_DICE_ROLL_RESULT_LINES = getLinesByKeyPrefix(props, "command.dnd.dice_roll_result");
            DND_DICE_ROLL_ERROR_LINES = getLinesByKeyPrefix(props, "command.dnd.dice_roll_error");
            DND_PLAY_SOUND_CONFIRM_LINES = getLinesByKeyPrefix(props, "command.dnd.play_sound.confirm");
            LORE_CREATE_LINES = getLinesByKeyPrefix(props, "command.dnd.create_note_success");
            LORE_ADD_LINES = getLinesByKeyPrefix(props, "command.dnd.update_note_success");
            LORE_RECALL_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.dnd.recall_note_success");
            LORE_RECALL_FAIL_LINES = getLinesByKeyPrefix(props, "command.dnd.recall_note_fail");
            LORE_LINK_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.dnd.link_note_success");
            LORE_RECALL_LINKS_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.dnd.recall_links_success");
            LORE_RECALL_LINKS_FAIL_LINES = getLinesByKeyPrefix(props, "command.dnd.recall_links_fail");
            DND_REVEAL_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.dnd.reveal_success");
            DND_ANALYZE_SUCCESS_LINES = getLinesByKeyPrefix(props, "command.dnd.analyze_success");
            DND_ANALYZE_FAIL_LINES = getLinesByKeyPrefix(props, "command.dnd.analyze_fail");
            DND_RULE_FOUND_LINES = getLinesByKeyPrefix(props, "command.dnd.rule_found");
            DND_RULE_NOT_FOUND_LINES = getLinesByKeyPrefix(props, "command.dnd.rule_not_found");
            TOGGLE_LISTENING_LINES = getLinesByKeyPrefix(props, "command.toggle_listening.");

            loadSeasonalLines(props);
            loadMoonPhaseLines(props);
            loadEclipseLines(props);
            loadPlanetLines(props);
            loadModeSwitchLines(props);

            System.out.println("Ciel Debug: Dialogue lines loaded successfully from ciel_lines_ja.properties.");
        } catch (IOException e) {
            System.err.println("Ciel FATAL Error: Failed to load dialogue lines from properties file.");
            e.printStackTrace();
            assignEmptyLineLists();
        }

        easterEggProps = new Properties();
        try (InputStream is = LineManager.class.getResourceAsStream("/easter_eggs.properties")) {
            if (is != null) {
                loadPropertiesWithSpaces(easterEggProps, is);
                System.out.println("Ciel Debug: Loaded " + easterEggProps.size() + " easter egg lines.");
            }
        } catch (IOException e) {
            System.err.println("Ciel Warning: Could not load easter_eggs.properties.");
        }
    }

    private static void loadPropertiesWithSpaces(Properties props, InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("---")) {
                    continue;
                }
                int separatorIndex = line.indexOf('=');
                if (separatorIndex != -1) {
                    String key = line.substring(0, separatorIndex).trim();
                    String value = line.substring(separatorIndex + 1).trim();
                    props.setProperty(key, value);
                }
            }
        }
    }

    public static Set<String> getEasterEggKeys() {
        return easterEggProps.stringPropertyNames();
    }

    public static Optional<DialogueLine> getEasterEggLine(String key) {
        if (key == null) return Optional.empty();
        for (String propKey : easterEggProps.stringPropertyNames()) {
            if (propKey.equalsIgnoreCase(key)) {
                return Optional.of(new DialogueLine("easteregg." + propKey, easterEggProps.getProperty(propKey)));
            }
        }
        return Optional.empty();
    }

    private static void loadSeasonalLines(Properties props) {
        SEASONAL_LINES = new HashMap<>();
        props.stringPropertyNames().stream()
            .filter(key -> key.startsWith("phase1.seasonal."))
            .forEach(key -> {
                try {
                    String monthStr = key.substring("phase1.seasonal.".length());
                    Month month = Month.valueOf(monthStr.toUpperCase());
                    SEASONAL_LINES.put(month, new DialogueLine(key, props.getProperty(key)));
                } catch (IllegalArgumentException e) { /* Ignore */ }
            });
    }

    private static void loadMoonPhaseLines(Properties props) {
        MOON_PHASE_LINES = new HashMap<>();
        props.stringPropertyNames().stream()
            .filter(key -> key.startsWith("phase1.moon."))
            .forEach(key -> {
                String phaseName = key.substring("phase1.moon.".length());
                MOON_PHASE_LINES.put(phaseName, new DialogueLine(key, props.getProperty(key)));
            });
    }

    private static void loadEclipseLines(Properties props) {
        ECLIPSE_LINES = new HashMap<>();
        ECLIPSE_LINES.put("lunar_today", getLinesByKeyPrefix(props, "phase1.eclipse.lunar.today"));
        ECLIPSE_LINES.put("lunar_upcoming", getLinesByKeyPrefix(props, "phase1.eclipse.lunar.upcoming"));
        ECLIPSE_LINES.put("lunar_aftermath", getLinesByKeyPrefix(props, "phase1.eclipse.lunar.aftermath"));
        ECLIPSE_LINES.put("lunar_notvisible", getLinesByKeyPrefix(props, "phase1.eclipse.lunar.notvisible"));
        ECLIPSE_LINES.put("solar_today", getLinesByKeyPrefix(props, "phase1.eclipse.solar.today"));
        ECLIPSE_LINES.put("solar_upcoming", getLinesByKeyPrefix(props, "phase1.eclipse.solar.upcoming"));
        ECLIPSE_LINES.put("solar_aftermath", getLinesByKeyPrefix(props, "phase1.eclipse.solar.aftermath"));
        ECLIPSE_LINES.put("solar_notvisible", getLinesByKeyPrefix(props, "phase1.eclipse.solar.notvisible"));
    }

    private static void loadPlanetLines(Properties props) {
        PLANET_LINES = new HashMap<>();
        props.stringPropertyNames().stream()
            .filter(key -> key.startsWith("phase1.planets."))
            .forEach(key -> {
                String planetName = key.substring("phase1.planets.".length());
                PLANET_LINES.put(planetName, new DialogueLine(key, props.getProperty(key)));
            });
    }

    private static void loadModeSwitchLines(Properties props) {
        MODE_SWITCH_LINES = new HashMap<>();
        props.stringPropertyNames().stream()
            .filter(key -> key.startsWith("command.mode_switch."))
            .forEach(key -> {
                String modeName = key.substring("command.mode_switch.".length());
                MODE_SWITCH_LINES.put(modeName, new DialogueLine(key, props.getProperty(key)));
            });
    }

    // FIXED: Numerical Sorting for Keys (chunk.2 vs chunk.10)
    private static List<DialogueLine> getLinesByKeyPrefix(Properties props, String prefix) {
        List<DialogueLine> lines = props.stringPropertyNames().stream()
            .filter(key -> key.startsWith(prefix))
            .map(key -> new DialogueLine(key, props.getProperty(key)))
            .collect(Collectors.toList());

        lines.sort((l1, l2) -> {
            // Try to extract integer suffixes
            Integer i1 = extractSuffix(l1.key, prefix);
            Integer i2 = extractSuffix(l2.key, prefix);
            
            if (i1 != null && i2 != null) {
                return i1.compareTo(i2); // Numerical Sort (1, 2, 10)
            } else {
                return l1.key.compareTo(l2.key); // Fallback to Alphabetical (String)
            }
        });
        
        return lines;
    }

    private static Integer extractSuffix(String key, String prefix) {
        try {
            String suffix = key.substring(prefix.length());
            return Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return null; // Not a number
        }
    }

    public static DialogueLine getAppAwarenessLine(String category) {
        List<DialogueLine> pool = new ArrayList<>();
        if ("game".equalsIgnoreCase(category)) pool = APP_AWARENESS_GAME_LINES;
        else if ("work".equalsIgnoreCase(category)) pool = APP_AWARENESS_WORK_LINES;
        else pool = APP_AWARENESS_GENERIC_LINES;

        if (pool == null || pool.isEmpty()) return null;
        return pool.get(random.nextInt(pool.size()));
    }

    private static void assignEmptyLineLists() {
        // Omitting for brevity
    }

    // --- Getters ---
    public static Optional<DialogueLine> get420Line() { return Optional.ofNullable(TIME_420_LINES.get(random.nextInt(TIME_420_LINES.size()))); }
    public static Optional<DialogueLine> getModeSwitchLine(String mode) { return Optional.ofNullable(MODE_SWITCH_LINES.get(mode)); }
    public static Optional<DialogueLine> getDiceRollWrongModeLine() { return Optional.ofNullable(DND_DICE_ROLL_WRONG_MODE_LINES.get(0)); }
    public static Optional<DialogueLine> getDiceRollResultLine() { return Optional.ofNullable(DND_DICE_ROLL_RESULT_LINES.get(0)); }
    public static Optional<DialogueLine> getDiceRollErrorLine() { return Optional.ofNullable(DND_DICE_ROLL_ERROR_LINES.get(0)); }
    public static Optional<DialogueLine> getPlaySoundConfirmLine() { return Optional.ofNullable(DND_PLAY_SOUND_CONFIRM_LINES.get(0)); }
    public static Optional<DialogueLine> getLoreCreateLine() { return LORE_CREATE_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getLoreAddLine() { return LORE_ADD_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getLoreRecallSuccessLine() { return LORE_RECALL_SUCCESS_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getLoreRecallFailLine() { return LORE_RECALL_FAIL_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getLoreLinkSuccessLine() { return LORE_LINK_SUCCESS_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getLoreRecallLinksSuccessLine() { return LORE_RECALL_LINKS_SUCCESS_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getLoreRecallLinksFailLine() { return LORE_RECALL_LINKS_FAIL_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getDndRevealSuccessLine() { return DND_REVEAL_SUCCESS_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getDndAnalyzeSuccessLine() { return DND_ANALYZE_SUCCESS_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getDndAnalyzeFailLine() { return DND_ANALYZE_FAIL_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getDndRuleFoundLine() { return DND_RULE_FOUND_LINES.stream().findAny(); }
    public static Optional<DialogueLine> getDndRuleNotFoundLine() { return DND_RULE_NOT_FOUND_LINES.stream().findAny(); }
    
    // CORRECTED METHODS
    public static Optional<DialogueLine> getCpuAlertLine() { return Optional.ofNullable(CPU_ALERT_LINE); }
    public static Optional<DialogueLine> getNvidiaMicFallbackLine() { return Optional.ofNullable(NVIDIA_MIC_FALLBACK_LINE); }

    public static List<DialogueLine> getBootGreetingLines() { return BOOT_GREETING_LINES; }
    public static List<DialogueLine> getLoginGreetingLines() { return LOGIN_GREETING_LINES; }
    public static List<DialogueLine> getWarmLoginGreetingLines() { return WARM_LOGIN_GREETING_LINES; }
    public static List<DialogueLine> getReturnFromIdleLines() { return RETURN_FROM_IDLE_LINES; }
    public static DialogueLine getReturnToGameLine() { return RETURN_TO_GAME_LINES.isEmpty() ? null : RETURN_TO_GAME_LINES.get(random.nextInt(RETURN_TO_GAME_LINES.size())); }
    public static List<DialogueLine> getPhase4InterruptLines() { return PHASE4_INTERRUPT_LINES; }
    public static List<DialogueLine> getPhase1LinesCommon() { return phase1LinesCommon; }
    public static List<DialogueLine> getPhase1LinesRare() { return phase1LinesRare; }
    public static List<DialogueLine> getPhase2LinesCommon() { return phase2LinesCommon; }
    public static List<DialogueLine> getPhase2LinesRare() { return phase2LinesRare; }
    public static List<DialogueLine> getPhase3LinesCommon() { return phase3LinesCommon; }
    public static List<DialogueLine> getPhase3LinesRare() { return phase3LinesRare; }
    public static List<DialogueLine> getPhase3LinesGameRare() { return phase3LinesGameRare; }
    public static List<DialogueLine> getPhase4Chunks() { return phase4Chunks; }
    public static DialogueLine getMemAlertLine() { return MEM_ALERT_LINE; }
    public static DialogueLine getLogoutWarningLine() { return LOGOUT_WARNING_LINE; }
    public static DialogueLine getSeasonalLine(Month month) { return SEASONAL_LINES.get(month); }
    public static Optional<DialogueLine> getMoonPhaseLine(String phaseName) { return Optional.ofNullable(MOON_PHASE_LINES.get(phaseName)); }
    public static Optional<DialogueLine> getEclipseLine(String key) {
        List<DialogueLine> lines = ECLIPSE_LINES.get(key);
        if (lines == null || lines.isEmpty()) return Optional.empty();
        return Optional.of(lines.get(random.nextInt(lines.size())));
    }
    public static Optional<DialogueLine> getPlanetLine(String planetName) { return Optional.ofNullable(PLANET_LINES.get(planetName)); }
    public static List<DialogueLine> getTimeLines() { return TIME_LINES; }
    public static List<DialogueLine> getStatusIntroLines() { return STATUS_INTRO_LINES; }
    public static DialogueLine getStatusCpuLine() { return STATUS_CPU_LINE; }
    public static DialogueLine getStatusMemLine() { return STATUS_MEM_LINE; }
    public static List<DialogueLine> getStatusOutroLines() { return STATUS_OUTRO_LINES; }
    public static DialogueLine getShutdownConfirmLine() { return SHUTDOWN_CONFIRM_LINE; }
    public static DialogueLine getRebootConfirmLine() { return REBOOT_CONFIRM_LINE; }
    public static DialogueLine getCancelSuccessLine() { return CANCEL_SUCCESS_LINE; }
    public static DialogueLine getCancelFailLine() { return CANCEL_FAIL_LINE; }
    public static DialogueLine getRememberSuccessLine() { return REMEMBER_SUCCESS_LINE; }
    public static DialogueLine getRecallSuccessLine() { return RECALL_SUCCESS_LINE; }
    public static DialogueLine getRecallFailLine() { return RECALL_FAIL_LINE; }
    public static List<DialogueLine> getUnrecognizedLines() { return UNRECOGNIZED_LINES; }
    public static Optional<DialogueLine> getDailyReportIntroLine() { 
        if (DAILY_REPORT_INTRO_LINES.isEmpty()) return Optional.empty();
        return Optional.of(DAILY_REPORT_INTRO_LINES.get(random.nextInt(DAILY_REPORT_INTRO_LINES.size())));
    }
    public static Optional<DialogueLine> getWakeWordAckLine() {
        if (WAKE_WORD_ACK_LINES.isEmpty()) return Optional.empty();
        return Optional.of(WAKE_WORD_ACK_LINES.get(random.nextInt(WAKE_WORD_ACK_LINES.size())));
    }
    public static Optional<DialogueLine> getCommandConfirmationLine() {
        if (COMMAND_CONFIRMATION_LINES.isEmpty()) return Optional.empty();
        return Optional.of(COMMAND_CONFIRMATION_LINES.get(random.nextInt(COMMAND_CONFIRMATION_LINES.size())));
    }
    public static DialogueLine getLaunchAppSuccessLine() {
        return LAUNCH_APP_SUCCESS_LINES.isEmpty() ? null : LAUNCH_APP_SUCCESS_LINES.get(random.nextInt(LAUNCH_APP_SUCCESS_LINES.size()));
    }
    public static DialogueLine getLaunchAppFailLine() {
        return LAUNCH_APP_FAIL_LINES.isEmpty() ? null : LAUNCH_APP_FAIL_LINES.get(random.nextInt(LAUNCH_APP_FAIL_LINES.size()));
    }
    
    public static Optional<DialogueLine> getWeatherReportLine() {
        if (WEATHER_REPORT_LINES.isEmpty()) return Optional.empty();
        return Optional.of(WEATHER_REPORT_LINES.get(random.nextInt(WEATHER_REPORT_LINES.size())));
    }
    
    public static Optional<DialogueLine> getWeatherForecastLine() {
        if (WEATHER_FORECAST_LINES == null || WEATHER_FORECAST_LINES.isEmpty()) return Optional.empty();
        return Optional.of(WEATHER_FORECAST_LINES.get(random.nextInt(WEATHER_FORECAST_LINES.size())));
    }

    public static Optional<DialogueLine> getDialogueLine(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        if(key.equals("command.toggle_listening.on")) return TOGGLE_LISTENING_LINES.stream().filter(l -> l.key().equals(key)).findFirst();
        if(key.equals("command.toggle_listening.off")) return TOGGLE_LISTENING_LINES.stream().filter(l -> l.key().equals(key)).findFirst();
        
        if ("command.search_ack.0".equals(key)) {
            return Optional.of(new DialogueLine(key, "サーチング。ワット アー ユー ルッキング フォー？"));
        }
        return Optional.empty();
    }
    
    public static DialogueLine getTopMemoryProcessLine() {
        return TOP_MEMORY_PROCESS_LINES.isEmpty() ? new DialogueLine("","") : TOP_MEMORY_PROCESS_LINES.get(0);
    }
    public static DialogueLine getTopCpuProcessLine() {
        return TOP_CPU_PROCESS_LINES.isEmpty() ? new DialogueLine("","") : TOP_CPU_PROCESS_LINES.get(0);
    }
    public static Optional<DialogueLine> getPrivilegedCommandRequiredLine() {
        if (PRIVILEGED_COMMAND_REQUIRED_LINES.isEmpty()) return Optional.empty();
        return Optional.of(PRIVILEGED_COMMAND_REQUIRED_LINES.get(random.nextInt(PRIVILEGED_COMMAND_REQUIRED_LINES.size())));
    }
    public static DialogueLine getTerminateAppSuccessLine() {
        return TERMINATE_APP_SUCCESS_LINES.isEmpty() ? null : TERMINATE_APP_SUCCESS_LINES.get(random.nextInt(TERMINATE_APP_SUCCESS_LINES.size()));
    }
    public static DialogueLine getTerminateAppFailLine() {
        return TERMINATE_APP_FAIL_LINES.isEmpty() ? null : TERMINATE_APP_FAIL_LINES.get(random.nextInt(TERMINATE_APP_FAIL_LINES.size()));
    }
    public static Optional<DialogueLine> getRoutineStartLine() {
        if (ROUTINE_START_LINES.isEmpty()) return Optional.empty();
        return Optional.of(ROUTINE_START_LINES.get(random.nextInt(ROUTINE_START_LINES.size())));
    }
    public static Optional<DialogueLine> getWebSearchLine() {
        if (WEB_SEARCH_LINES.isEmpty()) return Optional.empty();
        return Optional.of(WEB_SEARCH_LINES.get(random.nextInt(WEB_SEARCH_LINES.size())));
    }
    public static Optional<DialogueLine> getWebSearchFallbackLine() {
        if (WEB_SEARCH_FALLBACK_LINES.isEmpty()) return Optional.empty();
        return Optional.of(WEB_SEARCH_FALLBACK_LINES.get(random.nextInt(WEB_SEARCH_FALLBACK_LINES.size())));
    }
    public static Optional<DialogueLine> getFindAppSuccessLine() {
        if (FIND_APP_SUCCESS_LINES.isEmpty()) return Optional.empty();
        return Optional.of(FIND_APP_SUCCESS_LINES.get(random.nextInt(FIND_APP_SUCCESS_LINES.size())));
    }
    public static Optional<DialogueLine> getFindAppFailLine() {
        if (FIND_APP_FAIL_LINES.isEmpty()) return Optional.empty();
        return Optional.of(FIND_APP_FAIL_LINES.get(random.nextInt(FIND_APP_FAIL_LINES.size())));
    }
    public static Optional<DialogueLine> getScanAppsStartLine() {
        if (SCAN_APPS_START_LINES.isEmpty()) return Optional.empty();
        return Optional.of(SCAN_APPS_START_LINES.get(random.nextInt(SCAN_APPS_START_LINES.size())));
    }
    public static Optional<DialogueLine> getScanAppsSuccessLine() {
        if (SCAN_APPS_SUCCESS_LINES.isEmpty()) return Optional.empty();
        return Optional.of(SCAN_APPS_SUCCESS_LINES.get(random.nextInt(SCAN_APPS_SUCCESS_LINES.size())));
    }
     public static Optional<DialogueLine> getScanAppsFailLine() {
        if (SCAN_APPS_FAIL_LINES.isEmpty()) return Optional.empty();
        return Optional.of(SCAN_APPS_FAIL_LINES.get(random.nextInt(SCAN_APPS_FAIL_LINES.size())));
    }
}