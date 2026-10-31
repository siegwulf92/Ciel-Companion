package com.cielcompanion.service;

import java.util.Locale;

/** Optional media-aware references; astronomy measurements remain source-grounded. */
public final class AstronomyMetaReferences {
    private AstronomyMetaReferences() {}
    public static String referenceFor(String objectId) {
        String media = HabitTrackerService.getCurrentMediaTitle();
        if (media == null || media.isBlank() || objectId == null) return "";
        String title = media.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (!(title.contains("rezero") || title.contains("startinglifeinanotherworld"))) return "";
        return switch (objectId.toLowerCase(Locale.ROOT)) {
            case "regulus" -> "Regulus Corneas, the Sin Archbishop of Greed";
            case "sirius" -> "Sirius Romanée-Conti, the Sin Archbishop of Wrath";
            case "betelgeuse" -> "Betelgeuse Romanée-Conti, the Sin Archbishop of Sloth";
            case "shaula" -> "Shaula, the Scorpion";
            default -> "";
        };
    }
}
