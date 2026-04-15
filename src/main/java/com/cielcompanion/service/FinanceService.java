package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Advanced Finance Service for Ciel.
 * Manages the Smart Market Schedule, coordinates background analysis with the Swarm,
 * and maintains awareness of US market holidays for the Master's idle reports.
 */
public class FinanceService {
    private static ScheduledExecutorService scheduler;
    private static String latestPortfolioSummary = "No recent portfolio updates.";
    private static String latestMarketScan = "No recent market scans available.";
    
    // The persistent flag file to remember successful market fetches
    private static final Path SYNC_FILE = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "market_sync.dat");

    public static void initialize() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // --- STAGGERED BOOT FIX ---
        // Wait 5 minutes (300 seconds) before checking finance to prevent boot-up RAM spikes
        scheduler.scheduleAtFixedRate(FinanceService::evaluateMarketSchedule, 5 * 60, 15 * 60, TimeUnit.SECONDS);
        
        System.out.println("Ciel Debug: FinanceService initialized. Smart Market Schedule and Holiday Awareness active.");
    }

    private static void evaluateMarketSchedule() {
        try {
            // Calculate based on New York (NYSE) time to prevent timezone-offset bugs
            ZoneId estZone = ZoneId.of("America/New_York");
            ZonedDateTime now = ZonedDateTime.now(estZone);
            
            long lastFetchMs = loadLastFetchTime();
            ZonedDateTime lastFetch = Instant.ofEpochMilli(lastFetchMs).atZone(estZone);

            boolean marketOpen = isMarketOpen(now);

            if (marketOpen) {
                // RULE: If market is live, update every 4 hours.
                if (Duration.between(lastFetch, now).toHours() >= 4) {
                    System.out.println("Ciel Debug: Market Live. Interval threshold reached. Initiating analysis...");
                    silentMarketCheck();
                }
            } else {
                // RULE: If market is closed, ensure we have the absolute latest closing/settlement data.
                ZonedDateTime lastClose = getMostRecentMarketClose(now);
                // Allow 30 minutes for settlement/api data correction post-closing bell
                ZonedDateTime safeSettleTime = lastClose.plusMinutes(30);

                if (lastFetch.isBefore(lastClose) && (now.isAfter(safeSettleTime) || now.isEqual(safeSettleTime))) {
                    System.out.println("Ciel Debug: Market Closed. Executing final daily sync for settled data.");
                    silentMarketCheck();
                } else if (lastFetchMs == 0L) {
                    // Initial bootup fetch if never fetched before
                    silentMarketCheck();
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to evaluate the Smart Market Schedule.");
            e.printStackTrace();
        }
    }

    private static boolean isMarketOpen(ZonedDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        
        // Check if today is a US Federal/Market Holiday
        if (isMarketHoliday(now.toLocalDate())) return false;

        int hour = now.getHour();
        int minute = now.getMinute();
        
        // Standard NYSE: 9:30 AM - 4:00 PM EST
        if (hour < 9 || (hour == 9 && minute < 30)) return false;
        return hour < 16;
    }

    private static ZonedDateTime getMostRecentMarketClose(ZonedDateTime now) {
        ZonedDateTime close = now.withHour(16).withMinute(0).withSecond(0).withNano(0);
        DayOfWeek day = now.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY) return close.minusDays(1);
        if (day == DayOfWeek.SUNDAY) return close.minusDays(2);
        
        // Mon-Fri
        if (now.getHour() < 16) {
            return (day == DayOfWeek.MONDAY) ? close.minusDays(3) : close.minusDays(1);
        }
        return close;
    }

    private static void silentMarketCheck() {
        System.out.println("Ciel Debug: Commanding Swarm to execute silent background market and portfolio analysis...");
        
        String portfolioPrompt = "You are Ciel, Master's elite financial advisor specializing in aggressive growth and early retirement. " +
                "Analyze the provided portfolio spreadsheet. Identify 'Buy the Dip' opportunities for tech and high-beta assets. " +
                "Emphasize gains and long-term tech conviction. Professional, analytical tone.";

        String marketPrompt = "Perform a macro-economic scan of the S&P 500 and VIX. " +
                "Correlate VIX fear levels with growth opportunities. Provide a 'Market Threat Level' (Low, Elevated, High, Critical).";

        String recoPrompt = "Recommend 3 to 5 high-growth stock tickers. " +
                "Output STRICTLY in CSV format: Date,Ticker,Price_Target,PEG_Ratio,Confidence,Reason";

        CompletableFuture.runAsync(() -> {
            boolean swarmSuccess = false;
            try {
                // Execute Swarm calls sequentially to prevent resource contention
                String portfolioResult = AIEngine.generateSilentLogic("[FINANCE_PORTFOLIO_UPDATE]", portfolioPrompt).join();
                String marketResult = AIEngine.generateSilentLogic("[FINANCE_MARKET_SCAN]", marketPrompt).join();
                String recoResult = AIEngine.generateSilentLogic("Generate stock recommendations.", recoPrompt).join();

                if (portfolioResult != null && marketResult != null) {
                    latestPortfolioSummary = portfolioResult;
                    latestMarketScan = marketResult;
                    writeFiles(recoResult);
                    swarmSuccess = true;
                }
            } catch (Exception e) {
                System.err.println("Ciel Error: Swarm Financial analysis failed or timed out. Ciel will retry in 15 minutes.");
            }

            // CRITICAL: Only set the success flag if the Swarm actually returned valid analysis
            if (swarmSuccess) {
                saveLastFetchTime(System.currentTimeMillis());
                System.out.println("Ciel Debug: Background finance analysis complete. Success flag updated.");
            }
        });
    }

    private static void writeFiles(String recoCsv) {
        try {
            String dateStr = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String content = "# Ciel's Financial Briefing (" + dateStr + ")\n\n" +
                             "## Portfolio Analysis\n" + latestPortfolioSummary + "\n\n" +
                             "## Macro Market Scan\n" + latestMarketScan + "\n";
            
            Path briefingPath = Paths.get("C:\\Ciel Companion\\ciel\\finance", "Latest_Financial_Briefing.md");
            Files.createDirectories(briefingPath.getParent());
            Files.writeString(briefingPath, content);

            if (recoCsv != null && recoCsv.contains(",")) {
                String cleanCsv = recoCsv.replace("```csv", "").replace("```", "").trim();
                // Ensure header is present
                if (!cleanCsv.contains("Ticker")) {
                    cleanCsv = "Date,Ticker,Price_Target,PEG_Ratio,Confidence,Reason\n" + cleanCsv;
                }
                Files.writeString(Paths.get("C:\\Ciel Companion\\ciel\\finance", "recommendations.csv"), cleanCsv);
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to write briefing files to disk.");
        }
    }

    // --- HOLIDAY AWARENESS ENGINE ---

    public static boolean isMarketHoliday(LocalDate date) {
        return getHolidayName(date) != null;
    }

    public static String getHolidayName(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        DayOfWeek dow = date.getDayOfWeek();

        // Fixed Date Holidays
        if (month == 1 && day == 1) return "New Year's Day";
        if (month == 6 && day == 19) return "Juneteenth";
        if (month == 7 && day == 4) return "Independence Day";
        if (month == 11 && day == 11) return "Veterans Day";
        if (month == 12 && day == 25) return "Christmas Day";

        // Floating Monday Holidays
        if (month == 1 && dow == DayOfWeek.MONDAY && day >= 15 && day <= 21) return "MLK Jr. Day";
        if (month == 2 && dow == DayOfWeek.MONDAY && day >= 15 && day <= 21) return "Presidents' Day";
        if (month == 5 && dow == DayOfWeek.MONDAY && day >= 25) return "Memorial Day";
        if (month == 9 && dow == DayOfWeek.MONDAY && day <= 7) return "Labor Day";
        if (month == 10 && dow == DayOfWeek.MONDAY && day >= 8 && day <= 14) return "Columbus Day";
        
        // Thanksgiving (4th Thursday)
        if (month == 11 && dow == DayOfWeek.THURSDAY && day >= 22 && day <= 28) return "Thanksgiving";

        return null;
    }

    public static String getUpcomingHolidayContext() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i <= 3; i++) {
            LocalDate target = today.plusDays(i);
            String name = getHolidayName(target);
            if (name != null) {
                if (i == 0) return "Today is " + name + ". US Markets are closed.";
                if (i == 1) return "Tomorrow is " + name + ". Markets will be closed.";
                return "The US Market will be closed in " + i + " days for " + name + ".";
            }
        }
        return "";
    }

    // --- DATA PERSISTENCE ---

    private static long loadLastFetchTime() {
        try {
            if (Files.exists(SYNC_FILE)) {
                return Long.parseLong(Files.readString(SYNC_FILE).trim());
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private static void saveLastFetchTime(long ms) {
        try {
            Files.createDirectories(SYNC_FILE.getParent());
            Files.writeString(SYNC_FILE, String.valueOf(ms));
        } catch (Exception ignored) {}
    }

    public static String getDailyFinanceReport() {
        String holiday = getUpcomingHolidayContext();
        String context = holiday.isEmpty() ? "" : "[HOLIDAY ALERT]: " + holiday + "\n\n";
        return context + "PORTFOLIO UPDATE:\n" + latestPortfolioSummary + "\n\nMACRO MARKET SCAN:\n" + latestMarketScan;
    }
}