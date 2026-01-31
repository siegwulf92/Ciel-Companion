package com.cielcompanion.service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AppProfilerService {

    public record AppProfile(String processName, String displayName, String category, String shortName, Pattern windowTitleRegex, boolean isLauncher) {}

    private static final Map<String, AppProfile> profiles = new HashMap<>();

    public static void initialize() {
        Properties props = new Properties();
        try (InputStream is = AppProfilerService.class.getResourceAsStream("/app_profiles.properties")) {
            if (is == null) {
                System.err.println("Ciel Warning: app_profiles.properties not found. Application awareness will be disabled.");
                return;
            }
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));

            // Group properties by process name (e.g. "discord.exe")
            Map<String, Map<String, String>> propsByProcess = props.stringPropertyNames().stream()
                .filter(key -> key.contains("."))
                .collect(Collectors.groupingBy(
                    key -> key.substring(0, key.lastIndexOf('.')),
                    Collectors.toMap(
                        key -> key.substring(key.lastIndexOf('.') + 1),
                        props::getProperty
                    )
                ));

            for (Map.Entry<String, Map<String, String>> entry : propsByProcess.entrySet()) {
                String processKey = entry.getKey();
                Map<String, String> values = entry.getValue();
                
                String name = values.getOrDefault("name", processKey);
                String category = values.getOrDefault("category", "Generic");
                String regexStr = values.get("windowTitleRegex");
                Pattern regex = (regexStr != null && !regexStr.isBlank()) ? Pattern.compile(regexStr) : null;
                boolean isLauncher = Boolean.parseBoolean(values.getOrDefault("isLauncher", "false"));
                
                String shortName = null;
                if (name.contains(":")) {
                    shortName = name.substring(name.indexOf(":") + 1).trim();
                }

                profiles.put(processKey.toLowerCase(), new AppProfile(processKey, name, category, shortName, regex, isLauncher));
            }
            System.out.println("Ciel Debug: Loaded " + profiles.size() + " application profiles.");
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to load application profiles.");
            e.printStackTrace();
        }
    }

    public static AppProfile getProfile(String processName) {
        return profiles.get(processName.toLowerCase());
    }

    /**
     * Smart Identification: Tries exact process match first, then falls back to Window Title Regex.
     */
    public static AppProfile identifyActiveApp(String processName, String windowTitle) {
        if (processName == null) return null;
        String lowerProc = processName.toLowerCase();

        // 1. Direct Process Match
        if (profiles.containsKey(lowerProc)) {
            return profiles.get(lowerProc);
        }

        // 2. Window Title Regex Match (Context Awareness)
        if (windowTitle != null && !windowTitle.isBlank()) {
            for (AppProfile p : profiles.values()) {
                if (p.windowTitleRegex() != null && p.windowTitleRegex().matcher(windowTitle).find()) {
                    System.out.println("Ciel Debug: Identified '" + processName + "' as '" + p.displayName() + "' via Window Title match.");
                    return p;
                }
            }
        }

        return null;
    }

    /**
     * Finds a profile by a friendly name (e.g., "discord" instead of "discord.exe").
     */
    public static Optional<AppProfile> findProfileByFuzzyName(String fuzzyName) {
        if (fuzzyName == null || fuzzyName.isBlank()) return Optional.empty();
        String lowerFuzzyName = fuzzyName.toLowerCase();

        // Check for exact displayName match first
        for (AppProfile p : profiles.values()) {
            if (p.displayName().equalsIgnoreCase(lowerFuzzyName)) {
                return Optional.of(p);
            }
        }
        // Then check if the executable name contains the fuzzy name
        for (AppProfile p : profiles.values()) {
            if (p.processName().toLowerCase().contains(lowerFuzzyName)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}