package com.cielcompanion.service;

import com.cielcompanion.util.ProjectPaths;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Small durable coordination ledger for Ciel's background initiatives.
 *
 * This is deliberately local and append/update based: it coordinates work on
 * one device, survives a restart, and records enough state for a future paired
 * bridge to exchange redacted status without sharing prompts or credentials.
 */
public final class InitiativeLedgerService {
    private static final Path LEDGER = ProjectPaths.ciel().resolve("runtime").resolve("initiative-ledger.json");
    private static final int MAX_RECORDS = 256;
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final GsonBuilder JSON = new GsonBuilder().setPrettyPrinting();
    private static JsonArray records = new JsonArray();
    private static volatile boolean initialized;

    private InitiativeLedgerService() {}

    public static void initialize() {
        LOCK.lock();
        try {
            if (initialized) return;
            Files.createDirectories(LEDGER.getParent());
            records = readRecords();
            initialized = true;
            persistLocked();
            System.out.println("[Ciel Initiative Ledger] Ready; records=" + records.size());
        } catch (Exception e) {
            System.err.println("[Ciel Initiative Ledger] Initialization deferred: " + e.getClass().getSimpleName());
        } finally {
            LOCK.unlock();
        }
    }

    /** Claims a deduplicated initiative. Returns null when another attempt is active. */
    public static String claim(String key, String category, String evidence) {
        if (key == null || key.isBlank()) return null;
        ensureInitialized();
        LOCK.lock();
        try {
            long now = System.currentTimeMillis();
            for (JsonObject row : objects()) {
                if (key.equals(row.get("key").getAsString())) {
                    String status = value(row, "status", "unknown");
                    long next = longValue(row, "next_attempt_at", 0L);
                    if (("running".equals(status) || "queued".equals(status)) && next <= now) return null;
                    if ("running".equals(status) || "queued".equals(status)) return null;
                }
            }
            JsonObject row = new JsonObject();
            String id = UUID.randomUUID().toString();
            row.addProperty("id", id);
            row.addProperty("key", key);
            row.addProperty("category", safe(category, "general"));
            row.addProperty("status", "running");
            row.addProperty("owner_device", deviceId());
            row.addProperty("created_at", Instant.ofEpochMilli(now).toString());
            row.addProperty("updated_at", Instant.ofEpochMilli(now).toString());
            row.addProperty("next_attempt_at", now);
            row.addProperty("attempts", 1);
            row.addProperty("evidence", safe(evidence, "").substring(0, Math.min(1200, safe(evidence, "").length())));
            records.add(row);
            trimLocked();
            persistLocked();
            return id;
        } catch (Exception e) {
            System.err.println("[Ciel Initiative Ledger] Claim failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            LOCK.unlock();
        }
    }

    public static void complete(String id, String result) {
        update(id, "completed", result, 0L);
    }

    public static void fail(String id, String reason, long nextAttemptAt) {
        update(id, "failed", reason, nextAttemptAt);
    }

    public static JsonObject snapshot() {
        ensureInitialized();
        LOCK.lock();
        try {
            JsonObject result = new JsonObject();
            result.addProperty("schema_version", 1);
            result.addProperty("device_id", deviceId());
            result.addProperty("observed_at", Instant.now().toString());
            JsonArray copy = new JsonArray();
            for (JsonObject row : objects()) copy.add(row.deepCopy());
            result.add("records", copy);
            return result;
        } finally {
            LOCK.unlock();
        }
    }

    public static void flush() {
        if (!initialized) return;
        LOCK.lock();
        try { persistLocked(); } catch (Exception e) {
            System.err.println("[Ciel Initiative Ledger] Flush failed: " + e.getClass().getSimpleName());
        } finally { LOCK.unlock(); }
    }

    private static void update(String id, String status, String detail, long nextAttemptAt) {
        if (id == null || id.isBlank()) return;
        ensureInitialized();
        LOCK.lock();
        try {
            for (JsonObject row : objects()) {
                if (!id.equals(value(row, "id", ""))) continue;
                long now = System.currentTimeMillis();
                row.addProperty("status", status);
                row.addProperty("updated_at", Instant.ofEpochMilli(now).toString());
                row.addProperty("next_attempt_at", nextAttemptAt);
                if ("completed".equals(status)) row.addProperty("result", safe(detail, "completed").substring(0, Math.min(1600, safe(detail, "completed").length())));
                else row.addProperty("failure", safe(detail, "unknown failure").substring(0, Math.min(1600, safe(detail, "unknown failure").length())));
                persistLocked();
                return;
            }
        } catch (Exception e) {
            System.err.println("[Ciel Initiative Ledger] Update failed: " + e.getClass().getSimpleName());
        } finally { LOCK.unlock(); }
    }

    private static void ensureInitialized() { if (!initialized) initialize(); }

    private static JsonArray readRecords() {
        if (!Files.isRegularFile(LEDGER)) return new JsonArray();
        try {
            var root = JsonParser.parseString(Files.readString(LEDGER, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().has("records")) return root.getAsJsonObject().getAsJsonArray("records");
            if (root.isJsonArray()) return root.getAsJsonArray();
        } catch (Exception e) {
            System.err.println("[Ciel Initiative Ledger] Existing ledger unreadable; preserving it and starting clean.");
        }
        return new JsonArray();
    }

    private static List<JsonObject> objects() {
        List<JsonObject> out = new ArrayList<>();
        records.forEach(element -> { if (element.isJsonObject()) out.add(element.getAsJsonObject()); });
        return out;
    }

    private static void trimLocked() {
        while (records.size() > MAX_RECORDS) {
            int oldest = 0;
            long oldestTime = Long.MAX_VALUE;
            for (int i = 0; i < records.size(); i++) {
                JsonObject row = records.get(i).getAsJsonObject();
                long time = longValue(row, "next_attempt_at", 0L);
                if (time < oldestTime && !"running".equals(value(row, "status", ""))) { oldestTime = time; oldest = i; }
            }
            records.remove(oldest);
        }
    }

    private static void persistLocked() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("device_id", deviceId());
        root.addProperty("updated_at", Instant.now().toString());
        root.add("records", records);
        Path tmp = LEDGER.resolveSibling(LEDGER.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.create().toJson(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(tmp, LEDGER, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, LEDGER, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String deviceId() {
        String name = System.getenv().getOrDefault("COMPUTERNAME", "unknown");
        return "device-" + name.toLowerCase().replaceAll("[^a-z0-9._-]", "-");
    }

    private static String safe(String value, String fallback) { return value == null ? fallback : value.replaceAll("(?i)(api[_-]?key|token|secret|password|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]"); }
    private static String value(JsonObject row, String key, String fallback) { return row.has(key) && row.get(key).isJsonPrimitive() ? row.get(key).getAsString() : fallback; }
    private static long longValue(JsonObject row, String key, long fallback) { try { return row.has(key) ? row.get(key).getAsLong() : fallback; } catch (Exception e) { return fallback; } }
}
