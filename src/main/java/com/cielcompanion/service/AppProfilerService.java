package com.cielcompanion.service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AppProfilerService {

    public record AppProfile(String processName, String displayName, String category, String shortName, Pattern windowTitleRegex, boolean isLauncher) {}

    // The foreground classifier can read profiles while heavy boot is still
    // loading them. A plain HashMap can expose partial state or fail during a
    // values() iteration; a concurrent map gives readers a safe snapshot-like
    // view until initialization finishes.
    private static final Map<String, AppProfile> profiles = new ConcurrentHashMap<>();
    
    // Caches dynamically identified apps to prevent log flooding during active gaming
    private static final Map<String, AppProfile> dynamicCache = new ConcurrentHashMap<>();

    public static void initialize() {
        dynamicCache.clear();
        Map<String, AppProfile> loadedProfiles = new HashMap<>();
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

                loadedProfiles.put(processKey.toLowerCase(), new AppProfile(processKey, name, category, shortName, regex, isLauncher));
            }
            profiles.clear();
            profiles.putAll(loadedProfiles);
            System.out.println("Ciel Debug: Loaded " + profiles.size() + " application profiles.");
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to load application profiles.");
            e.printStackTrace();
        }
    }

    public static AppProfile getProfile(String processName) {
        return profiles.get(processName.toLowerCase());
    }

    // Exposes the profiles so the Habit Tracker can iterate through them
    public static java.util.Collection<AppProfile> getAllProfiles() {
        return profiles.values();
    }

    /**
     * Smart Identification: Tries exact process match first, then falls back to Window Title Regex.
     */
    public static AppProfile identifyActiveApp(String processName, String windowTitle) {
        if (processName == null) return null;
        String lowerProc = processName.toLowerCase();

        // 0. Check the fast cache first to silence log spam
        String cacheKey = lowerProc + "\n" + (windowTitle == null ? "" : windowTitle.toLowerCase());
        if (dynamicCache.containsKey(cacheKey)) {
            return dynamicCache.get(cacheKey);
        }

        // 1. Direct Process Match
        if (profiles.containsKey(lowerProc)) {
            AppProfile p = profiles.get(lowerProc);
            if (!p.isLauncher()) { // Do not identify launchers as the active game
                return p;
            }
        }

        // 2. Window Title Regex Match (Context Awareness)
        if (windowTitle != null && !windowTitle.isBlank()) {
            for (AppProfile p : profiles.values()) {
                if (p.windowTitleRegex() != null && p.windowTitleRegex().matcher(windowTitle).find()) {
                    
                    // SAFETY GUARD: Do not let web browsers trigger gaming mode due to YouTube video titles
                    if ("Game".equalsIgnoreCase(p.category()) && 
                        (lowerProc.contains("chrome") || lowerProc.contains("firefox") || 
                         lowerProc.contains("edge") || lowerProc.contains("opera") || lowerProc.contains("brave"))) {
                        continue;
                    }

                    // SAFETY GUARD: Do not identify launchers based on title
                    if (p.isLauncher()) {
                        continue;
                    }
                    
                    System.out.println("Ciel Debug: Identified '" + processName + "' as '" + p.displayName() + "' via Window Title match.");
                    dynamicCache.put(cacheKey, p); // A changed title must be classified afresh.
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
