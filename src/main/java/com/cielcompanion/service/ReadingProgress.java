package com.cielcompanion.service;

import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Persists observed manga/light-novel page context without inventing reading completion. */
public final class ReadingProgress {
    private static final long WRITE_INTERVAL_MS = 15 * 60_000L;

    private ReadingProgress() {}

    public static synchronized void observe(String dom, long now) {
        if (dom == null || !"READING".equalsIgnoreCase(MediaObservation.field(dom, "MEDIA_KIND"))) return;
        String title = firstNonBlank(MediaObservation.field(dom, "CONTENT_TITLE"),
                MediaObservation.field(dom, "MEDIA_TITLE"), MediaObservation.field(dom, "SERIES"));
        if (title.isBlank()) return;
        String series = firstNonBlank(MediaObservation.field(dom, "SERIES"), title);
        String chapter = MediaObservation.field(dom, "CHAPTER");
        String source = MediaObservation.field(dom, "SOURCE_SITE");
        String key = key(series, chapter, source);
        try {
            JsonObject value = MemoryService.getFact(key)
                    .map(f -> JsonParser.parseString(f.value()).getAsJsonObject())
                    .orElse(new JsonObject());
            long last = value.has("last_observed_at") ? value.get("last_observed_at").getAsLong() : 0L;
            if (now - last < WRITE_INTERVAL_MS) return;
            value.addProperty("content_title", title);
            value.addProperty("series", series);
            value.addProperty("chapter", chapter);
            value.addProperty("source_site", source);
            if (!value.has("first_observed_at")) value.addProperty("first_observed_at", now);
            value.addProperty("last_observed_at", now);
            value.addProperty("evidence", "Observed active reading page; does not prove completion.");
            MemoryService.addFact(new Fact(key, value.toString(), now, "reading_context", "media_monitor", 1));
            System.out.println("[Reading Progress] Saved observed page '" + series + "'" +
                    (chapter.isBlank() ? "" : " / " + chapter) + ".");
        } catch (Exception error) {
            System.err.println("[Reading Progress] Save deferred: " + error.getClass().getSimpleName());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String key(String series, String chapter, String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (series.trim().toLowerCase(Locale.ROOT) + "|" + chapter.trim().toLowerCase(Locale.ROOT)
                            + "|" + source.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return "reading_context_" + HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
