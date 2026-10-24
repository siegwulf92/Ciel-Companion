package com.cielcompanion.service;

import java.util.Locale;

/** Spoiler-safe game context when a launch model is busy or returns a generic line. */
final class GameLaunchCommentary {
    private GameLaunchCommentary() {}

    static String grounding(String name) {
        return switch (name) {
            case "Kingdom Hearts Final Mix" -> "Grounded premise: Sora wields the Keyblade and faces the Heartless while visiting different worlds.";
            case "Kingdom Hearts II Final Mix" -> "Grounded premise: Sora continues his Keyblade journey across worlds.";
            case "Kingdom Hearts Re:Chain of Memories" -> "Grounded premise: Castle Oblivion and a card-based battle system shape the game.";
            case "Kingdom Hearts Birth by Sleep Final Mix" -> "Grounded premise: Terra, Aqua, and Ventus have distinct paths.";
            case "Kingdom Hearts 358/2 Days" -> "Grounded premise: Roxas is central to this story.";
            default -> "Only the game's title and executable are known; do not invent its characters or events.";
        };
    }

    static String selectLine(String name, String response) {
        String fallback = switch (name) {
            case "Kingdom Hearts Final Mix" -> "[Curious] Master, Sora has the Keyblade in hand; I hope your journey through Final Mix's worlds goes well.";
            case "Kingdom Hearts II Final Mix" -> "[Curious] Master, Sora's Keyblade journey continues in Kingdom Hearts II Final Mix. I am here if you want to talk strategy.";
            case "Kingdom Hearts Re:Chain of Memories" -> "[Curious] Master, Castle Oblivion's card battles await. I wonder what kind of deck you will build.";
            case "Kingdom Hearts Birth by Sleep Final Mix" -> "[Curious] Master, Terra, Aqua, and Ventus each have a path to follow. I will keep track of where you are.";
            case "Kingdom Hearts 358/2 Days" -> "[Curious] Master, Roxas's story is on screen. I will follow along without getting ahead of you.";
            default -> "[Curious] Master, I see you have started " + name + ". I am here if you want help with a tough section.";
        };
        if (response == null || response.isBlank() || response.contains("ABORT") || response.contains("DATA_DEFICIT"))
            return fallback;
        String candidate = response.trim();
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (name.startsWith("Kingdom Hearts") && !(lower.contains("sora") || lower.contains("keyblade")
                || lower.contains("heartless") || lower.contains("castle oblivion") || lower.contains("card")
                || lower.contains("terra") || lower.contains("aqua") || lower.contains("ventus")
                || lower.contains("roxas"))) return fallback;
        return candidate.matches("(?s)^\\[[a-zA-Z]+\\].*") ? candidate : "[Curious] " + candidate;
    }
}
