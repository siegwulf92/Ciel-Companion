package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public class SpellCheckService {
    private JsonArray tiers;
    private final Random random = new Random();

    public SpellCheckService() {
        loadTable();
    }

    private void loadTable() {
        String pathStr = Settings.getDndCampaignPath();
        if (pathStr == null) return;
        try {
            Path path = Paths.get(pathStr, "data", "spell_check_tiers.json");
            if (Files.exists(path)) {
                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (root.has("tiers")) {
                    tiers = root.getAsJsonArray("tiers");
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Could not load spell_check_tiers.json");
        }
    }

    public void processSpellCheck(String casterName, int rollResult) {
        if (tiers == null) {
            SpeechService.speak("Spell check database not loaded.");
            return;
        }

        String outcome = "UNKNOWN";
        String response = "Processing spell result.";

        for (JsonElement el : tiers) {
            JsonObject tier = el.getAsJsonObject();
            String range = tier.get("range").getAsString();
            if (isRollInRange(rollResult, range)) {
                outcome = tier.get("outcome").getAsString();
                JsonArray responses = tier.getAsJsonArray("responses");
                response = responses.get(random.nextInt(responses.size())).getAsString();
                break;
            }
        }

        // Add flavor for specific outcomes
        if ("CRITICAL_FAILURE".equals(outcome)) {
            // Trigger your manual lookup table here or just announce the fail
            SpeechService.speakPreformatted("Critical failure for " + casterName + ". " + response);
        } else if ("ENHANCED_SUCCESS".equals(outcome)) {
            SpeechService.speakPreformatted("Enhanced cast for " + casterName + ". " + response);
        } else {
            SpeechService.speakPreformatted(response);
        }
    }

    private boolean isRollInRange(int roll, String range) {
        if (range.contains("-")) {
            String[] parts = range.split("-");
            int min = Integer.parseInt(parts[0]);
            int max = Integer.parseInt(parts[1]);
            return roll >= min && roll <= max;
        } else {
            return roll == Integer.parseInt(range);
        }
    }
}