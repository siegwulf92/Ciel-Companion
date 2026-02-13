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
    private JsonObject currentWorldConfig; // NEW: Holds active world data
    private final Path campaignRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Random random = new Random();

    public DndCampaignService() {
        String pathStr = Settings.getDndCampaignPath();
        this.campaignRoot = (pathStr != null && !pathStr.isBlank()) ? Paths.get(pathStr) : null;
        loadAllData();
    }

    private void loadAllData() {
        if (campaignRoot == null) return;
        surges = loadJson("data/surge_tracker.json");
        memories = loadJson("data/memory_fragments.json");
        patron = loadJson("data/patron_translations.json");
        quirks = loadJson("data/character_quirks.json");
    }

    // NEW: Call this when entering a new world
    public void loadWorldConfig(String worldFolderName) {
        if (campaignRoot == null) return;
        String relativePath = "Worlds/" + worldFolderName + "/world_config.json";
        currentWorldConfig = loadJson(relativePath);
        
        if (currentWorldConfig.size() > 0) {
            String worldName = currentWorldConfig.has("world_name") ? currentWorldConfig.get("world_name").getAsString() : worldFolderName;
            System.out.println("Ciel Debug: Loaded config for world: " + worldName);
            
            // Auto-play entrance line if available
            speakWorldResponse("enter");
        } else {
            System.out.println("Ciel Warning: No config found for " + worldFolderName);
        }
    }

    public String getCurrentAmbientTrack() {
        if (currentWorldConfig != null && currentWorldConfig.has("music")) {
            JsonObject music = currentWorldConfig.getAsJsonObject("music");
            if (music.has("ambient_track")) return music.get("ambient_track").getAsString();
        }
        return null;
    }

    public String getCurrentBattleTrack() {
        if (currentWorldConfig != null && currentWorldConfig.has("music")) {
            JsonObject music = currentWorldConfig.getAsJsonObject("music");
            if (music.has("battle_track")) return music.get("battle_track").getAsString();
        }
        return null;
    }

    // Helper to speak random world-specific lines
    public void speakWorldResponse(String key) {
        if (currentWorldConfig == null || !currentWorldConfig.has("ciel_responses")) return;
        JsonObject responses = currentWorldConfig.getAsJsonObject("ciel_responses");
        
        if (responses.has(key)) {
            JsonArray lines = responses.getAsJsonArray(key);
            if (lines.size() > 0) {
                String line = lines.get(random.nextInt(lines.size())).getAsString();
                SpeechService.speakPreformatted(line);
            }
        }
    }

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

    // ... [Rest of your existing methods: checkQuirks, translatePatron, incrementSurge, etc.] ...
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

    public String translatePatron(String message) {
        if (patron == null || !patron.has("lexicon")) return null;
        JsonObject lexicon = patron.getAsJsonObject("lexicon");
        String lowerMsg = message.toLowerCase();
        for (String key : lexicon.keySet()) {
            if (lowerMsg.contains(key.toLowerCase())) return lexicon.get(key).getAsString();
        }
        return null;
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