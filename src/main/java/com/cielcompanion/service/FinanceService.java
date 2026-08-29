package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FinanceService {
    private static ScheduledExecutorService scheduler;
    private static String latestPortfolioSummary = "No recent portfolio updates.";
    private static String latestMarketScan = "No recent market scans available.";
    
    private static final Path SYNC_FILE = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "market_sync.dat");
    private static final Path ATTEMPT_FILE = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "market_attempt.dat");
    
    private static long localSyncTimeMs = 0L;
    private static long localAttemptTimeMs = 0L;

    public static void initialize() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(FinanceService::evaluateMarketSchedule, 5 * 60, 15 * 60, TimeUnit.SECONDS);
        System.out.println("Ciel Debug: FinanceService initialized. Smart Market Schedule and Holiday Awareness active.");
    }

    private static void evaluateMarketSchedule() {
        try {
            ZoneId estZone = ZoneId.of("America/New_York");
            ZonedDateTime now = ZonedDateTime.now(estZone);
            
            long lastFetchMs = loadTimestamp(SYNC_FILE, localSyncTimeMs);
            long lastAttemptMs = loadTimestamp(ATTEMPT_FILE, localAttemptTimeMs);
            
            ZonedDateTime lastFetch = Instant.ofEpochMilli(lastFetchMs).atZone(estZone);

            boolean marketOpen = isMarketOpen(now);
            
            long timeSinceLastAttempt = System.currentTimeMillis() - lastAttemptMs;
            if (timeSinceLastAttempt < TimeUnit.MINUTES.toMillis(5)) {
                return;
            }

            if (marketOpen) {
                if (Duration.between(lastFetch, now).toHours() >= 4) {
                    System.out.println("Ciel Debug: Market Live. Interval threshold reached. Initiating local analysis...");
                    executeSilentMarketCheck();
                }
            } else {
                ZonedDateTime lastClose = getMostRecentMarketClose(now);
                ZonedDateTime safeSettleTime = lastClose.plusMinutes(30);

                if (lastFetch.isBefore(lastClose) && (now.isAfter(safeSettleTime) || now.isEqual(safeSettleTime))) {
                    System.out.println("Ciel Debug: Market Closed. Executing final daily sync for settled data.");
                    executeSilentMarketCheck();
                } else if (lastFetchMs == 0L) {
                    executeSilentMarketCheck();
                } else if (Duration.between(lastFetch, now).toHours() >= 8) {
                    System.out.println("Ciel Debug: Off-hours 8-hour sync threshold reached. Executing routine portfolio scan...");
                    executeSilentMarketCheck();
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to evaluate the Smart Market Schedule.");
        }
    }

    private static void executeSilentMarketCheck() {
        localAttemptTimeMs = System.currentTimeMillis();
        saveTimestamp(ATTEMPT_FILE, localAttemptTimeMs);
        
        CompletableFuture.runAsync(() -> {
            System.out.println("Ciel Debug: Commanding Swarm to execute silent background market and portfolio analysis from local CSVs...");
            
            String marketPrompt = "Perform a macro-economic scan of the S&P 500 and VIX. " +
                    "Correlate VIX fear levels with growth opportunities for a 33-year-old investor. Provide a 'Market Threat Level' (Low, Elevated, High, Critical).";

            String portfolioPrompt = "You are Ciel, Master Taylor's elite financial advisor. Master Taylor's DOB is 12/30/1992 (currently 33 years old), and his ultimate goal is aggressive growth and early retirement. " +
                    "Analyze the provided portfolio spreadsheet and local account CSVs. " +
                    "CRITICAL CONTEXT: The accounts labeled 'taxable' and 'smart' (which hold assets like MINT, TFLO, bonds, or dividend ETFs) function as his liquid Emergency Fund and cash reserves. " +
                    "RULE 1 - TAX-LOSS HARVESTING (< 0% Gain): It is ALWAYS safe to recommend selling assets at a loss in the taxable account to harvest tax benefits. " +
                    "RULE 2 - ROTH IRA FUNDING (> 5% Gain): DO NOT recommend selling assets in the taxable account with > 5% gains UNLESS Master Taylor needs the liquidity to max out his Annual Roth IRA Contribution. " +
                    "RULE 3 - ROTH IRA ALGORITHMS: Focus your 'Buy the Dip' and massive growth allocations STRICTLY on his tax-advantaged 'Roth' account. " +
                    "CRITICAL: You MUST include a 'TL;DR' section at the very end summarizing everything in simple, plain English.";

            String recoPrompt = "[FINANCE_RECOMMENDATIONS] Generate stock recommendations.";

            try {
                System.out.println("Ciel Debug: Step 1/3 - Executing Market Scan...");
                String marketResult = AIEngine.generateSilentLogic("[FINANCE_MARKET_SCAN]", marketPrompt).get(15, TimeUnit.MINUTES);
                if (marketResult == null) throw new RuntimeException("Market Scan timed out or failed.");
                latestMarketScan = marketResult;

                System.out.println("Ciel Debug: Step 2/3 - Executing Portfolio Update...");
                String portfolioResult = AIEngine.generateSilentLogic("[FINANCE_PORTFOLIO_UPDATE]", portfolioPrompt).get(15, TimeUnit.MINUTES);
                if (portfolioResult == null) throw new RuntimeException("Portfolio Update timed out or failed.");
                latestPortfolioSummary = portfolioResult;

                System.out.println("Ciel Debug: Step 3/3 - Executing Strategic Recommendations...");
                String recoResult = AIEngine.generateSilentLogic("[FINANCE_RECOMMENDATIONS]", recoPrompt).get(15, TimeUnit.MINUTES);
                if (recoResult == null) throw new RuntimeException("Recommendations processing timed out or failed.");
                
                writeFiles(recoResult);
                
                localSyncTimeMs = System.currentTimeMillis();
                saveTimestamp(SYNC_FILE, localSyncTimeMs);
                System.out.println("Ciel Debug: Background finance analysis complete. Success flag updated.");
                
            } catch (Exception e) {
                System.err.println("Ciel Error: Swarm Financial analysis failed sequentially: " + e.getMessage());
            }
        });
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
                String cleanCsv = recoCsv.replace("`" + "`" + "`csv", "").replace("`" + "`" + "`", "").trim();
                
                if (!cleanCsv.contains("Date")) {
                    cleanCsv = "Date,Account,Ticker,Action,Shares,Target_Price,Reason_and_Confidence\n" + cleanCsv;
                }
                Files.writeString(Paths.get("C:\\Ciel Companion\\ciel\\finance", "recommendations.csv"), cleanCsv);
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to write briefing files to disk.");
        }
    }

    public static boolean isMarketHoliday(LocalDate date) {
        return getHolidaryName(date) != null;
    }

    public static String getHolidaryName(LocalDate date) {
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
        return context + "PORTFOLIO UPDATE:\n" + latestPortfolioSummary + "\n\nMACRO MARKET SCAN:\n" + latestMarketScan;
    }
}