package com.cielcompanion.service;

import com.cielcompanion.memory.DatabaseManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.UUID;

/**
 * Conservative local reservation ledger for Breeze's 400-credit daily grant.
 * Breeze charges one credit per ten characters, with CJK characters weighted
 * as two.  A reservation is kept when a request times out because the server
 * may have accepted it; this avoids accidentally doubling usage on retries.
 * The file is intentionally replaceable by the paired memory-sync service so
 * the two devices can merge reservations later without putting the API key in
 * shared memory.
 */
public final class BreezeUsageTracker {
    private static final Object LOCK = new Object();
    private static final Path FILE = Path.of(".local", "breeze_usage.properties");
    private static final String PREFIX = "breeze_tts_usage:";

    private BreezeUsageTracker() {}

    public static boolean reserve(String text) {
        long credits = creditsFor(text);
        synchronized (LOCK) {
            Boolean shared = reserveShared(credits, text);
            if (shared != null) return shared;
            System.err.println("[Breeze TTS] Shared usage ledger unavailable; using device-local safety ledger.");
            Properties p = read();
            String today = LocalDate.now().toString();
            if (!today.equals(p.getProperty("day"))) {
                p.clear();
                p.setProperty("day", today);
                p.setProperty("credits", "0");
            }
            long used = parseLong(p.getProperty("credits"), 0L);
            long limit = Math.max(1L, Settings.getBreezeDailyCreditLimit());
            if (credits > limit || used > limit - credits) {
                System.err.println("[Breeze TTS] Daily credit guard declined request (used="
                        + used + ", requested=" + credits + ", limit=" + limit + ").");
                return false;
            }
            p.setProperty("credits", Long.toString(used + credits));
            return write(p);
        }
    }

    /** Reserve in the facts database replicated by the authenticated memory bridge. */
    private static Boolean reserveShared(long credits, String text) {
        String day = LocalDate.now().toString();
        try (Connection db = DatabaseManager.getConnection()) {
            String device = deviceId(db);
            seedLegacy(db, device, day);
            long used = usageCredits(db, day);
            long limit = Math.max(1L, Settings.getBreezeDailyCreditLimit());
            if (credits > limit || used > limit - credits) return false;
            JsonObject value = new JsonObject();
            value.addProperty("status", "reserved"); value.addProperty("credits", credits);
            value.addProperty("characters", text == null ? 0 : text.length()); value.addProperty("day", day);
            insert(db, PREFIX + day + ":" + device + ":" + UUID.randomUUID(), value);
            return true;
        } catch (Exception unavailable) { return null; }
    }

    private static String deviceId(Connection db) throws Exception {
        try (PreparedStatement query = db.prepareStatement("SELECT value FROM ciel_sync_meta WHERE key='device'"); ResultSet rows = query.executeQuery()) {
            if (rows.next() && rows.getString(1).matches("[0-9a-f]{32}")) return rows.getString(1);
        }
        throw new IOException("memory bridge device identity is unavailable");
    }

    private static void seedLegacy(Connection db, String device, String day) throws Exception {
        String key = PREFIX + day + ":" + device + ":legacy";
        try (PreparedStatement query = db.prepareStatement("SELECT 1 FROM facts WHERE key=?")) {
            query.setString(1, key); try (ResultSet rows = query.executeQuery()) { if (rows.next()) return; }
        }
        Properties old = read();
        JsonObject value = new JsonObject(); value.addProperty("status", "legacy_baseline");
        value.addProperty("credits", day.equals(old.getProperty("day")) ? parseLong(old.getProperty("credits"), 0L) : 0L);
        value.addProperty("day", day); insert(db, key, value);
    }

    private static void insert(Connection db, String key, JsonObject value) throws Exception {
        try (PreparedStatement statement = db.prepareStatement("INSERT OR IGNORE INTO facts(key,value,created_at_ms,tags,source,version) VALUES(?,?,?,?,?,1)")) {
            statement.setString(1, key); statement.setString(2, value.toString()); statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, "[\"breeze_tts_usage\"]"); statement.setString(5, "breeze_speech_tracker"); statement.executeUpdate();
        }
    }

    private static long usageCredits(Connection db, String day) throws Exception {
        long total = 0L;
        try (PreparedStatement query = db.prepareStatement("SELECT value FROM facts WHERE key LIKE ?")) {
            query.setString(1, PREFIX + day + ":%"); try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) { JsonObject value = JsonParser.parseString(rows.getString(1)).getAsJsonObject(); if (value.has("credits")) total += Math.max(0L, value.get("credits").getAsLong()); }
            }
        }
        return total;
    }

    private static long creditsFor(String text) {
        if (text == null || text.isEmpty()) return 1L;
        long weightedCharacters = text.codePoints().mapToLong(cp -> isCjk(cp) ? 2L : 1L).sum();
        return Math.max(1L, (weightedCharacters + 9L) / 10L);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3000 && cp <= 0x30ff) || (cp >= 0x3400 && cp <= 0x9fff)
                || (cp >= 0xf900 && cp <= 0xfaff);
    }

    private static Properties read() {
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(FILE)) {
                try (InputStream in = Files.newInputStream(FILE)) { p.load(in); }
            }
        } catch (IOException e) {
            System.err.println("[Breeze TTS] Usage ledger read failed; refusing unsafe retry: " + e.getMessage());
        }
        return p;
    }

    private static boolean write(Properties p) {
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "Breeze daily credit reservations"); }
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException atomicFailure) {
            try {
                Files.createDirectories(FILE.getParent());
                try (OutputStream out = Files.newOutputStream(FILE)) { p.store(out, "Breeze daily credit reservations"); }
                return true;
            } catch (IOException e) {
                System.err.println("[Breeze TTS] Usage ledger write failed; request declined: " + e.getMessage());
                return false;
            }
        }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }
}
