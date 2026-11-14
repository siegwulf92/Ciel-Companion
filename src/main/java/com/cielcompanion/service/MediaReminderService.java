package com.cielcompanion.service;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Bounded, spoiler-safe reminders for media that was actually observed.
 * A viewing reminder only mentions a new episode after a bounded Cinemeta
 * lookup confirms the next numbered episode is listed. Failed lookups stay
 * silent rather than becoming guesses.
 */
public final class MediaReminderService {
    private static final long SHOW_INTERVAL_MS = Duration.ofDays(7).toMillis();
    private static final long READING_INTERVAL_MS = Duration.ofDays(14).toMillis();
    private static final long REPEAT_SUPPRESSION_MS = Duration.ofDays(7).toMillis();

    private MediaReminderService() {}

    public static Optional<String> getDueReminder() {
        long now = System.currentTimeMillis();
        Optional<Reminder> candidate = dueReading(now).or(() -> dueViewing(now));
        if (candidate.isEmpty()) return Optional.empty();
        Reminder reminder = candidate.get();
        markReminded(reminder.fact(), reminder.json(), now);
        return Optional.of(reminder.text());
    }

    private static Optional<Reminder> dueReading(long now) {
        List<Fact> facts = MemoryService.getFactsByTag("reading_context");
        return facts.stream().map(f -> parse(f, false, now))
                .filter(java.util.Objects::nonNull)
                .filter(r -> r.ageMs() >= READING_INTERVAL_MS && r.sinceReminderMs() >= REPEAT_SUPPRESSION_MS)
                .min(Comparator.comparingLong(Reminder::ageMs));
    }

    private static Optional<Reminder> dueViewing(long now) {
        List<Fact> facts = MemoryService.getFactsByTag("viewing_progress");
        return facts.stream().map(f -> parse(f, true, now))
                .filter(java.util.Objects::nonNull)
                .filter(r -> r.ageMs() >= SHOW_INTERVAL_MS && r.sinceReminderMs() >= REPEAT_SUPPRESSION_MS)
                .filter(r -> !r.nearEnd())
                .map(MediaReminderService::confirmViewingFollowup)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparingLong(Reminder::ageMs));
    }

    private static Reminder parse(Fact fact, boolean viewing, long now) {
        try {
            JsonObject json = JsonParser.parseString(fact.value()).getAsJsonObject();
            long observed = json.has("last_observed_at") ? json.get("last_observed_at").getAsLong() : fact.createdAtMs();
            long reminded = json.has("last_reminded_at") ? json.get("last_reminded_at").getAsLong() : 0L;
            String title = json.has("series") ? json.get("series").getAsString() : json.has("content_title")
                    ? json.get("content_title").getAsString() : "that title";
            String episode = json.has("episode") ? json.get("episode").getAsString() : "";
            String seasonNumber = json.has("season") ? json.get("season").getAsString() : "";
            String episodeNumber = json.has("episode_number") ? json.get("episode_number").getAsString() : "";
            String chapter = json.has("chapter") ? json.get("chapter").getAsString() : "";
            boolean nearEnd = json.has("near_end_observed") && json.get("near_end_observed").getAsBoolean();
            long age = Math.max(0, now - observed);
            long sinceReminder = reminded <= 0 ? Long.MAX_VALUE : Math.max(0, now - reminded);
            long days = Math.max(1, age / Duration.ofDays(1).toMillis());
            String text;
            if (viewing) {
                text = String.format(Locale.ROOT,
                        "It has been %d days since you returned to %s%s.",
                        days, title, episode.isBlank() ? "" : " (last observed " + episode + ")");
            } else {
                text = String.format(Locale.ROOT,
                        "It has been %d days since you last read %s%s. Shall I keep an eye out for the next chapter or volume?",
                        days, title, chapter.isBlank() ? "" : " (last observed " + chapter + ")");
            }
            return new Reminder(fact, json, age, sinceReminder, nearEnd, text, title, seasonNumber, episodeNumber, viewing);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Reminder confirmViewingFollowup(Reminder reminder) {
        if (!reminder.viewing()) return reminder;
        Followup followup = queryCinemeta(reminder.title(), reminder.season(), reminder.episodeNumber());
        if (followup == null || "UNKNOWN".equals(followup.status())) return null;
        String suffix;
        if ("CONFIRMED".equals(followup.status())) {
            String listed = followup.title().isBlank() ? "the next episode" : "the next episode, “" + followup.title() + "”";
            suffix = " I checked Cinemeta, and it lists " + listed + " (S" + followup.season() + "E" + followup.episode() + ").";
        } else {
            suffix = " I checked Cinemeta, and it does not list a next episode yet.";
        }
        return new Reminder(reminder.fact(), reminder.json(), reminder.ageMs(), reminder.sinceReminderMs(),
                reminder.nearEnd(), reminder.text() + suffix, reminder.title(), reminder.season(), reminder.episodeNumber(), true);
    }

    private static Followup queryCinemeta(String series, String season, String episode) {
        if (series == null || series.isBlank() || !season.matches("\\d+") || !episode.matches("\\d+")) return null;
        HttpURLConnection connection = null;
        try {
            String query = "series=" + URLEncoder.encode(series, StandardCharsets.UTF_8)
                    + "&season=" + season + "&episode=" + episode;
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:8000/media_followup?" + query).toURL().openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                JsonObject body = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                return new Followup(body.has("status") ? body.get("status").getAsString() : "UNKNOWN",
                        body.has("season") ? body.get("season").getAsString() : "",
                        body.has("episode") ? body.get("episode").getAsString() : "",
                        body.has("title") ? body.get("title").getAsString() : "");
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void markReminded(Fact fact, JsonObject json, long now) {
        json.addProperty("last_reminded_at", now);
        MemoryService.addFact(new Fact(fact.key(), json.toString(), now, fact.tags(), fact.source(), fact.version()));
    }

    private record Reminder(Fact fact, JsonObject json, long ageMs, long sinceReminderMs,
                            boolean nearEnd, String text, String title, String season,
                            String episodeNumber, boolean viewing) {}
    private record Followup(String status, String season, String episode, String title) {}
}
