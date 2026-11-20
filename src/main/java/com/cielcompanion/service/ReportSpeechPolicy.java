package com.cielcompanion.service;

/** Budget policy for generated daily report speech; existing audio is free. */
public final class ReportSpeechPolicy {
    private ReportSpeechPolicy() {}

    public static boolean isReportKey(String key) {
        if (key == null) return false;
        String normalized = key.replaceFirst("^phonokana-v[0-9]+(?:-[A-Za-z0-9_-]+)?:", "");
        return normalized.startsWith("idle_report.");
    }

    public static boolean maySynthesize(String key, boolean reportGenerationEnabled) {
        return reportGenerationEnabled || !isReportKey(key);
    }

    public static boolean useBreeze(String key, boolean cacheHit, boolean reportGenerationEnabled) {
        return cacheHit || maySynthesize(key, reportGenerationEnabled);
    }

    /** Preserve each requested report's key through asynchronous generation. */
    public static void routeRequestedReport(java.util.concurrent.CompletableFuture<String> response,
            String key, java.util.function.BiConsumer<String, String> speaker,
            java.util.function.Consumer<String> failure, Runnable complete) {
        if (!isReportKey(key)) throw new IllegalArgumentException("Requested report requires a report speech key");
        response.whenComplete((text, error) -> {
            try {
                if (error != null || text == null || text.isBlank()) {
                    failure.accept(error == null ? "empty_response" : error.getClass().getSimpleName());
                } else {
                    speaker.accept(text.trim(), key);
                }
            } finally {
                complete.run();
            }
        });
    }
}
