package com.cielcompanion.service;

import java.util.Locale;
import java.util.List;

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

    record Selection(String line, boolean fromModel, String reason) {}

    static Selection selectLine(String name, String response, GameProgress.Context history) {
        List<String> fallbacks = fallbackLines(name, history);
        String fallback = fallbacks.stream()
                .filter(line -> history.recentComments().stream().noneMatch(old -> similar(old, line)))
                .findFirst().orElse(fallbacks.get(Math.floorMod(history.priorSessions(), fallbacks.size())));
        if (response == null || response.isBlank() || response.contains("ABORT") || response.contains("DATA_DEFICIT"))
            return new Selection(fallback, false, "model unavailable or timed out");
        String candidate = response.trim();
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (name.startsWith("Kingdom Hearts") && !(lower.contains("sora") || lower.contains("keyblade")
                || lower.contains("heartless") || lower.contains("castle oblivion") || lower.contains("card")
                || lower.contains("terra") || lower.contains("aqua") || lower.contains("ventus")
                || lower.contains("roxas") || lower.contains(name.toLowerCase(Locale.ROOT))))
            return new Selection(fallback, false, "model response lacked grounded game context");
        String formatted = candidate.matches("(?s)^\\[[a-zA-Z]+\\].*") ? candidate : "[Curious] " + candidate;
        if (history.recentComments().stream().anyMatch(old -> similar(old, formatted)))
            return new Selection(fallback, false, "model repeated recent dialogue");
        return new Selection(formatted, true, "grounded model response");
    }

    private static List<String> fallbackLines(String name, GameProgress.Context history) {
        long minutes = history.priorSeconds() / 60;
        return switch (name) {
            case "Kingdom Hearts Final Mix" -> List.of(
                    "[Curious] Master, Sora has the Keyblade in hand again. I will keep your return to Final Mix company without getting ahead of you.",
                    "[Happy] Back to Sora's first journey, Master. Let us see where the Keyblade takes you this session.",
                    "[Observing] You have at least " + minutes + " minutes recorded in Final Mix, Master. I will follow this session without spoiling what lies ahead.");
            case "Kingdom Hearts Re:Chain of Memories" -> history.earlierTitles().contains("Kingdom Hearts Final Mix")
                    ? List.of(
                        "[Curious] From Final Mix's worlds to Castle Oblivion's cards, Master. I remember the journey that brought Sora here.",
                        "[Observing] Castle Oblivion changes the rules, Master; your recorded time with Final Mix is the only earlier story context I will use.",
                        "[Happy] The Keyblade journey continues through a very different deck, Master. I will keep later games out of my analysis.")
                    : List.of(
                        "[Curious] Master, Castle Oblivion's card battles await. I wonder what kind of deck you will build.",
                        "[Observing] I see Sora entering Castle Oblivion, Master. I will keep my comments within what you have actually played.");
            case "Kingdom Hearts II Final Mix" -> List.of(
                    "[Curious] Master, Sora's Keyblade journey continues in Kingdom Hearts II Final Mix; I will draw only on the earlier games you have played.",
                    "[Happy] Another chapter of Sora's journey is open, Master. I remember your earlier Kingdom Hearts sessions and will avoid later knowledge.");
            case "Kingdom Hearts Birth by Sleep Final Mix" -> List.of(
                    "[Curious] Master, Terra, Aqua, and Ventus each have a path to follow. I will keep track without reaching into later games.",
                    "[Observing] Three paths are in view, Master. I will ground my comments in this session and your earlier recorded games.");
            case "Kingdom Hearts 358/2 Days" -> List.of(
                    "[Curious] Master, Roxas's story is on screen. I will follow along without getting ahead of you.",
                    "[Observing] I recognize Roxas's chapter, Master. I will use only the Kingdom Hearts history you have already played.");
            default -> List.of(
                    "[Curious] Master, I see you have started " + name + ". I will learn this session without inventing details.",
                    "[Observing] " + name + " is active, Master. I will keep track of the time you spend here and adapt as I learn more.");
        };
    }

    private static boolean similar(String first, String second) {
        String a = normalize(first), b = normalize(second);
        return a.equals(b) || (a.length() > 35 && b.length() > 35
                && (a.contains(b.substring(0, Math.min(35, b.length())))
                || b.contains(a.substring(0, Math.min(35, a.length())))));
    }

    private static String normalize(String text) {
        return (text == null ? "" : text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
