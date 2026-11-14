package com.cielcompanion.service;

import com.cielcompanion.util.ProjectPaths;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Redacted, machine-readable view of what this Ciel instance can use now. */
public final class CapabilityStatusService {
    private static final Path STATUS = ProjectPaths.ciel().resolve("runtime").resolve("capability-status.json");
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ciel-capability-status"); t.setDaemon(true); return t;
    });
    private static volatile JsonObject last = new JsonObject();
    private static volatile String lastSignature = "";
    private static volatile boolean initialized;

    private CapabilityStatusService() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        refresh();
        SCHEDULER.scheduleWithFixedDelay(CapabilityStatusService::refresh, 30, 300, TimeUnit.SECONDS);
        System.out.println("[Ciel Capability Status] Snapshot service online; refresh=300s.");
    }

    public static JsonObject snapshot() { return last.deepCopy(); }

    /** Compact, redacted context suitable for model prompts and spoken status. */
    public static String contextSummary() {
        JsonObject caps = last.getAsJsonObject("capabilities");
        if (caps == null || caps.entrySet().isEmpty()) return "CAPABILITY STATUS: still initializing.";
        StringBuilder result = new StringBuilder("CAPABILITY STATUS (live, redacted): ");
        boolean first = true;
        for (var entry : caps.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            if (!first) result.append(", ");
            first = false;
            result.append(entry.getKey()).append('=').append(entry.getValue().getAsJsonObject().get("state").getAsString());
        }
        return result.toString();
    }

    public static void shutdown() { SCHEDULER.shutdownNow(); }

    private static void refresh() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", 1);
            root.addProperty("device_id", "device-" + System.getenv().getOrDefault("COMPUTERNAME", "unknown").toLowerCase().replaceAll("[^a-z0-9._-]", "-"));
            root.addProperty("observed_at", Instant.now().toString());
            root.add("capabilities", new JsonObject());
            JsonObject caps = root.getAsJsonObject("capabilities");
            put(caps, "java_runtime", "available", "JVM " + System.getProperty("java.version"));
            put(caps, "initiative_ledger", Files.isRegularFile(ProjectPaths.ciel().resolve("runtime").resolve("initiative-ledger.json")) ? "available" : "degraded", "local durable coordination state");
            put(caps, "device_profile", freshness(ProjectPaths.root().resolve(".local").resolve("device-profile.json"), 180), "hardware and power profile");
            put(caps, "lore_library", Files.isDirectory(ProjectPaths.ciel().resolve("lore").resolve("Raw")) ? "available" : "blocked", "source-grounded local lore");
            put(caps, "finance_reports", Files.isRegularFile(ProjectPaths.ciel().resolve("finance").resolve("Latest_Financial_Briefing.md")) ? "available" : "degraded", "local financial reporting pipeline");
            put(caps, "openjarvis_router", httpStatus("http://127.0.0.1:8000/model_routing_status"), "local model router and quota policy");
            put(caps, "ollama", tcpStatus(11434), "local Ollama endpoint");
            put(caps, "lm_studio", tcpStatus(1234), "local LM Studio endpoint");
            put(caps, "paired_updater", updaterStatus(), "signed paired-release updater");
            root.add("initiative_summary", summarizeLedger());
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            last = root;
            Path parent = STATUS.getParent(); Files.createDirectories(parent);
            Path tmp = STATUS.resolveSibling(STATUS.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try { Files.move(tmp, STATUS, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, STATUS, StandardCopyOption.REPLACE_EXISTING); }
            String signature = root.getAsJsonObject("capabilities").toString();
            if (!signature.equals(lastSignature)) {
                lastSignature = signature;
                System.out.println("[Ciel Capability Status] Snapshot changed: " + summarize(caps));
            }
        } catch (Exception e) {
            System.err.println("[Ciel Capability Status] Refresh failed: " + e.getClass().getSimpleName());
        }
    }

    private static String httpStatus(String endpoint) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(1200); connection.setReadTimeout(1500); connection.setRequestMethod("GET");
            int code = connection.getResponseCode(); connection.disconnect();
            return code == 200 ? "available" : "degraded";
        } catch (Exception e) { return "blocked"; }
    }

    private static String tcpStatus(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250); return "available";
        } catch (Exception e) { return "blocked"; }
    }

    private static String freshness(Path file, long maxAgeSeconds) {
        try { return System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() <= maxAgeSeconds * 1000L ? "available" : "degraded"; }
        catch (Exception e) { return "blocked"; }
    }

    private static String updaterStatus() {
        Path log = ProjectPaths.root().resolve("logs").resolve("paired-update.log");
        try {
            if (!Files.isRegularFile(log)) return "degraded";
            String text = Files.readString(log, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R");
            String tail = "";
            for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].isBlank()) { tail = lines[i]; break; }
            if (tail.contains("already-installed") || tail.contains("installed")) return "available";
            if (tail.contains("failed") || tail.contains("needs-review")) return "degraded";
        } catch (Exception ignored) {}
        return "degraded";
    }

    private static JsonObject summarizeLedger() {
        JsonObject summary = new JsonObject();
        int running = 0, failed = 0, completed = 0;
        try {
            var rows = InitiativeLedgerService.snapshot().getAsJsonArray("records");
            for (var element : rows) {
                String status = element.getAsJsonObject().has("status") ? element.getAsJsonObject().get("status").getAsString() : "";
                if ("running".equals(status)) running++; else if ("failed".equals(status)) failed++; else if ("completed".equals(status)) completed++;
            }
        } catch (Exception ignored) {}
        summary.addProperty("running", running); summary.addProperty("failed", failed); summary.addProperty("completed", completed);
        return summary;
    }

    private static void put(JsonObject parent, String name, String state, String detail) {
        JsonObject value = new JsonObject(); value.addProperty("state", state); value.addProperty("detail", detail); value.addProperty("verified_at", Instant.now().toString()); parent.add(name, value);
    }

    private static String summarize(JsonObject caps) {
        long available = caps.entrySet().stream().filter(e -> e.getValue().isJsonObject() && "available".equals(e.getValue().getAsJsonObject().get("state").getAsString())).count();
        long degraded = caps.entrySet().stream().filter(e -> e.getValue().isJsonObject() && "degraded".equals(e.getValue().getAsJsonObject().get("state").getAsString())).count();
        long blocked = caps.entrySet().stream().filter(e -> e.getValue().isJsonObject() && "blocked".equals(e.getValue().getAsJsonObject().get("state").getAsString())).count();
        return "available=" + available + ", degraded=" + degraded + ", blocked=" + blocked;
    }
}
