package com.cielcompanion.dnd;

import com.cielcompanion.service.Settings;
import com.cielcompanion.service.SpeechService;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class DndCampaignService {
    private JsonObject surges, memories, patron, quirks;
    private JsonObject currentWorldConfig;
    private final Path campaignRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Random random = new Random();
    
    // NEW: The Dynamic Brain
    private final DynamicResponses dynamicResponses;
    
    // Manual tracking for now (could be saved to a JSON state file later)
    private int currentAct = 1;
    private int worldsCompleted = 0;
    private int perfectWorlds = 0;

    public DndCampaignService() {
        String pathStr = Settings.getDndCampaignPath();
        this.campaignRoot = (pathStr != null && !pathStr.isBlank()) ? Paths.get(pathStr) : null;
        this.dynamicResponses = new DynamicResponses();
        loadAllData();
    }

    // ... [Existing loadAllData, loadJson, loadWorldConfig methods] ...

    // --- NEW DYNAMIC METHODS ---

    /**
     * Called when a spell fails due to plot/metaphysics.
     * Guarantees a "Spell Refund" or "Metaphysics" line.
     */
    public void triggerSpellRefund(String spellName) {
        // Force context "spell_refund"
        String line = dynamicResponses.getDynamicLine("spell_refund", currentAct, worldsCompleted, perfectWorlds);
        SpeechService.speakPreformatted(line);
    }

    /**
     * Called periodically or manually to comment on the environment.
     */
    public void triggerAmbientCommentary(String context) {
        // Context could be "exploration", "danger", "eerie"
        String line = dynamicResponses.getDynamicLine(context, currentAct, worldsCompleted, perfectWorlds);
        SpeechService.speakPreformatted(line);
    }
    
    /**
     * Called when the party successfully finishes a task/combat.
     */
    public void triggerSuccessCommentary() {
        String line = dynamicResponses.getDynamicLine("success", currentAct, worldsCompleted, perfectWorlds);
        SpeechService.speakPreformatted(line);
    }
    
    // State Setters (You can wire these to voice commands later if needed)
    public void setAct(int act) { this.currentAct = act; }
    public void addWorldCompletion(boolean isPerfect) {
        this.worldsCompleted++;
        if (isPerfect) this.perfectWorlds++;
    }

    // ... [Existing methods: checkQuirks, translatePatron, incrementSurge, etc.] ...

    // (Include the rest of your existing DndCampaignService code here)
    // I am omitting the repetitive existing methods to save space, 
    // but ensure you keep loadJson, saveJson, etc.
    
    private void loadAllData() {
        if (campaignRoot == null) return;
        surges = loadJson("data/surge_tracker.json");
        memories = loadJson("data/memory_fragments.json");
        patron = loadJson("data/patron_translations.json");
        quirks = loadJson("data/character_quirks.json");
    }
    
    // ... [Include loadWorldConfig, speakWorldResponse, etc from previous version] ...
    
    private JsonObject loadJson(String relativePath) {
        try {
            Path path = campaignRoot.resolve(relativePath);
            if (!Files.exists(path)) return new JsonObject();
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("Ciel Warning: Failed to load JSON at " + relativePath);
            return new JsonObject();
        }
    }
    
    public void checkQuirks(String speaker, String text) {
        if (quirks == null || !quirks.has("quirks")) return;
        JsonArray qArray = quirks.getAsJsonArray("quirks");
        for (JsonElement e : qArray) {
            JsonObject q = e.getAsJsonObject();
            if (q.get("character").getAsString().equalsIgnoreCase(speaker)) {
                String trigger = q.get("trigger_keyword").getAsString().toLowerCase();
                if (text.toLowerCase().contains(trigger)) {
                    speakRandomResponse(q);
                    return;
                }
            }
        }
    }

    public void incrementSurge(String player) {
        if (surges == null || !surges.has("player_stats")) return;
        JsonObject stats = surges.getAsJsonObject("player_stats");
        if (stats.has(player)) {
            JsonObject pStat = stats.getAsJsonObject(player);
            int current = pStat.get("surge_count").getAsInt();
            pStat.addProperty("surge_count", current + 1);
            saveJson("data/surge_tracker.json", surges);
            SpeechService.speakPreformatted("Surge recorded for " + player + ". Total count is now " + (current + 1) + ".");
        }
    }

    private void speakRandomResponse(JsonObject quirkObject) {
        String response = "";
        if (quirkObject.has("ciel_responses")) {
            JsonArray responses = quirkObject.getAsJsonArray("ciel_responses");
            if (responses.size() > 0) response = responses.get(random.nextInt(responses.size())).getAsString();
        }
        if (response.isEmpty() && quirkObject.has("ciel_response")) {
            response = quirkObject.get("ciel_response").getAsString();
        }
        if (!response.isEmpty()) SpeechService.speakPreformatted(response);
    }

    private void saveJson(String relativePath, JsonObject data) {
        try {
            Path path = campaignRoot.resolve(relativePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) { e.printStackTrace(); }
    }
}