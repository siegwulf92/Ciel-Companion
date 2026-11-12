package com.cielcompanion.service;

import com.cielcompanion.util.ProjectPaths;
import com.cielcompanion.util.WindowlessCommand;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Durable session/boot bookkeeping for Ciel's recovery path.
 *
 * The ledger is intentionally kept under .local so it never becomes part of
 * a paired source release. A heartbeat means a missing clean-shutdown record
 * is evidence of an interrupted process, not proof of a particular cause.
 * Windows event IDs are used only as corroborating evidence.
 */
public final class SessionRecoveryService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long HEARTBEAT_SECONDS = 60L;
    private static final long DAILY_REPORT_SECONDS = 24L * 60L * 60L;
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s,;]+")
            ;

    private static final Object LOCK = new Object();
    private static Path statePath;
    private static Path reportDirectory;
    private static String sessionId;
    private static ScheduledExecutorService heartbeat;
    private static boolean started;

    private SessionRecoveryService() {}

    public static void startup(Path root) {
        synchronized (LOCK) {
            if (started) return;
            statePath = root.resolve(".local/ciel-session/session-state.json");
            reportDirectory = root.resolve("Ciel/diagnostics/recovery");
            try {
                Files.createDirectories(statePath.getParent());
                Files.createDirectories(reportDirectory);
            } catch (IOException e) {
                System.err.println("Ciel Warning: Session recovery storage unavailable: " + e.getMessage());
                return;
            }

            JsonObject previous = readState();
            String previousId = text(previous, "session_id");
            String previousTerminal = text(previous, "terminal_state");
            String previousIntent = text(previous, "shutdown_intent");
            String previousStarted = text(previous, "started_at");
            String previousHeartbeat = text(previous, "last_heartbeat");
            String systemEvents = collectSystemEvents();
            String bootObservation = collectBootObservation();
            String classification = (previousId == null || previousId.isBlank())
                    && (previousTerminal == null || previousTerminal.isBlank())
                    && (previousIntent == null || previousIntent.isBlank())
                    ? "initial_startup"
                    : classifyTermination(previousTerminal, previousIntent, systemEvents);
            boolean interrupted = previousId != null && !previousId.isBlank()
                    && !"clean_shutdown".equalsIgnoreCase(previousTerminal)
                    && !"planned_windows_shutdown".equalsIgnoreCase(classification);
            boolean planned = "planned_windows_shutdown".equalsIgnoreCase(classification)
                    || previousIntent.toLowerCase(Locale.ROOT).startsWith("planned_")
                    || previousIntent.toLowerCase(Locale.ROOT).startsWith("ciel_");

            sessionId = UUID.randomUUID().toString();
            JsonObject current = new JsonObject();
            current.addProperty("schema_version", 1);
            current.addProperty("session_id", sessionId);
            current.addProperty("started_at", Instant.now().toString());
            current.addProperty("last_heartbeat", Instant.now().toString());
            current.addProperty("terminal_state", "running");
            current.addProperty("shutdown_intent", "none");
            current.addProperty("system_boot_observation", bootObservation);
            current.addProperty("previous_session_id", previousId == null ? "" : previousId);
            current.addProperty("previous_classification", classification);
            current.addProperty("last_reported_incident_id", text(previous, "last_reported_incident_id"));
            current.addProperty("last_health_report_at", text(previous, "last_health_report_at"));

            String incidentId = previousId + ":" + classification;
            boolean newIncident = interrupted || planned;
            boolean dailyDue = secondsSince(text(previous, "last_health_report_at")) >= DAILY_REPORT_SECONDS;
            boolean alreadyReported = incidentId.equals(text(previous, "last_reported_incident_id"));
            if ((newIncident && !alreadyReported) || dailyDue) {
                Path report = writeRecoveryReport(root, classification, previousStarted, previousHeartbeat,
                        previousId, previousIntent, systemEvents, bootObservation, interrupted);
                if (report != null) {
                    current.addProperty("last_reported_incident_id", incidentId);
                    current.addProperty("last_health_report_at", Instant.now().toString());
                    System.out.println("Ciel Recovery: report=" + report);
                }
            }
            writeState(current);
            started = true;
            heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Ciel-Session-Heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            heartbeat.scheduleWithFixedDelay(SessionRecoveryService::heartbeat, HEARTBEAT_SECONDS,
                    HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            System.out.println("Ciel Recovery: session ledger active; classification=" + classification
                    + ", previous_session=" + (previousId == null ? "none" : previousId));
        }
    }

    public static void requestShutdown(String reason) {
        synchronized (LOCK) {
            if (!started) return;
            JsonObject state = readState();
            state.addProperty("shutdown_intent", reason == null || reason.isBlank() ? "ciel_requested" : reason);
            state.addProperty("shutdown_requested_at", Instant.now().toString());
            state.addProperty("last_heartbeat", Instant.now().toString());
            writeState(state);
        }
    }

    public static void markGracefulShutdown(String reason) {
        synchronized (LOCK) {
            if (!started) return;
            JsonObject state = readState();
            state.addProperty("terminal_state", "clean_shutdown");
            state.addProperty("shutdown_intent", reason == null || reason.isBlank() ? "jvm_shutdown" : reason);
            state.addProperty("shutdown_completed_at", Instant.now().toString());
            state.addProperty("last_heartbeat", Instant.now().toString());
            writeState(state);
            started = false;
            if (heartbeat != null) heartbeat.shutdownNow();
            heartbeat = null;
        }
    }

    /** Used by the local verification harness and kept deterministic for tests. */
    public static String classifyTermination(String terminalState, String intent, String eventEvidence) {
        String state = terminalState == null ? "" : terminalState.toLowerCase(Locale.ROOT);
        String requested = intent == null ? "" : intent.toLowerCase(Locale.ROOT);
        String events = eventEvidence == null ? "" : eventEvidence;
        if (containsEvent(events, 6008) || containsEvent(events, 41)) {
            return "unexpected_system_termination";
        }
        if (containsEvent(events, 1074) || containsEvent(events, 6006)) {
            return "planned_windows_shutdown";
        }
        if (requested.startsWith("ciel_") || requested.startsWith("planned_")) {
            return "planned_ciel_shutdown";
        }
        if ("clean_shutdown".equals(state)) return "clean_shutdown";
        if ("running".equals(state) || state.isBlank()) return "interrupted_session";
        return "unknown_interruption";
    }

    private static boolean containsEvent(String evidence, int id) {
        return evidence.contains("\"Id\":" + id)
                || evidence.contains("\"Id\": " + id)
                || evidence.contains("\"Id\" : " + id)
                || evidence.contains("EventID=" + id)
                || evidence.contains("EventID=" + id + " ");
    }

    private static void heartbeat() {
        synchronized (LOCK) {
            if (!started) return;
            JsonObject state = readState();
            if (secondsSince(text(state, "last_health_report_at")) >= DAILY_REPORT_SECONDS) {
                Path report = writeRecoveryReport(rootPath(), "daily_health_snapshot",
                        text(state, "started_at"), text(state, "last_heartbeat"),
                        text(state, "session_id"), text(state, "shutdown_intent"),
                        collectSystemEvents(), text(state, "system_boot_observation"), false);
                if (report != null) {
                    state.addProperty("last_health_report_at", Instant.now().toString());
                    System.out.println("Ciel Recovery: daily health report=" + report);
                }
            }
            state.addProperty("last_heartbeat", Instant.now().toString());
            writeState(state);
        }
    }

    private static Path rootPath() {
        // reportDirectory is root/Ciel/diagnostics/recovery.
        return reportDirectory.getParent().getParent().getParent();
    }

    private static JsonObject readState() {
        try {
            if (statePath != null && Files.isRegularFile(statePath)) {
                return JsonParser.parseString(Files.readString(statePath, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Session state unreadable; starting a new ledger: " + e.getMessage());
        }
        return new JsonObject();
    }

    private static void writeState(JsonObject state) {
        if (statePath == null) return;
        Path staging = statePath.resolveSibling(".session-state-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(staging, GSON.toJson(state), StandardCharsets.UTF_8);
            Files.move(staging, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.move(staging, statePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                System.err.println("Ciel Warning: Session state write failed: " + e.getMessage());
            }
        } finally {
            try { Files.deleteIfExists(staging); } catch (IOException ignored) {}
        }
    }

    private static Path writeRecoveryReport(Path root, String classification, String previousStarted,
                                            String previousHeartbeat, String previousId, String previousIntent,
                                            String systemEvents, String bootObservation, boolean interrupted) {
        try {
            String timestamp = Instant.now().toString().replace(":", "").replace(".", "");
            Path newestLog = newestFile(root.resolve("logs"), "ciel_companion_*.log");
            Path updaterLog = newestFile(root.resolve("logs"), "paired-update*.log");
            StringBuilder report = new StringBuilder();
            report.append("# Ciel Session Recovery Report\n\n");
            report.append("- Generated: ").append(Instant.now()).append("\n");
            report.append("- Classification: `").append(classification).append("`\n");
            report.append("- Previous session: `").append(previousId == null ? "unknown" : previousId).append("`\n");
            report.append("- Previous start: ").append(valueOrUnknown(previousStarted)).append("\n");
            report.append("- Previous heartbeat: ").append(valueOrUnknown(previousHeartbeat)).append("\n");
            report.append("- Previous shutdown intent: ").append(valueOrUnknown(previousIntent)).append("\n");
            report.append("- Interruption suspected: `").append(interrupted).append("`\n");
            report.append("- Windows boot observation: ").append(valueOrUnknown(bootObservation)).append("\n\n");
            report.append("## Evidence\n\n");
            report.append("- Latest Java log: ").append(pathInfo(newestLog)).append("\n");
            report.append("- Latest paired-update log: ").append(pathInfo(updaterLog)).append("\n");
            report.append("- Recent Windows System events: `").append(sanitize(systemEvents)).append("`\n\n");
            report.append("## Current log findings\n\n");
            String javaFindings = logFindings(newestLog);
            report.append("### Java\n\n").append(javaFindings).append("\n\n");
            Path routerLog = newestMatchingLog(root.resolve("logs"), "router*");
            String routerFindings = logFindings(routerLog);
            report.append("### Python/router\n\n").append(routerFindings).append("\n\n");
            report.append("## Interpretation\n\n");
            if ("unexpected_system_termination".equals(classification)) {
                report.append("Windows recorded an unexpected termination or power-loss signal. Ciel will preserve this report and avoid repeating it until a new incident is observed.\n");
            } else if ("planned_windows_shutdown".equals(classification)) {
                report.append("Windows recorded a planned shutdown/restart. This is not treated as a crash.\n");
            } else if (interrupted) {
                report.append("The previous Ciel heartbeat stopped without a clean shutdown marker. The cause is unknown until the logs or Windows events provide more evidence.\n");
            } else {
                report.append("This is the scheduled health snapshot; no interruption was proven by the available evidence.\n");
            }
            report.append("\n## Recommended next checks\n\n");
            report.append("1. Review the linked current Java and paired-update logs, using older logs only as historical context.\n");
            report.append("2. If the classification is unexpected, check the newest startup-failure, router, and memory-sync records before changing source.\n");
            report.append("3. ").append(recommendationFor(javaFindings, routerFindings)).append("\n");
            report.append("4. Keep this report local; it contains paths and event summaries but no API credentials.\n");
            Path reportPath = reportDirectory.resolve("Ciel-Recovery-" + timestamp + ".md");
            Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
            return reportPath;
        } catch (Exception e) {
            System.err.println("Ciel Warning: Recovery report write failed: " + e.getMessage());
            return null;
        }
    }

    private static String collectSystemEvents() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return "unavailable_non_windows";
        String command = "$ids=41,1074,6006,6008; "
                + "$since=(Get-Date).AddHours(-48); "
                + "Get-WinEvent -FilterHashtable @{LogName='System'; Id=$ids; StartTime=$since} -MaxEvents 20 -ErrorAction SilentlyContinue "
                + "| Select-Object TimeCreated,Id,ProviderName | ConvertTo-Json -Compress";
        try {
            WindowlessCommand.Result result = WindowlessCommand.run(List.of("powershell.exe", "-NoProfile",
                    "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command), 8);
            return sanitize(result.output().trim());
        } catch (Exception e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    private static String collectBootObservation() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return "unavailable_non_windows";
        try {
            WindowlessCommand.Result result = WindowlessCommand.run(List.of("powershell.exe", "-NoProfile",
                    "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                    "(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToString('o')"), 6);
            return sanitize(result.output().trim());
        } catch (Exception e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    private static Path newestFile(Path directory, String glob) {
        if (!Files.isDirectory(directory)) return null;
        try (var stream = Files.newDirectoryStream(directory, glob)) {
            List<Path> paths = new ArrayList<>();
            for (Path path : stream) if (Files.isRegularFile(path)) paths.add(path);
            return paths.stream().max(Comparator.comparingLong(SessionRecoveryService::lastModified)).orElse(null);
        } catch (IOException e) { return null; }
    }

    private static Path newestMatchingLog(Path directory, String glob) {
        return newestFile(directory, glob);
    }

    private static String logFindings(Path path) {
        if (path == null || !Files.isRegularFile(path)) return "No current log was available.";
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 4000);
            List<String> findings = new ArrayList<>();
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.matches("(?i).*\\b(error|exception|failed|failure|timeout|timed out|unavailable|rejected|fatal|warning)\\b.*")) {
                    findings.add(sanitize(line));
                }
            }
            if (findings.isEmpty()) return "No error or warning markers were found in the current log tail.";
            int first = Math.max(0, findings.size() - 25);
            StringBuilder result = new StringBuilder();
            for (int i = first; i < findings.size(); i++) result.append("- ").append(findings.get(i)).append("\n");
            if (findings.size() > 25) result.insert(0, "- ... older findings omitted; see the linked log.\n");
            return result.toString().trim();
        } catch (Exception e) {
            return "Unable to read current log: " + e.getClass().getSimpleName();
        }
    }

    private static String recommendationFor(String javaFindings, String routerFindings) {
        String combined = (javaFindings + " " + routerFindings).toLowerCase(Locale.ROOT);
        if (combined.contains("inference engines not ready") || combined.contains("readiness failed")) {
            return "Inference readiness was implicated; verify local/cloud provider availability and model eligibility before changing the Java path.";
        }
        if (combined.contains("memory") && (combined.contains("timeout") || combined.contains("failed"))) {
            return "Memory-sync evidence was implicated; compare listener, service, and Tailscale CLI state independently before changing the bridge.";
        }
        if (combined.contains("startup failed") || combined.contains("fatal")) {
            return "Startup failure evidence was found; verify the current JAR, Java executable, and launcher manifest before restoring anything older.";
        }
        if (combined.contains("warning")) {
            return "Warnings were present; review the linked current logs first and treat older logs only as context.";
        }
        return "No targeted repair is indicated by the current log tail; keep the report for the next comparison.";
    }

    private static long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException e) { return 0L; }
    }

    private static String pathInfo(Path path) {
        if (path == null) return "unavailable";
        return "`" + path.toAbsolutePath() + "` (last write " + Instant.ofEpochMilli(lastModified(path)) + ")";
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "none";
        String compact = value.replaceAll("[\\r\\n]+", " ").trim();
        compact = SECRET_PATTERN.matcher(compact).replaceAll("$1=<redacted>");
        return compact.length() > 1800 ? compact.substring(0, 1800) + "..." : compact;
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        return object.get(key).getAsString();
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : "`" + value + "`";
    }

    private static long secondsSince(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return Long.MAX_VALUE;
        try { return Math.max(0L, Duration.between(Instant.parse(timestamp), Instant.now()).getSeconds()); }
        catch (DateTimeParseException e) { return Long.MAX_VALUE; }
    }
}
