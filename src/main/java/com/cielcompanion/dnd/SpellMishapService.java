package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public class SpellMishapService {
    private JsonObject mishapTable;
    private final Random random = new Random();

    public SpellMishapService() {
        loadTable();
    }

    private void loadTable() {
        String pathStr = Settings.getDndCampaignPath();
        if (pathStr == null) return;
        try {
            Path path = Paths.get(pathStr, "data", "spell_mishaps.json");
            if (Files.exists(path)) {
                mishapTable = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Could not load spell_mishaps.json");
        }
    }

    public void checkMishap(String casterName, int checkResult, int dc) {
        if (mishapTable == null || !mishapTable.has("effects")) return;
        
        int margin = checkResult - dc;
        
        // Logic: If they FAIL the check (margin < 0), chaos happens.
        // Or if you just want pure randomness for scrolls.
        if (margin < 0) {
            JsonArray effects = mishapTable.getAsJsonArray("effects");
            // Simple D20-like roll on the array
            int roll = random.nextInt(effects.size()); 
            String effect = effects.get(roll).getAsString();
            
            SpeechService.speakPreformatted("Magical instability detected for " + casterName + ". Effect: " + effect);
        } else {
            SpeechService.speakPreformatted("Spell stability confirmed. No mishap.");
        }
    }
}