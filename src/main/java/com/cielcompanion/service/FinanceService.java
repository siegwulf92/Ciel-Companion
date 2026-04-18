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

public class FinanceService {
    private static ScheduledExecutorService scheduler;
    private static String latestPortfolioSummary = "No recent portfolio updates.";
    private static String latestMarketScan = "No recent market scans available.";
    
    private static final Path SYNC_FILE = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "market_sync.dat");

    public static void initialize() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(FinanceService::evaluateMarketSchedule, 5 * 60, 15 * 60, TimeUnit.SECONDS);
        System.out.println("Ciel Debug: FinanceService initialized. Smart Market Schedule and Holiday Awareness active.");
    }

    private static void evaluateMarketSchedule() {
        try {
            ZoneId estZone = ZoneId.of("America/New_York");
            ZonedDateTime now = ZonedDateTime.now(estZone);
            
            long lastFetchMs = loadLastFetchTime();
            ZonedDateTime lastFetch = Instant.ofEpochMilli(lastFetchMs).atZone(estZone);

            boolean marketOpen = isMarketOpen(now);

            if (marketOpen) {
                if (Duration.between(lastFetch, now).toHours() >= 4) {
                    System.out.println("Ciel Debug: Market Live. Interval threshold reached. Initiating analysis...");
                    silentMarketCheck();
                }
            } else {
                ZonedDateTime lastClose = getMostRecentMarketClose(now);
                ZonedDateTime safeSettleTime = lastClose.plusMinutes(30);

                if (lastFetch.isBefore(lastClose) && (now.isAfter(safeSettleTime) || now.isEqual(safeSettleTime))) {
                    System.out.println("Ciel Debug: Market Closed. Executing final daily sync for settled data.");
                    silentMarketCheck();
                } else if (lastFetchMs == 0L) {
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

    private static void silentMarketCheck() {
        System.out.println("Ciel Debug: Commanding Swarm to execute silent background market and portfolio analysis...");
        
        // UPGRADED PROMPT: Age, Goal Alignment, and TL;DR requirement
        String portfolioPrompt = "You are Ciel, Master Taylor's elite financial advisor. Master Taylor's DOB is 12/30/1992 (currently 33 years old), and his ultimate goal is aggressive growth and early retirement. " +
                "Analyze the provided portfolio spreadsheet, paying close attention to his cash liquidity. Identify 'Buy the Dip' opportunities for tech and high-beta assets. " +
                "Note how any newly added funds align with his long-term goals. " +
                "CRITICAL: You MUST include a 'TL;DR' section at the very end summarizing everything in simple, plain English.";

        String marketPrompt = "Perform a macro-economic scan of the S&P 500 and VIX. " +
                "Correlate VIX fear levels with growth opportunities for a 33-year-old investor. Provide a 'Market Threat Level' (Low, Elevated, High, Critical).";

        String recoPrompt = "Recommend 3 to 5 high-growth stock tickers. " +
                "Output STRICTLY in CSV format: Date,Ticker,Price_Target,PEG_Ratio,Confidence,Reason";

        CompletableFuture.runAsync(() -> {
            boolean swarmSuccess = false;
            try {
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
                System.err.println("Ciel Error: Swarm Financial analysis failed or timed out.");
            }

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
                if (!cleanCsv.contains("Ticker")) {
                    cleanCsv = "Date,Ticker,Price_Target,PEG_Ratio,Confidence,Reason\n" + cleanCsv;
                }
                Files.writeString(Paths.get("C:\\Ciel Companion\\ciel\\finance", "recommendations.csv"), cleanCsv);
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to write briefing files to disk.");
        }
    }

    public static boolean isMarketHoliday(LocalDate date) {
        return getHolidayName(date) != null;
    }

    public static String getHolidayName(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        DayOfWeek dow = date.getDayOfWeek();

        if (month == 1 && day == 1) return "New Year's Day";
        if (month == 6 && day == 19) return "Juneteenth";
        if (month == 7 && day == 4) return "Independence Day";
        if (month == 11 && day == 11) return "Veterans Day";
        if (month == 12 && day == 25) return "Christmas Day";

        if (month == 1 && dow == DayOfWeek.MONDAY && day >= 15 && day <= 21) return "MLK Jr. Day";
        if (month == 2 && dow == DayOfWeek.MONDAY && day >= 15 && day <= 21) return "Presidents' Day";
        if (month == 5 && dow == DayOfWeek.MONDAY && day >= 25) return "Memorial Day";
        if (month == 9 && dow == DayOfWeek.MONDAY && day <= 7) return "Labor Day";
        if (month == 10 && dow == DayOfWeek.MONDAY && day >= 8 && day <= 14) return "Columbus Day";
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