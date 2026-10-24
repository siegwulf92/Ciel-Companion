package com.cielcompanion.service;

import com.cielcompanion.memory.DatabaseManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.Properties;
import java.util.UUID;

/** Shared, per-request Azure speech ledger, replicated by Ciel's authenticated memory bridge. */
public final class AzureUsageTracker {
    private static final String PREFIX = "azure_tts_usage:";
    private static volatile long monthlyLimitSeconds = 18_000;
    // The F0 Neural TTS allowance is 500,000 characters; leave room for estimates and other clients.
    private static volatile long monthlyLimitCharacters = 450_000;

    private AzureUsageTracker() {}

    public static void setLimit(long limitSeconds) {
        monthlyLimitSeconds = Math.max(0, limitSeconds);
    }

    public static void setCharacterLimit(long limitCharacters) {
        monthlyLimitCharacters = Math.max(0, limitCharacters);
    }

    /** Reserve before contacting Azure, so a failed ledger write cannot cause untracked usage. */
    public static synchronized String reserve(long estimatedSeconds, String voice, int spokenChars, int inputChars) {
        String month = YearMonth.now().toString();
        try (Connection db = DatabaseManager.getConnection()) {
            String device = deviceId(db);
            seedLegacy(db, device, month);
            long used = usageSeconds(db, month);
            long usedCharacters = usageCharacters(db, month);
            long requested = Math.max(1, estimatedSeconds);
            if (used + requested > monthlyLimitSeconds
                    || usedCharacters + Math.max(0, inputChars) > monthlyLimitCharacters) {
                System.out.println("[Azure Tracker] Shared monthly safety limit reached: " + used + "/"
                        + monthlyLimitSeconds + "s; estimated billed characters " + usedCharacters + "/"
                        + monthlyLimitCharacters + "; requested " + requested + "s, " + inputChars + " characters.");
                return null;
            }
            String id = PREFIX + month + ":" + device + ":" + UUID.randomUUID();
            JsonObject value = entry("reserved", requested, voice, spokenChars, inputChars);
            insert(db, id, value);
            System.out.println("[Azure Tracker] Reserved request; shared known use " + (used + requested)
                    + "/" + monthlyLimitSeconds + "s; estimated billed characters "
                    + (usedCharacters + inputChars) + "/" + monthlyLimitCharacters + ".");
            return id;
        } catch (Exception e) {
            System.err.println("[Azure Tracker] Shared ledger unavailable; Azure request deferred: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    public static synchronized void finish(String id, long actualSeconds, boolean completed) {
        if (id == null) return;
        try (Connection db = DatabaseManager.getConnection();
             PreparedStatement query = db.prepareStatement("SELECT value FROM facts WHERE key=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Azure reservation missing");
                JsonObject value = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                value.addProperty("status", completed ? "completed" : "unconfirmed");
                if (completed) value.addProperty("seconds", Math.max(1, actualSeconds));
                try (PreparedStatement update = db.prepareStatement(
                        "UPDATE facts SET value=?, created_at_ms=MAX(created_at_ms+1, ?), version=version+1 WHERE key=?")) {
                    update.setString(1, value.toString());
                    update.setLong(2, System.currentTimeMillis());
                    update.setString(3, id);
                    update.executeUpdate();
                }
            }
            System.out.println("[Azure Tracker] Request " + (completed ? "completed" : "unconfirmed")
                    + "; shared known use " + usageSeconds(db, YearMonth.now().toString())
                    + "/" + monthlyLimitSeconds + "s; estimated billed characters "
                    + usageCharacters(db, YearMonth.now().toString()) + "/" + monthlyLimitCharacters + ".");
        } catch (Exception e) {
            // The reservation remains durable and still counts against the cap.
            System.err.println("[Azure Tracker] Could not finalize reservation: " + e.getMessage());
        }
    }

    public static synchronized long getSharedKnownUsageSeconds() throws SQLException {
        String month = YearMonth.now().toString();
        try (Connection db = DatabaseManager.getConnection()) {
            try { seedLegacy(db, deviceId(db), month); }
            catch (Exception e) { throw new SQLException("Cannot seed legacy Azure usage", e); }
            return usageSeconds(db, month);
        }
    }

    private static JsonObject entry(String status, long seconds, String voice, int spokenChars, int inputChars) {
        JsonObject value = new JsonObject();
        value.addProperty("status", status);
        value.addProperty("seconds", seconds);
        value.addProperty("spoken_characters", Math.max(0, spokenChars));
        value.addProperty("estimated_billable_characters", Math.max(0, inputChars));
        value.addProperty("voice", voice);
        return value;
    }

    private static String deviceId(Connection db) throws SQLException {
        try (PreparedStatement query = db.prepareStatement("SELECT value FROM ciel_sync_meta WHERE key='device'");
             ResultSet rows = query.executeQuery()) {
            if (rows.next() && rows.getString(1).matches("[0-9a-f]{32}")) return rows.getString(1);
        }
        throw new SQLException("memory bridge device identity is unavailable");
    }

    private static void seedLegacy(Connection db, String device, String month) throws Exception {
        String key = PREFIX + month + ":" + device + ":legacy";
        try (PreparedStatement query = db.prepareStatement("SELECT 1 FROM facts WHERE key=?")) {
            query.setString(1, key);
            try (ResultSet rows = query.executeQuery()) { if (rows.next()) return; }
        }
        Properties old = new Properties();
        File file = new File("azure_usage.properties");
        if (file.isFile()) try (FileInputStream stream = new FileInputStream(file)) { old.load(stream); }
        long seconds = 0;
        if (month.equals(old.getProperty("current_month"))) {
            try { seconds = Math.max(0, Long.parseLong(old.getProperty("usage_seconds", "0"))); }
            catch (NumberFormatException ignored) { /* Cannot infer corrupt legacy data. */ }
        }
        insert(db, key, entry("legacy_baseline", seconds, "unknown", 0, 0));
    }

    private static void insert(Connection db, String key, JsonObject value) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "INSERT OR IGNORE INTO facts(key,value,created_at_ms,tags,source,version) VALUES(?,?,?,?,?,1)")) {
            statement.setString(1, key);
            statement.setString(2, value.toString());
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, "[\"azure_tts_usage\"]");
            statement.setString(5, "azure_speech_tracker");
            statement.executeUpdate();
        }
    }

    private static long usageSeconds(Connection db, String month) throws SQLException {
        long total = 0;
        try (PreparedStatement query = db.prepareStatement("SELECT value FROM facts WHERE key LIKE ?")) {
            query.setString(1, PREFIX + month + ":%");
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try { total += Math.max(0, JsonParser.parseString(rows.getString(1)).getAsJsonObject()
                            .get("seconds").getAsLong()); }
                    catch (RuntimeException badEntry) { throw new SQLException("Invalid shared Azure usage entry", badEntry); }
                }
            }
        }
        return total;
    }

    private static long usageCharacters(Connection db, String month) throws SQLException {
        long total = 0;
        try (PreparedStatement query = db.prepareStatement("SELECT value FROM facts WHERE key LIKE ?")) {
            query.setString(1, PREFIX + month + ":%");
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try {
                        JsonObject entry = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                        if (entry.has("estimated_billable_characters"))
                            total += Math.max(0, entry.get("estimated_billable_characters").getAsLong());
                    } catch (RuntimeException badEntry) {
                        throw new SQLException("Invalid shared Azure usage entry", badEntry);
                    }
                }
            }
        }
        return total;
    }
}
