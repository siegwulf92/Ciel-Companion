package com.cielcompanion.mood;

import com.cielcompanion.CielState;
import com.cielcompanion.memory.stwm.ShortTermMemoryService;
import java.awt.Color;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EmotionalState {

    private final Map<String, Emotion> activeEmotions = new ConcurrentHashMap<>();
    private static final double DECAY_RATE_PER_SECOND = 0.05; // 5% decay per second
    private String lastDominantEmotionName = ""; // To track for logging

    public void triggerEmotion(String name, double intensity, String cause) {
        MoodConfig.EmotionDefinition def = MoodConfig.getEmotionDef(name).orElse(null);
        if (def == null) return;

        String ssmlStyle = def.ssmlStyle();
        String pitch = def.pitch();

        Emotion current = activeEmotions.get(name);
        double newIntensity = (current != null) ? Math.min(1.0, current.intensity() + intensity) : intensity;
        
        if (newIntensity <= 0) {
            activeEmotions.remove(name);
        } else {
            activeEmotions.put(name, new Emotion(name, newIntensity, cause, System.currentTimeMillis(), ssmlStyle, pitch));
        }
    }

    public void applyDecay(long deltaTimeMillis) {
        if (deltaTimeMillis <= 0) return;
        double decayFactor = (deltaTimeMillis / 1000.0) * DECAY_RATE_PER_SECOND;

        int currentPhase = ShortTermMemoryService.getMemory().getCurrentPhase();
        String stickyEmotionName = "IdlePhase" + currentPhase;

        for (Emotion emotion : activeEmotions.values()) {
            if ((currentPhase > 0 && emotion.name().equals(stickyEmotionName)) || 
                (currentPhase == 0 && emotion.name().equals("Focused"))) {
                continue;
            }

            double newIntensity = Math.max(0, emotion.intensity() - decayFactor);
            if (newIntensity == 0) {
                activeEmotions.remove(emotion.name());
            } else {
                activeEmotions.put(emotion.name(), emotion.withIntensity(newIntensity));
            }
        }
    }
    
    public Optional<Emotion> getDominantEmotion() {
        return activeEmotions.values().stream()
            .max((e1, e2) -> Double.compare(e1.intensity(), e2.intensity()));
    }

    public record VisualState(Color color, MoodConfig.AnimationStyle animation, double brightness) {}

    public VisualState getVisualState() {
        if (activeEmotions.isEmpty()) {
            triggerEmotion("Observing", 0.5, null);
        }

        Emotion dominantEmotion = getDominantEmotion().orElse(new Emotion("Observing", 1.0, null, 0, "default", "default"));

        if (!dominantEmotion.name().equals(lastDominantEmotionName)) {
            String activeEmotionsString = activeEmotions.values().stream()
                .map(e -> String.format("%s(%.2f)", e.name(), e.intensity()))
                .collect(Collectors.joining(", "));
            System.out.printf("Ciel Debug: Dominant emotion changed from %s to %s. Active emotions: [%s]%n",
                lastDominantEmotionName.isEmpty() ? "None" : lastDominantEmotionName,
                dominantEmotion.name(),
                activeEmotionsString);
            lastDominantEmotionName = dominantEmotion.name();
        }

        MoodConfig.AnimationStyle animation = MoodConfig.getEmotionDef(dominantEmotion.name())
            .map(MoodConfig.EmotionDefinition::animation)
            .orElse(MoodConfig.AnimationStyle.GENTLE_PULSE);

        float totalIntensity = (float) activeEmotions.values().stream().mapToDouble(Emotion::intensity).sum();
        if (totalIntensity == 0) {
             return new VisualState(parseColor("100, 100, 255"), animation, 0.5);
        }

        float r = 0, g = 0, b = 0;
        for (Emotion e : activeEmotions.values()) {
            Color emotionColor = getColorForEmotion(e);
            float weight = (float) (e.intensity() / totalIntensity);
            r += emotionColor.getRed() * weight;
            g += emotionColor.getGreen() * weight;
            b += emotionColor.getBlue() * weight;
        }

        Color blendedColor = new Color(clamp(r), clamp(g), clamp(b));
        double brightness = Math.min(1.0, totalIntensity);

        return new VisualState(blendedColor, animation, brightness);
    }
    
    private Color getColorForEmotion(Emotion emotion) {
        return MoodConfig.getEmotionDef(emotion.name()).map(def -> {
            if (emotion.cause() != null && def.causes() != null && def.causes().containsKey(emotion.cause())) {
                return parseColor(def.causes().get(emotion.cause()).colorTint());
            }
            return parseColor(def.baseColor());
        }).orElse(Color.MAGENTA);
    }

    private Color parseColor(String rgb) {
        if (rgb == null || rgb.isBlank()) return Color.MAGENTA;
        String[] parts = rgb.split(",");
        return new Color(
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim())
        );
    }
    
    private int clamp(float value) {
        return Math.max(0, Math.min(255, (int) value));
    }

    public Map<String, Emotion> getActiveEmotions() {
        return activeEmotions;
    }
}