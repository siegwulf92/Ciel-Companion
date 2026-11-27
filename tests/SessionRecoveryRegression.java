package com.cielcompanion.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class SessionRecoveryRegression {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ciel-session-recovery-");
        Path marker = root.resolve(".local/ciel-session/planned-restart.json");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "{\"session_id\":\"session-a\","
                + "\"intent\":\"planned_paired_update\",\"created_at\":\""
                + Instant.now() + "\"}", StandardCharsets.UTF_8);
        String intent = SessionRecoveryService.consumePlannedRestartMarker(root, "session-a");
        require("planned_paired_update".equals(intent), "matching fresh marker was not accepted");
        require(!Files.exists(marker), "accepted marker was not consumed");

        Files.writeString(marker, "{\"session_id\":\"other-session\","
                + "\"intent\":\"planned_paired_update\",\"created_at\":\""
                + Instant.now() + "\"}", StandardCharsets.UTF_8);
        require(SessionRecoveryService.consumePlannedRestartMarker(root, "session-a") == null,
                "marker from another session was accepted");
        require(!Files.exists(marker), "rejected marker was not consumed");

        require("planned_windows_shutdown".equals(
                        SessionRecoveryService.classifyTermination("running", "windows_shutdown_pending", "")),
                "durable Windows shutdown intent was not classified as planned");
        require("planned_windows_shutdown".equals(
                        SessionRecoveryService.classifyTermination("running", "planned_windows_shutdown", "")),
                "explicit planned Windows shutdown intent was not classified as planned");
        require("planned_ciel_shutdown".equals(
                        SessionRecoveryService.classifyTermination("running", "planned_paired_update", "")),
                "planned Ciel update was not classified as planned");
        require("interrupted_session".equals(
                        SessionRecoveryService.classifyTermination("running", "none", "")),
                "unmarked running session was not retained as interrupted");
        System.out.println("Session recovery regression checks passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
