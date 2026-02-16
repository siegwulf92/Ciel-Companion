package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class DynamicResponses {

    private JsonObject responseDb;
    private final Random random = new Random();
    private static final String DEFAULT_RESPONSE = "Data unclear.";

    public DynamicResponses() {
        loadDatabase();
    }

    private void loadDatabase() {
        String campaignPath = Settings.getDndCampaignPath();
        if (campaignPath == null) return;
        
        try {
            Path path = Paths.get(campaignPath, "data", "ciel_dynamic_responses.json");
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                responseDb = JsonParser.parseString(json).getAsJsonObject();
                System.out.println("Ciel Debug: Dynamic Response database loaded.");
            } else {
                System.err.println("Ciel Warning: ciel_dynamic_responses.json not found at " + path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * The core brain for selecting a line.
     * @param context The situation tags (e.g., "combat", "spell_failure", "exploration").
     * @param act The current campaign Act (1, 2, or 3).
     * @param worldsCompleted Number of worlds finished (for Act 2 scaling).
     * @param perfectWorlds Number of 100% worlds (for Act 2 scaling).
     */
    public String getDynamicLine(String context, int act, int worldsCompleted, int perfectWorlds) {
        if (responseDb == null) return DEFAULT_RESPONSE;

        // 1. Check for Foreshadowing Override (Act 2 Only)
        if (act == 2 && shouldTriggerForeshadowing(worldsCompleted, perfectWorlds)) {
            String line = pickLineFromCategory("narrative_foreshadowing", context);
            if (line != null) return line;
        }

        // 2. Determine Category based on Act Weights
        String category = pickCategoryByWeight(act);
        
        // 3. Pick line from that category matching context
        String line = pickLineFromCategory(category, context);
        
        // Fallback: If no line found in that category for that context, try "neutral" or "system_diagnostic"
        if (line == null) {
            line = pickLineFromCategory("neutral_diegetic", "universal");
        }
        
        return line != null ? line : "Processing.";
    }

    private boolean shouldTriggerForeshadowing(int worlds, int perfects) {
        // Base 5% + 2% per world + 3% per perfect world
        int chance = 5 + (2 * worlds) + (3 * perfects);
        // Cap at 45% just in case
        chance = Math.min(chance, 45);
        return random.nextInt(100) < chance;
    }

    private String pickCategoryByWeight(int act) {
        if (!responseDb.has("config") || !responseDb.getAsJsonObject("config").has("act_weights")) {
            return "neutral_diegetic";
        }
        
        JsonObject weights = responseDb.getAsJsonObject("config")
                .getAsJsonObject("act_weights")
                .getAsJsonObject(String.valueOf(act));
                
        int roll = random.nextInt(100);
        int currentThreshold = 0;
        
        // Iterate through keys: diagnostic, neutral, etc.
        for (String cat : weights.keySet()) {
            currentThreshold += weights.get(cat).getAsInt();
            if (roll < currentThreshold) {
                // Map short keys to full category names in JSON
                return mapKeyToCategory(cat);
            }
        }
        return "neutral_diegetic";
    }

    private String mapKeyToCategory(String key) {
        switch (key) {
            case "diagnostic": return "system_diagnostic";
            case "mystical": return "mystical_arcane";
            case "neutral": return "neutral_diegetic";
            case "reassuring": return "reassuring_supportive";
            case "foreshadowing": return "narrative_foreshadowing";
            case "humor": return "soft_humor";
            default: return "neutral_diegetic";
        }
    }

    private String pickLineFromCategory(String category, String context) {
        if (!responseDb.has("categories") || !responseDb.getAsJsonObject("categories").has(category)) {
            return null;
        }
        
        JsonArray lines = responseDb.getAsJsonObject("categories").getAsJsonArray(category);
        List<String> candidates = new ArrayList<>();
        
        for (JsonElement el : lines) {
            JsonObject obj = el.getAsJsonObject();
            JsonArray tags = obj.getAsJsonArray("tags");
            boolean match = false;
            
            // Check if any tag matches our context (or if line is universal)
            for (JsonElement t : tags) {
                String tag = t.getAsString();
                if (tag.equalsIgnoreCase(context) || tag.equalsIgnoreCase("universal")) {
                    match = true;
                    break;
                }
            }
            
            if (match) {
                candidates.add(obj.get("text").getAsString());
            }
        }
        
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }
}