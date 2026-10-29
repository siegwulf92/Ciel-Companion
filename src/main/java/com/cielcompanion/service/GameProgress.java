package com.cielcompanion.service;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Durable, cross-device observation history for games. It never claims story completion. */
final class GameProgress {
    record Context(int priorSessions, long priorSeconds, List<String> earlierTitles,
                   List<String> recentComments) {
        String promptContext() {
            String duration = priorSeconds < 60
                    ? "No meaningful prior play time has been observed for this exact game."
                    : "Before this launch, at least " + Math.max(1, priorSeconds / 60)
                    + " minutes across " + priorSessions + " observed session(s) were recorded for this exact game.";
            String earlier = earlierTitles.isEmpty()
                    ? "No earlier game in this series has verified observed play history."
                    : "Earlier games in this series with observed play history, in safe reference order: "
                    + String.join(" -> ", earlierTitles) + ".";
            String repeated = recentComments.isEmpty()
                    ? "No recent launch lines are recorded."
                    : "Do not repeat or closely paraphrase these recent launch lines: "
                    + String.join(" | ", recentComments) + ".";
            return duration + " " + earlier + " " + repeated
                    + " Never reference a later game in the series, and never claim the Master completed story content merely from elapsed time.";
        }
    }

    private static final Map<String, Integer> KH_ORDER = new LinkedHashMap<>();
    private static final Map<String, Long> BASE_SECONDS = new java.util.HashMap<>();
    private static final Map<String, Long> LAST_WRITE = new java.util.HashMap<>();

    static {
        KH_ORDER.put("Kingdom Hearts Final Mix", 10);
        KH_ORDER.put("Kingdom Hearts Re:Chain of Memories", 20);
        KH_ORDER.put("Kingdom Hearts 358/2 Days", 30);
        KH_ORDER.put("Kingdom Hearts II Final Mix", 40);
        KH_ORDER.put("Kingdom Hearts Birth by Sleep Final Mix", 50);
        KH_ORDER.put("Kingdom Hearts Re:coded", 60);
        KH_ORDER.put("Kingdom Hearts Dream Drop Distance HD", 70);
        KH_ORDER.put("Kingdom Hearts 0.2 Birth by Sleep", 80);
        KH_ORDER.put("Kingdom Hearts III", 90);
    }

    private GameProgress() {}

    static synchronized Context begin(String title, long now) {
        JsonObject previous = read(title);
        int priorSessions = integer(previous, "session_count");
        long priorSeconds = number(previous, "total_observed_seconds");
        Context context = new Context(priorSessions, priorSeconds, earlierTitles(title), recentComments(previous));

        JsonObject current = previous == null ? new JsonObject() : previous.deepCopy();
        current.addProperty("title", title);
        current.addProperty("series", series(title));
        current.addProperty("series_order", order(title));
        if (!current.has("first_observed_at")) current.addProperty("first_observed_at", now);
        current.addProperty("last_observed_at", now);
        current.addProperty("session_count", priorSessions + 1);
        current.addProperty("total_observed_seconds", priorSeconds);
        current.addProperty("evidence", "Foreground game process observation; elapsed time is not proof of story completion.");
        BASE_SECONDS.put(title, priorSeconds);
        LAST_WRITE.put(title, now);
        write(title, current, now);
        return context;
    }

    static synchronized void observe(String title, long sessionStartedAt, long now, boolean force) {
        if (!force && now - LAST_WRITE.getOrDefault(title, 0L) < 60_000L) return;
        JsonObject current = read(title);
        if (current == null) return;
        long elapsed = Math.max(0L, (now - sessionStartedAt) / 1000L);
        long expected = BASE_SECONDS.getOrDefault(title, number(current, "total_observed_seconds")) + elapsed;
        current.addProperty("total_observed_seconds", Math.max(number(current, "total_observed_seconds"), expected));
        current.addProperty("last_observed_at", now);
        write(title, current, now);
        LAST_WRITE.put(title, now);
    }

    static synchronized void recordComment(String title, String line, long now) {
        JsonObject current = read(title);
        if (current == null) return;
        List<String> comments = recentComments(current);
        comments.removeIf(existing -> normalize(existing).equals(normalize(line)));
        comments.add(0, line);
        JsonArray saved = new JsonArray();
        comments.stream().limit(6).forEach(saved::add);
        current.add("recent_comments", saved);
        current.addProperty("last_observed_at", now);
        write(title, current, now);
    }

    private static List<String> earlierTitles(String title) {
        int currentOrder = order(title);
        if (currentOrder <= 0) return List.of();
        List<Map.Entry<Integer, String>> found = new ArrayList<>();
        for (Fact fact : MemoryService.getFactsByTag("game_progress")) {
            try {
                JsonObject value = JsonParser.parseString(fact.value()).getAsJsonObject();
                String candidate = value.get("title").getAsString();
                int candidateOrder = order(candidate);
                if (candidateOrder > 0 && candidateOrder < currentOrder
                        && number(value, "total_observed_seconds") >= 60) {
                    found.add(Map.entry(candidateOrder, candidate));
                }
            } catch (Exception ignored) { }
        }
        found.sort(Comparator.comparingInt(Map.Entry::getKey));
        return found.stream().map(Map.Entry::getValue).distinct().toList();
    }

    private static List<String> recentComments(JsonObject value) {
        if (value == null || !value.has("recent_comments") || !value.get("recent_comments").isJsonArray()) return new ArrayList<>();
        List<String> comments = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray("recent_comments")) {
            if (item.isJsonPrimitive() && !item.getAsString().isBlank()) comments.add(item.getAsString());
        }
        return comments;
    }

    private static JsonObject read(String title) {
        return MemoryService.getFact(key(title)).flatMap(fact -> {
            try { return java.util.Optional.of(JsonParser.parseString(fact.value()).getAsJsonObject()); }
            catch (Exception ignored) { return java.util.Optional.empty(); }
        }).orElse(null);
    }

    private static void write(String title, JsonObject value, long now) {
        MemoryService.addFact(new Fact(key(title), value.toString(), now,
                "user_knowledge,game_progress", "foreground_process_observation", 1));
    }

    private static String key(String title) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(title.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "game_progress_" + HexFormat.of().formatHex(digest);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static int order(String title) { return KH_ORDER.getOrDefault(title, 0); }
    private static String series(String title) { return order(title) > 0 ? "Kingdom Hearts" : title; }
    private static int integer(JsonObject value, String name) {
        try { return value == null || !value.has(name) ? 0 : value.get(name).getAsInt(); }
        catch (Exception ignored) { return 0; }
    }
    private static long number(JsonObject value, String name) {
        try { return value == null || !value.has(name) ? 0L : value.get(name).getAsLong(); }
        catch (Exception ignored) { return 0L; }
    }
    private static String normalize(String text) {
        return (text == null ? "" : text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
