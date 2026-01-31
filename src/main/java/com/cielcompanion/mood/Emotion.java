package com.cielcompanion.mood;

public record Emotion(
    String name,
    double intensity,
    String cause,
    long lastTriggerTimestamp,
    String ssmlStyle,
    String pitch
) {
    // Intensity is a value from 0.0 to 1.0
    public Emotion withIntensity(double newIntensity) {
        return new Emotion(name, newIntensity, cause, lastTriggerTimestamp, ssmlStyle, pitch);
    }

    public Emotion triggeredNow() {
        return new Emotion(name, intensity, cause, System.currentTimeMillis(), ssmlStyle, pitch);
    }
}