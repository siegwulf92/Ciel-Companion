package com.cielcompanion.mood;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

public record MoodConfig(Map<String, EmotionDefinition> emotions) {

    public record EmotionDefinition(String baseColor, AnimationStyle animation, String ssmlStyle, String pitch, Map<String, CauseDefinition> causes) {}
    public record CauseDefinition(String colorTint) {}
    public enum AnimationStyle { GENTLE_PULSE, SHARP_FLICKER, SLOW_BURN, SLOW_FADE, RAINBOW_CYCLE, ERRATIC_FLICKER }

    private static MoodConfig instance;

    public static synchronized MoodConfig getInstance() {
        if (instance == null) {
            try (InputStream is = MoodConfig.class.getResourceAsStream("/moods.json")) {
                if (is == null) {
                    throw new RuntimeException("CRITICAL: moods.json not found in resources.");
                }
                instance = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), MoodConfig.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load moods.json", e);
            }
        }
        return instance;
    }

    public static Optional<EmotionDefinition> getEmotionDef(String name) {
        return Optional.ofNullable(getInstance().emotions.get(name));
    }
}