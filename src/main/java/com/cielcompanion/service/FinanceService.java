package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

public class FinanceService {
    private static ScheduledExecutorService scheduler;
    private static volatile String latestPortfolioSummary = "No recent portfolio updates.";
    private static volatile String latestMarketScan = "No recent market scans available.";
    private static volatile String latestRecommendations = "No recommendations generated yet.";
    private static volatile String latestPortfolioDataStatus = "No portfolio refresh has completed yet.";
    private static final ExecutorService financeExecutor = Executors.newFixedThreadPool(3);
    private static final AtomicBoolean analysisRunning = new AtomicBoolean(false);

    public static boolean isAnalysisRunning() {
        return analysisRunning.get();
    }

    private static final Path SYNC_FILE = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "market_sync.dat");
    private static final Path ATTEMPT_FILE = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "market_attempt.dat");

    private static long localSyncTimeMs = 0L;
    private static long localAttemptTimeMs = 0L;
    private static final Path SCHEDULE_STATE = Paths.get(System.getProperty("user.dir"), ".local", "finance-schedule.properties");

    private static synchronized java.util.Properties scheduleState() {
        java.util.Properties state = new java.util.Properties();
        try (var in = Files.newInputStream(SCHEDULE_STATE)) { state.load(in); }
        catch (java.io.IOException ignored) { }
        return state;
    }
    private static synchronized void recordSchedule(FinanceSchedulePolicy.Job job, Boolean succeeded) {
        recordSchedule(job, succeeded, null);
    }
    private static synchronized void recordSchedule(FinanceSchedulePolicy.Job job, Boolean succeeded,
                                                    LocalDate reportDate) {
        var state = scheduleState();
        String key = job.name() + ".";
        long now = System.currentTimeMillis();
        if (succeeded == null) state.setProperty(key + "attempt", Long.toString(now));
        else if (succeeded) {
            state.setProperty(key + "success", Long.toString(now));
            state.setProperty(key + "failures", "0");
            if (reportDate != null) state.setProperty(key + "reportDate", reportDate.toString());
        } else {
            int failures = Integer.parseInt(state.getProperty(key + "failures", "0")) + 1;
            state.setProperty(key + "failures", Integer.toString(failures));
            // A failed/deferred attempt is still an attempt. Persist its time
            // so the next scheduler tick honors retry backoff.
            state.setProperty(key + "attempt", Long.toString(now));
            System.out.println("[Finance Schedule] " + job + " incomplete; retry eligible after " + FinanceSchedulePolicy.retryMinutes(failures) + " minutes in its next active window.");
        }
        try {
            Files.createDirectories(SCHEDULE_STATE.getParent());
            Path stage = SCHEDULE_STATE.resolveSibling("finance-schedule.properties.tmp");
            try (var out = Files.newOutputStream(stage)) { state.store(out, "Device-local finance schedule"); }
            Files.move(stage, SCHEDULE_STATE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) { System.err.println("[Finance Schedule] State save failed: " + e.getClass().getSimpleName()); }
    }

    public static void initialize() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Check soon after login. Readiness and persistent retry state gate the actual run.
        // A 15-minute boot delay caused short interactive sessions to miss every check.
        scheduler.scheduleAtFixedRate(FinanceService::evaluateMarketSchedule, 2 * 60, 15 * 60, TimeUnit.SECONDS);
        System.out.println("Ciel Debug: FinanceService initialized. Daily briefing or closed-market plan; hourly news watch; persistent retries (2m boot delay).");
    }

    private static void evaluateMarketSchedule() {
        try {
            ZoneId estZone = ZoneId.of("America/New_York");
            ZonedDateTime now = ZonedDateTime.now(estZone);

            var job = FinanceSchedulePolicy.briefingAt(now);
            var state = scheduleState();
            String key = job.name() + ".";
            long success = Long.parseLong(state.getProperty(key + "success", "0"));
            // A holdings import can land after the last briefing (for example,
            // when the user refreshes the CSV after work).  Treat that as a
            // new obligation on a market day so the next active tick uses the
            // current file instead of silently accepting a pre-import report.
            if (job == FinanceSchedulePolicy.Job.DAILY_BRIEFING && success > 0) {
                Path portfolio = Paths.get(System.getProperty("user.dir"), "Ciel", "finance", "portfolio.csv");
                try {
                    if (Files.exists(portfolio) && Files.getLastModifiedTime(portfolio).toMillis() > success) {
                        System.out.println("[Finance Schedule] portfolio.csv changed after the last briefing; scheduling a refreshed market-day run.");
                        success = 0;
                    }
                } catch (java.io.IOException ignored) {
                    // The normal schedule state remains authoritative if the
                    // optional freshness probe cannot read the file.
                }
            }
            if (job == FinanceSchedulePolicy.Job.CLOSED_MARKET_PLAN) {
                Path profile = Paths.get(System.getProperty("user.dir"), "Ciel", "finance", "investor_profile.json");
                if (Files.exists(profile) && Files.getLastModifiedTime(profile).toMillis() > success) success = 0;
            }
            boolean holiday = FinanceSchedulePolicy.isFullDayHoliday(now.toLocalDate());
            if (holiday && FinanceSchedulePolicy.holidayCatchupDue(now,
                    Long.parseLong(state.getProperty(FinanceSchedulePolicy.Job.DAILY_BRIEFING.name() + ".success", "0")),
                    Long.parseLong(state.getProperty(FinanceSchedulePolicy.Job.DAILY_BRIEFING.name() + ".attempt", "0")),
                    Integer.parseInt(state.getProperty(FinanceSchedulePolicy.Job.DAILY_BRIEFING.name() + ".failures", "0")))) {
                executeSilentMarketCheck(FinanceSchedulePolicy.Job.DAILY_BRIEFING);
                return;
            }
            if (!holiday && FinanceSchedulePolicy.due(now, job,
                    success,
                    Long.parseLong(state.getProperty(key + "attempt", "0")),
                    Integer.parseInt(state.getProperty(key + "failures", "0")))) {
                executeSilentMarketCheck(job);
                return;
            }
            String newsKey = FinanceSchedulePolicy.Job.NEWS_WATCH.name() + ".";
            if (FinanceSchedulePolicy.due(now, FinanceSchedulePolicy.Job.NEWS_WATCH,
                    Long.parseLong(state.getProperty(newsKey + "success", "0")),
                    Long.parseLong(state.getProperty(newsKey + "attempt", "0")),
                    Integer.parseInt(state.getProperty(newsKey + "failures", "0")))) executeNewsCheck();

        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to evaluate the Smart Market Schedule: " + e);
        }
    }

    private static void executeSilentMarketCheck(FinanceSchedulePolicy.Job job) {
        ZoneId estZone = ZoneId.of("America/New_York");
        ZonedDateTime scheduledAt = ZonedDateTime.now(estZone);
        var priorState = scheduleState();
        long priorSuccess = Long.parseLong(priorState.getProperty(job.name() + ".success", "0"));
        LocalDate reportDate = FinanceSchedulePolicy.reportDate(scheduledAt, job, priorSuccess);
        String routerStatus = financeRouterStatus();
        if (!"ready".equals(routerStatus)) {
            recordSchedule(job, false, reportDate);
            System.out.println("[Raphael â€” Financial Analysis] Deferred: local AI router " + routerStatus + "; retry state recorded.");
            return;
        }
        if (CodexAgentService.isMaintenanceActive()) {
            recordSchedule(job, null, reportDate);
            System.out.println("[Raphael â€” Financial Analysis] Deferred while the daily health review is active.");
            return;
        }
        if (!analysisRunning.compareAndSet(false, true)) {
            System.out.println("[Raphael â€” Financial Analysis] A finance run is already active; skipping this schedule tick.");
            return;
        }
        // Close the race where daily maintenance claims its window after the first check.
        if (CodexAgentService.isMaintenanceActive()) {
            analysisRunning.set(false);
            recordSchedule(job, null, reportDate);
            System.out.println("[Raphael â€” Financial Analysis] Deferred while the daily health review is active.");
            return;
        }
        recordSchedule(job, null, reportDate);
        localAttemptTimeMs = System.currentTimeMillis();
        saveTimestamp(ATTEMPT_FILE, localAttemptTimeMs);

        latestPortfolioSummary = "Portfolio update in progress.";
        latestMarketScan = "Market scan in progress.";
        latestRecommendations = "Recommendations pending portfolio update.";
        latestPortfolioDataStatus = "Portfolio refresh in progress.";

        System.out.println("[Raphael â€” Financial Analysis] Starting " + job + " with market scan and portfolio refresh.");
        CompletableFuture<String> rawMarketTask = runFinanceTask(
                "Market scan", "[FINANCE_MARKET_SCAN]", "Fetch macro market data.");
        CompletableFuture<String> portfolioTask = runFinanceTask(
                "Portfolio update", "[FINANCE_PORTFOLIO_UPDATE]", "Update portfolio CSV data.");

        CompletableFuture<String> marketTask = rawMarketTask.thenApply(result -> {
            latestMarketScan = result;
            return result;
        });

        CompletableFuture<String> recommendationsTask = portfolioTask.thenCompose(result -> {
            latestPortfolioSummary = result;
            latestPortfolioDataStatus = isFinanceFailure(result)
                    ? "Portfolio refresh failed: " + result
                    : result;
            latestRecommendations = "Recommendations in progress.";
            return runFinanceTask("Strategic recommendations",
                    (job == FinanceSchedulePolicy.Job.CLOSED_MARKET_PLAN
                            ? "[FINANCE_CLOSED_MARKET_RECOMMENDATIONS]" : "[FINANCE_DAILY_RECOMMENDATIONS]")
                            + " Generate stock recommendations.",
                    "Generate strategic advice.");
        }).thenApply(result -> {
            latestRecommendations = result;
            System.out.println("[Raphael â€” Financial Analysis] Recommendation narrative saved (" + result.length() + " chars).");
            return result;
        });

        CompletableFuture.allOf(marketTask, portfolioTask, recommendationsTask)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        System.err.println("[Raphael â€” Financial Analysis] Pipeline failed: " + error);
                        latestRecommendations = "Finance pipeline failed: " + error;
                    }
                    boolean runSucceeded = error == null
                            && !isFinanceFailure(marketTask.getNow(null))
                            && !isFinanceFailure(portfolioTask.getNow(null))
                            && !isFinanceFailure(recommendationsTask.getNow(null));
                    boolean reportWritten = runSucceeded && writeFiles(reportDate);
                    boolean strategicFallback = reportWritten
                            && needsStrategicRetry(recommendationsTask.getNow(null));
                    // Keep a dated, evidence-bounded report available, but do
                    // not mark the strategic review complete when its model
                    // output failed validation.  This preserves today's data
                    // and lets the exponential retry schedule try again later
                    // (including after a holdings import or market close).
                    recordSchedule(job, reportWritten && !strategicFallback, reportDate);
                    if (reportWritten) {
                        CielController.noteMajorBackgroundActivity("I completed the "
                                + (job == FinanceSchedulePolicy.Job.CLOSED_MARKET_PLAN ? "closed-market portfolio plan" : "daily portfolio briefing"));
                        if (!strategicFallback) {
                            localSyncTimeMs = System.currentTimeMillis();
                            saveTimestamp(SYNC_FILE, localSyncTimeMs);
                        } else {
                            System.out.println("[Finance Schedule] Evidence-bounded fallback written; strategic narrative retry remains eligible.");
                        }
                    } else {
                        System.err.println("[Raphael â€” Financial Analysis] Run incomplete; last successful briefing preserved and retry remains eligible.");
                    }
                    analysisRunning.set(false);
                    System.out.println("[Raphael â€” Financial Analysis] Background run complete.");
                    // A failed full briefing must not indefinitely starve the hourly news watch.
                    evaluateMarketSchedule();
                });
    }

    private static void executeNewsCheck() {
        String routerStatus = financeRouterStatus();
        if (!"ready".equals(routerStatus)) {
            recordSchedule(FinanceSchedulePolicy.Job.NEWS_WATCH, false);
            System.out.println("[Finance Schedule] News watch deferred: local AI router " + routerStatus + "; retry state recorded.");
            return;
        }
        if (CodexAgentService.isMaintenanceActive()) {
            recordSchedule(FinanceSchedulePolicy.Job.NEWS_WATCH, null);
            System.out.println("[Finance Schedule] News watch deferred while maintenance is active; no failure recorded.");
            return;
        }
        if (!analysisRunning.compareAndSet(false, true)) return;
        recordSchedule(FinanceSchedulePolicy.Job.NEWS_WATCH, null);
        runFinanceTask("Portfolio news watch", "[FINANCE_NEWS_WATCH]", "Check material news for my holdings using saved prices; do not refresh portfolio prices or the market scan.")
            .whenComplete((result, error) -> {
                boolean success = error == null && !isFinanceFailure(result);
                try {
                    Path directory = Paths.get(System.getProperty("user.dir"), "Ciel", "finance");
                    Files.createDirectories(directory);
                    Path target = directory.resolve(success ? "Latest_Portfolio_News_Watch.md" : "Portfolio_News_Last_Attempt.md");
                    writeAtomically(target, result == null ? "Weekday news watch unavailable: " + error : result);
                    if (success && (result.contains(": REVIEW_BUY") || result.contains(": REVIEW_SELL"))) {
                        CielController.noteMajorBackgroundActivity("I found portfolio news that warrants review");
                        SpeechService.speakPreformatted("[Focused] I found new portfolio news worth reviewing. Please check the news watch before making any trade.", "finance.news.review", false, false);
                    }
                    System.out.println("[Finance Schedule] Portfolio news " + (success ? "completed" : "incomplete") + "; full briefing preserved.");
                } catch (java.io.IOException e) { success = false; }
                finally {
                    recordSchedule(FinanceSchedulePolicy.Job.NEWS_WATCH, success);
                    analysisRunning.set(false);
                }
            });
    }

    private static boolean isFinanceFailure(String result) {
        if (result == null) return true;
        String normalized = result.trim().toLowerCase(java.util.Locale.ROOT);
        // Market evidence is still publishable when the narrative model fails
        // validation: this deterministic fallback preserves dated source data
        // and refuses to make a directional call. Treat it as a completed,
        // evidence-bounded stage so a daily briefing is not silently skipped.
        if (isSafeMarketFallback(normalized)) return false;
        // A model response can fail policy validation while the deterministic
        // holdings fallback remains a complete, evidence-bounded briefing.
        // Only the explicitly marked fallback with usable holdings data is a
        // successful stage; malformed fallback output must still retry.
        if (isValidatedHoldingsFallback(normalized)) return false;
        // A partial quote refresh is still a valid, dated portfolio snapshot
        // when at least one row was refreshed and the remaining rows retained
        // their prior prices. Keep the briefing and label the limitation;
        // only a zero-row refresh remains fatal.
        if (isUsablePartialPortfolioRefresh(normalized)) return false;
        // Ordinary prose may discuss unavailable quotes or past timeouts.
        // Only explicit stage/error markers establish a failed stage.
        return normalized.isEmpty() || normalized.startsWith("error:")
                || normalized.startsWith("portfolio update error:") || normalized.startsWith("market scan error:")
                || normalized.startsWith("portfolio refresh partial:") || normalized.startsWith("portfolio update failed:")
                || normalized.startsWith("portfolio file not found") || normalized.startsWith("portfolio file is empty")
                || normalized.startsWith("[ai error") || normalized.equals("timeout_exception")
                || normalized.startsWith("market data unavailable")
                || normalized.startsWith("finance module error:")
                || normalized.startsWith("weekday news watch unavailable:")
                || normalized.startsWith("portfolio news watch unavailable:")
                || normalized.contains("model review unavailable. these dated discovery headlines")
                || normalized.startsWith("finance status: validated holdings fallback")
                || normalized.startsWith("master, the recommendation models did not return prose this cycle")
                || normalized.startsWith("master, i could not produce my usual strategic review")
                || normalized.startsWith("master, the recommendation model was unavailable")
                || normalized.startsWith("### market scan status\nthe narrative model was unavailable")
                || normalized.matches("(?s)^(market scan|strategic recommendations|portfolio update|finance pipeline) (failed:|returned no result|timed out|unavailable).*" );
    }

    /** True only for a partial refresh with usable current/prior-price data. */
    private static boolean isUsablePartialPortfolioRefresh(String normalized) {
        if (normalized == null || !normalized.startsWith("portfolio refresh partial:")) return false;
        if (!normalized.contains("unavailable quotes retain their prior prices")) return false;
        java.util.regex.Matcher refreshed = java.util.regex.Pattern
                .compile("\\b([1-9][0-9]*)/[0-9]+\\s+priced holding rows refreshed\\b")
                .matcher(normalized);
        return refreshed.find();
    }

    /** Validate the narrow deterministic fallback emitted by ciel_finance.py. */
    private static boolean isValidatedHoldingsFallback(String result) {
        if (result == null) return false;
        String normalized = result.trim().toLowerCase(java.util.Locale.ROOT);
        // The structured Python fallback retains the same evidence marker
        // after its opening heading. Keep strategic retries eligible for it.
        if (normalized.startsWith("### i. snapshot and concentration\n")) {
            normalized = normalized.substring(normalized.indexOf('\n') + 1).stripLeading();
        }
        boolean marker = normalized.startsWith("finance status: validated holdings fallback")
                || normalized.startsWith("master, the recommendation models did not return prose this cycle");
        return marker
                && normalized.contains("tracked value is $")
                && normalized.contains("rules-based read")
                && normalized.contains("model status:");
    }

    /** A fallback is publishable evidence, but its strategic model pass is not complete. */
    private static boolean needsStrategicRetry(String result) {
        return isValidatedHoldingsFallback(result);
    }

    private static boolean isSafeMarketFallback(String result) {
        if (result == null) return false;
        String normalized = result.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("### market scan status")
                && normalized.contains("fetched evidence is preserved")
                && normalized.contains("s&p 500 and vix source data")
                && normalized.contains("market news context")
                && normalized.contains("without a directional conclusion")
                && !normalized.contains("i can't help with that")
                && !normalized.contains("i can’t help with that")
                && !normalized.contains("i cannot help with that");
    }

    /** Probe each router health endpoint and preserve the reason for deferral. */
    private static String financeRouterStatus() {
        int docs = probeRouterEndpoint("/docs");
        if (docs == 200) return "ready";
        int routing = probeRouterEndpoint("/model_routing_status");
        if (routing == 200) return "ready";
        int device = probeRouterEndpoint("/device_status");
        if (device == 200) return "ready";
        if (docs == -1 && routing == -1 && device == -1) return "unavailable (connection failed)";
        return "unavailable (HTTP docs=" + docs + ", routing=" + routing + ", device=" + device + ")";
    }

    private static int probeRouterEndpoint(String path) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:8000" + path).openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1500);
            connection.setRequestMethod("GET");
            return connection.getResponseCode();
        } catch (java.io.IOException e) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static CompletableFuture<String> runFinanceTask(String label, String prompt, String context) {
        return CompletableFuture.supplyAsync(() -> {
            String taskId = "finance-" + UUID.randomUUID();
            System.out.println("[Raphael â€” Financial Analysis] Starting stage: " + label + " task_id=" + taskId);
            try {
                // The router owns the five-attempt provider failover and has a
                // 15-minute evidence budget. Java must wait longer than that
                // so a slow large-context report is never mislabeled as empty.
                String result = AIEngine.generateSilentLogic(
                        prompt, context, "Financial Report Generation", taskId)
                        .get(20, TimeUnit.MINUTES);
                if (result == null || result.isBlank()) {
                    System.err.println("[Raphael â€” Financial Analysis] Stage '" + label + "' failed task_id=" + taskId + " reason=empty-result.");
                    return label + " returned no result.";
                }
                String resultKind = isValidatedHoldingsFallback(result) ? "; validated holdings fallback" : "";
                System.out.println("[Raphael â€” Financial Analysis] Stage '" + label + "' completed task_id=" + taskId + " (" + result.length() + " chars" + resultKind + ").");
                return result;
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                System.err.println("[Raphael â€” Financial Analysis] Stage '" + label + "' failed task_id=" + taskId + ": " + message);
                return label + " failed: " + message;
            }
        }, financeExecutor);
    }

    private static boolean isMarketOpen(ZonedDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        if (isMarketHoliday(now.toLocalDate())) return false;

        int hour = now.getHour();
        int minute = now.getMinute();

        if (hour < FinanceSchedulePolicy.MARKET_OPEN.getHour()
                || (hour == FinanceSchedulePolicy.MARKET_OPEN.getHour()
                && minute < FinanceSchedulePolicy.MARKET_OPEN.getMinute())) return false;
        return now.toLocalTime().isBefore(FinanceSchedulePolicy.FINAL_REPORT_TIME);
    }

    private static ZonedDateTime getMostRecentMarketClose(ZonedDateTime now) {
        ZonedDateTime close = now.with(FinanceSchedulePolicy.FINAL_REPORT_TIME).withSecond(0).withNano(0);
        DayOfWeek day = now.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY) return close.minusDays(1);
        if (day == DayOfWeek.SUNDAY) return close.minusDays(2);

        if (now.toLocalTime().isBefore(FinanceSchedulePolicy.FINAL_REPORT_TIME)) {
            return (day == DayOfWeek.MONDAY) ? close.minusDays(3) : close.minusDays(1);
        }
        return close;
    }

    /**
     * Evidence generated from the files used for this run, not from model
     * prose. This prevents an older "tracked value" sentence from being
     * mistaken for the current portfolio after a refresh or model retry.
     */
    private static String currentPortfolioEvidence(Path financeDir) {
        Path portfolio = financeDir.resolve("portfolio.csv");
        if (!Files.exists(portfolio)) return "## Authoritative portfolio snapshot\n\n- `portfolio.csv` was not readable for this run.";
        try {
            var lines = Files.readAllLines(portfolio);
            if (lines.size() < 2) return "## Authoritative portfolio snapshot\n\n- `portfolio.csv` contains no holding rows.";
            String[] headers = lines.get(0).split(",", -1);
            int accountIndex = -1, valueIndex = -1, quoteIndex = -1;
            for (int i = 0; i < headers.length; i++) {
                String header = headers[i].trim();
                if ("Account".equalsIgnoreCase(header)) accountIndex = i;
                if ("Total_Value".equalsIgnoreCase(header)) valueIndex = i;
                if ("Price_As_Of".equalsIgnoreCase(header)) quoteIndex = i;
            }
            if (valueIndex < 0) return "## Authoritative portfolio snapshot\n\n- `Total_Value` was not present in the current portfolio file.";
            double total = 0.0;
            int rows = 0;
            Map<String, Double> accounts = new LinkedHashMap<>();
            String minQuote = null, maxQuote = null;
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                if (fields.length <= valueIndex) continue;
                try {
                    double value = Double.parseDouble(fields[valueIndex].trim());
                    total += value;
                    rows++;
                    if (accountIndex >= 0 && fields.length > accountIndex) {
                        String account = fields[accountIndex].trim();
                        if (account.isEmpty()) account = "unknown";
                        accounts.merge(account, value, Double::sum);
                    }
                    if (quoteIndex >= 0 && fields.length > quoteIndex) {
                        String quote = fields[quoteIndex].trim();
                        if (quote.length() >= 10) {
                            quote = quote.substring(0, 10);
                            minQuote = minQuote == null || quote.compareTo(minQuote) < 0 ? quote : minQuote;
                            maxQuote = maxQuote == null || quote.compareTo(maxQuote) > 0 ? quote : maxQuote;
                        }
                    }
                } catch (NumberFormatException ignored) { }
            }
            StringBuilder out = new StringBuilder("## Authoritative portfolio snapshot\n\n")
                    .append(String.format(java.util.Locale.US,
                            "- Current normalized `portfolio.csv`: **%d holding rows; $%,.2f total tracked value**.\n",
                            rows, total))
                    .append("- Source file last modified: **")
                    .append(Files.getLastModifiedTime(portfolio).toString())
                    .append("**.\n");
            if (minQuote != null) out.append("- Quote-date range present in the file: **")
                    .append(minQuote).append(" through ").append(maxQuote).append("**.\n");
            if (!accounts.isEmpty()) {
                out.append("- Account totals: ");
                boolean first = true;
                for (Map.Entry<String, Double> entry : accounts.entrySet()) {
                    if (!first) out.append(", ");
                    out.append(String.format(java.util.Locale.US, "%s $%,.2f", entry.getKey(), entry.getValue()));
                    first = false;
                }
                out.append(".\n");
            }
            return out.toString().trim();
        } catch (Exception error) {
            return "## Authoritative portfolio snapshot\n\n- Current snapshot could not be read ("
                    + error.getClass().getSimpleName() + "); prior report preserved.";
        }
    }

    /**
     * Keep the deterministic collectible context in every accepted briefing.
     * It is deliberately sourced from the local, structured MTG artifacts;
     * model prose can omit or hallucinate this section and must not be the
     * report's only source of truth.
     */
    private static String currentMtgEvidence(Path financeDir) {
        Path reports = financeDir.resolve(Paths.get("mtg", "reports"));
        Path review = reports.resolve("Latest_Collection_Review.md");
        Path monitor = reports.resolve("Latest_Price_Monitor.md");
        try {
            if (!Files.isRegularFile(review)) {
                return "## MTG collection and alternative-asset context\n\nNo normalized MTG collection review is available on this device; no value was inferred.";
            }
            String reviewText = Files.readString(review);
            String monitorText = Files.isRegularFile(monitor) ? Files.readString(monitor) : "";
            // Bound the appendix so a malformed local note cannot inflate a
            // finance response or consume model context.
            if (reviewText.length() > 12000) reviewText = reviewText.substring(0, 12000);
            if (monitorText.length() > 2500) monitorText = monitorText.substring(0, 2500);
            StringBuilder out = new StringBuilder("## MTG collection and alternative-asset context\n\n")
                    .append("Collectible estimates are excluded from securities and FIRE totals.\n\n")
                    .append(reviewText.trim());
            if (!monitorText.isBlank()) {
                out.append("\n\n### Price-source status\n\n").append(monitorText.trim());
            }
            out.append("\n\nNo automatic listing or selling is performed; verify condition, fees, liquidity, reprint risk, and cost basis first.");
            return out.toString();
        } catch (Exception error) {
            return "## MTG collection and alternative-asset context\n\nCollection context could not be read ("
                    + error.getClass().getSimpleName() + "); prior report data was preserved.";
        }
    }

    /** Avoid replacing a newer root briefing with an older alternate-path run. */
    private static boolean rootBriefingHasNewerPortfolioSource(Path briefing, Path portfolio) {
        if (!Files.isRegularFile(briefing) || !Files.isRegularFile(portfolio)) return false;
        try {
            String current = Files.readString(briefing);
            Matcher matcher = Pattern.compile("Source file last modified: \\*\\*(.*?)\\*\\*",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(current);
            if (!matcher.find()) return false;
            Instant existing = Instant.parse(matcher.group(1).trim());
            Instant source = Files.getLastModifiedTime(portfolio).toInstant();
            return !existing.isBefore(source);
        } catch (Exception ignored) {
            // An unparseable/legacy report is not trusted as newer evidence;
            // the next complete run is allowed to replace it.
            return false;
        }
    }

    private static synchronized boolean writeFiles(LocalDate reportDate) {
        try {
            ZoneId estZone = ZoneId.of("America/New_York");
            ZonedDateTime generatedAt = ZonedDateTime.now(estZone);
            String yearStr = String.format("%04d", reportDate.getYear());
            String monthStr = String.format("%02d", reportDate.getMonthValue());
            String dayStr = String.format("%02d", reportDate.getDayOfMonth());

            // Build dynamic folders
            Path financeDir = Paths.get(System.getProperty("user.dir"), "Ciel", "finance").toAbsolutePath().normalize();
            Path reportsDir = financeDir.resolve(Paths.get("reports", yearStr, monthStr, dayStr));
            Files.createDirectories(reportsDir);

            String dateStr = generatedAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String fireProgress = "";
            Path fireFile = financeDir.resolve("Latest_FIRE_Progress.md");
            if (Files.exists(fireFile)) {
                fireProgress = "\n\n## Deterministic FIRE Scenario Progress\n\n" + Files.readString(fireFile);
            }
            String mtgEvidence = currentMtgEvidence(financeDir);
            boolean recommendationAlreadyContainsMtg = latestRecommendations.toLowerCase(java.util.Locale.ROOT)
                    .contains("mtg collection and alternative-asset context");

            // Never insert the raw CSV math here!
            boolean closed = FinanceSchedulePolicy.marketClosed(reportDate);
            String content = "# Ciel's " + (closed ? "Closed-Market Portfolio Plan" : "Daily Financial Briefing") + " (" + dateStr + ")\n\n" +
                             "## Portfolio Data Status\n" +
                             latestPortfolioDataStatus + "\n\n" + currentPortfolioEvidence(financeDir) + "\n\n" +
                             "## Ciel's Portfolio Analysis and Recommendations\n" +
                             (closed ? "Planning for the next market session (verify the exchange calendar before trading).\n\n" : "Market-day review.\n\n") + latestRecommendations + "\n\n" +
                             "## Macro Market Scan\n" +
                             latestMarketScan + (recommendationAlreadyContainsMtg ? "" : "\n\n" + mtgEvidence) + fireProgress;

            String filename = "Briefing_" + reportDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyy_MM_dd")) + ".md";
            Path dailyReport = reportsDir.resolve(filename);

            writeAtomically(dailyReport, content);

            // Copy to root only when this run is at least as fresh as the
            // source snapshot already represented there. Dated reports remain
            // immutable evidence even when an older alternate run arrives.
            Path briefingPath = financeDir.resolve("Latest_Financial_Briefing.md");
            if (rootBriefingHasNewerPortfolioSource(briefingPath, financeDir.resolve("portfolio.csv"))) {
                System.out.println("[Raphael — Financial Analysis] Root briefing is newer than this source snapshot; preserving it.");
            } else {
                writeAtomically(briefingPath, content);
            }
            System.out.println("[Raphael â€” Financial Analysis] Markdown written: daily=" + dailyReport.toAbsolutePath()
                    + " (" + Files.size(dailyReport) + " bytes), latest=" + briefingPath.toAbsolutePath()
                    + " (" + Files.size(briefingPath) + " bytes).");
            return true;

        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to write briefing files to disk: " + e);
            e.printStackTrace();
            return false;
        }
    }

    private static void writeAtomically(Path target, String content) throws java.io.IOException {
        Files.createDirectories(target.getParent());
        Path stage = Files.createTempFile(target.getParent(), ".ciel-finance-", ".tmp");
        try {
            Files.writeString(stage, content);
            try {
                Files.move(stage, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(stage, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    public static boolean isMarketHoliday(LocalDate date) {
        return getHolidaryName(date) != null;
    }

    public static String getHolidaryName(LocalDate date) {
        return FinanceSchedulePolicy.marketClosed(date) ? "NYSE market closure" : null;
    }

    public static String getUpcomingHolidayContext() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i <= 3; i++) {
            LocalDate target = today.plusDays(i);
            String name = getHolidaryName(target);
            if (name != null) {
                if (i == 0) return "Today is " + name + ". US Markets are closed.";
                if (i == 1) return "Tomorrow is " + name + ". Markets will be closed.";
                return "The US Market will be closed in " + i + " days for " + name + ".";
            }
        }
        return "";
    }

    private static long loadTimestamp(Path file, long ramFallback) {
        if (ramFallback > 0) return ramFallback;
        try {
            if (Files.exists(file)) {
                return Long.parseLong(Files.readString(file).trim());
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private static void saveTimestamp(Path file, long ms) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.valueOf(ms));
        } catch (Exception ignored) {}
    }

    public static String getDailyFinanceReport() {
        String holiday = getUpcomingHolidayContext();
        String context = holiday.isEmpty() ? "" : "[HOLIDAY ALERT]: " + holiday + "\n\n";
        String watch = "";
        try {
            Path file = Paths.get(System.getProperty("user.dir"), "Ciel", "finance", "Latest_Portfolio_News_Watch.md");
            if (Files.exists(file)) watch = "\n\nLATEST PORTFOLIO NEWS WATCH (check its source timestamps):\n" + Files.readString(file);
        } catch (java.io.IOException ignored) { }
        return context + "PORTFOLIO DATA STATUS:\n" + latestPortfolioDataStatus
                + "\n\nCIEL'S PORTFOLIO ANALYSIS AND RECOMMENDATIONS:\n" + latestRecommendations
                + "\n\nMACRO MARKET SCAN:\n" + latestMarketScan + watch;
    }
}
