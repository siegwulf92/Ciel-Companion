package com.cielcompanion.service;

import com.cielcompanion.util.ProjectPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persists music observations without interrupting playback. */
public final class MusicLibraryService {
    private static final Path ROOT = ProjectPaths.ciel().resolve("media").resolve("Music");
    private static final Pattern FIELD = Pattern.compile("(?im)^([A-Z][A-Z0-9_]*):\\s*(.*)$");
    private static final long REPLAY_WINDOW_MS = 10 * 60 * 1000L;

    private MusicLibraryService() {}

    public static synchronized void record(String domText, String fallbackTitle, String sourceUrl) {
        try {
            String title = field(domText, "TRACK");
            if (title.isBlank()) title = field(domText, "MEDIA_TITLE");
            if (title.isBlank()) title = fallbackTitle == null ? "Unknown Track" : fallbackTitle;
            String artist = first(field(domText, "ARTIST"), field(domText, "CHANNEL"), "Unknown Artist");
            String album = first(field(domText, "ALBUM"), "Singles");
            String genre = first(field(domText, "GENRE"), "Unknown Genre");
            String track = title.trim();
            Path song = ROOT.resolve(segment(genre, "Unknown Genre"))
                    .resolve(segment(artist, "Unknown Artist"))
                    .resolve(segment(album, "Singles"))
                    .resolve(segment(track, "Unknown Track") + ".md");
            Files.createDirectories(song.getParent());
            long now = System.currentTimeMillis();
            String existing = Files.isRegularFile(song) ? Files.readString(song, StandardCharsets.UTF_8) : "";
            int count = number(existing, "play_count", 0);
            long lastPlayed = numberLong(existing, "last_played_epoch_ms", 0L);
            if (lastPlayed == 0L || now - lastPlayed >= REPLAY_WINDOW_MS) count++;
            StringBuilder body = new StringBuilder();
            body.append("---\n").append("type: music\n")
                    .append("title: \"").append(yaml(track)).append("\"\n")
                    .append("artist: \"").append(yaml(artist)).append("\"\n")
                    .append("album: \"").append(yaml(album)).append("\"\n")
                    .append("genre: \"").append(yaml(genre)).append("\"\n")
                    .append("play_count: ").append(count).append("\n")
                    .append("last_played_epoch_ms: ").append(now).append("\n")
                    .append("last_played: \"").append(Instant.ofEpochMilli(now)).append("\"\n")
                    .append("source_url: \"").append(yaml(sourceUrl)).append("\"\n---\n\n")
                    .append("# ").append(track).append("\n\n")
                    .append("- Artist: ").append(artist).append("\n")
                    .append("- Album: ").append(album).append("\n")
                    .append("- Genre: ").append(genre).append("\n")
                    .append("- Plays: ").append(count).append("\n");
            writeAtomic(song, body.toString());
            writeIndex(song);
        } catch (Exception e) {
            System.err.println("[Music Library] Could not record observation: " + e.getClass().getSimpleName());
        }
    }

    private static String field(String text, String name) {
        if (text == null) return "";
        Matcher matcher = FIELD.matcher(text);
        while (matcher.find()) if (name.equalsIgnoreCase(matcher.group(1))) return matcher.group(2).trim();
        return "";
    }
    private static String first(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String first(String value, String second, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : second != null && !second.isBlank() ? second.trim() : fallback;
    }
    private static String segment(String value, String fallback) {
        String clean = value == null ? "" : value.replaceAll("[<>:\"/\\\\|?*]", " ").replaceAll("\\s+", " ").trim();
        if (clean.isBlank() || clean.equalsIgnoreCase("unknown")) clean = fallback;
        return clean.length() > 100 ? clean.substring(0, 100).trim() : clean;
    }
    private static String yaml(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " "); }
    private static int number(String text, String key, int fallback) { try { return Integer.parseInt(String.valueOf(numberLong(text, key, fallback))); } catch (Exception ignored) { return fallback; } }
    private static long numberLong(String text, String key, long fallback) {
        if (text == null) return fallback;
        Matcher matcher = Pattern.compile("(?im)^" + Pattern.quote(key) + ":\\s*(\\d+)").matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : fallback;
    }
    private static void writeAtomic(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void writeIndex(Path song) throws IOException {
        Files.createDirectories(ROOT);
        Path index = ROOT.resolve("README.md");
        String relative = ROOT.relativize(song).toString().replace('\\', '/').replaceFirst("\\.md$", "");
        String existing = Files.isRegularFile(index) ? Files.readString(index, StandardCharsets.UTF_8) : "# Music library\n\nCiel tracks listening history without interrupting playback.\n\n## Tracks\n";
        String link = "- [[" + relative + "]]";
        if (!existing.contains(link)) writeAtomic(index, existing.stripTrailing() + "\n" + link + "\n");
    }
}
