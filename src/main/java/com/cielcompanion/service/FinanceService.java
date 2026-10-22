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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        var state = scheduleState();
        String key = job.name() + ".";
        long now = System.currentTimeMillis();
        if (succeeded == null) state.setProperty(key + "attempt", Long.toString(now));
        else if (succeeded) {
            state.setProperty(key + "success", Long.toString(now));
            state.setProperty(key + "failures", "0");
        } else {
            int failures = Integer.parseInt(state.getProperty(key + "failures", "0")) + 1;
            state.setProperty(key + "failures", Integer.toString(failures));
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
            if (job == FinanceSchedulePolicy.Job.CLOSED_MARKET_PLAN) {
                Path profile = Paths.get(System.getProperty("user.dir"), "Ciel", "finance", "investor_profile.json");
                if (Files.exists(profile) && Files.getLastModifiedTime(profile).toMillis() > success) success = 0;
            }
            if (FinanceSchedulePolicy.due(now, job,
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
        if (!isFinanceRouterReady()) {
            System.out.println("[Raphael â€” Financial Analysis] Deferred: local AI router is not ready; existing briefing and retry timestamps preserved.");
            return;
        }
        if (CodexAgentService.isMaintenanceActive()) {
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
            System.out.println("[Raphael â€” Financial Analysis] Deferred while the daily health review is active.");
            return;
        }
        recordSchedule(job, null);
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
                    if (runSucceeded) runSucceeded = writeFiles();
                    recordSchedule(job, runSucceeded);
                    if (runSucceeded) {
                        CielController.noteMajorBackgroundActivity("I completed the "
                                + (job == FinanceSchedulePolicy.Job.CLOSED_MARKET_PLAN ? "closed-market portfolio plan" : "daily portfolio briefing"));
                        localSyncTimeMs = System.currentTimeMillis();
                        saveTimestamp(SYNC_FILE, localSyncTimeMs);
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
        if (!isFinanceRouterReady() || CodexAgentService.isMaintenanceActive()) {
            System.out.println("[Finance Schedule] News watch deferred; router unavailable or maintenance active. Rechecking next schedule tick.");
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
                || normalized.startsWith("master, the recommendation models did not return prose this cycle")
                || normalized.startsWith("### market scan status\nthe narrative model was unavailable")
                || normalized.matches("(?s)^(market scan|strategic recommendations|portfolio update|finance pipeline) (failed:|returned no result|timed out|unavailable).*" );
    }

    private static boolean isFinanceRouterReady() {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:8000/docs").openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1500);
            return connection.getResponseCode() == 200;
        } catch (java.io.IOException e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static CompletableFuture<String> runFinanceTask(String label, String prompt, String context) {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("[Raphael â€” Financial Analysis] Starting stage: " + label);
            try {
                String result = AIEngine.generateSilentLogic(prompt, context, "Financial Report Generation").get(10, TimeUnit.MINUTES);
                if (result == null || result.isBlank()) {
                    System.err.println("[Raphael â€” Financial Analysis] Stage '" + label + "' returned an empty result.");
                    return label + " returned no result.";
                }
                System.out.println("[Raphael â€” Financial Analysis] Stage '" + label + "' completed (" + result.length() + " chars).");
                return result;
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                System.err.println("[Raphael â€” Financial Analysis] Stage '" + label + "' failed: " + message);
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
        
        if (hour < 9 || (hour == 9 && minute < 30)) return false;
        return hour < 16;
    }

    private static ZonedDateTime getMostRecentMarketClose(ZonedDateTime now) {
        ZonedDateTime close = now.withHour(16).withMinute(0).withSecond(0).withNano(0);
        DayOfWeek day = now.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY) return close.minusDays(1);
        if (day == DayOfWeek.SUNDAY) return close.minusDays(2);
        
        if (now.getHour() < 16) {
            return (day == DayOfWeek.MONDAY) ? close.minusDays(3) : close.minusDays(1);
        }
        return close;
    }

    private static synchronized boolean writeFiles() {
        try {
            ZoneId estZone = ZoneId.of("America/New_York");
            ZonedDateTime now = ZonedDateTime.now(estZone);
            String yearStr = String.format("%04d", now.getYear());
            String monthStr = String.format("%02d", now.getMonthValue());
            String dayStr = String.format("%02d", now.getDayOfMonth());
            
            // Build dynamic folders
            Path financeDir = Paths.get(System.getProperty("user.dir"), "Ciel", "finance").toAbsolutePath().normalize();
            Path reportsDir = financeDir.resolve(Paths.get("reports", yearStr, monthStr, dayStr));
            Files.createDirectories(reportsDir);

            String dateStr = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String fireProgress = "";
            Path fireFile = financeDir.resolve("Latest_FIRE_Progress.md");
            if (Files.exists(fireFile)) {
                fireProgress = "\n\n## Deterministic FIRE Scenario Progress\n\n" + Files.readString(fireFile);
            }
            
            // Never insert the raw CSV math here!
            boolean closed = FinanceSchedulePolicy.marketClosed(now.toLocalDate());
            String content = "# Ciel's " + (closed ? "Closed-Market Portfolio Plan" : "Daily Financial Briefing") + " (" + dateStr + ")\n\n" +
                             "## Portfolio Data Status\n" +
                             latestPortfolioDataStatus + "\n\n" +
                             "## Ciel's Portfolio Analysis and Recommendations\n" +
                             (closed ? "Planning for the next market session (verify the exchange calendar before trading).\n\n" : "Market-day review.\n\n") + latestRecommendations + "\n\n" +
                             "## Macro Market Scan\n" + 
                             latestMarketScan + fireProgress;
            
            String filename = "Briefing_" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy_MM_dd")) + ".md";
            Path dailyReport = reportsDir.resolve(filename);
            
            writeAtomically(dailyReport, content);

            // Copy to root
            Path briefingPath = financeDir.resolve("Latest_Financial_Briefing.md");
            writeAtomically(briefingPath, content);
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
