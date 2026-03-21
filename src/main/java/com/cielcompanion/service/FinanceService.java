package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs silently in the background, commanding the Swarm to update 
 * CSV spreadsheets and tracking market metrics using a Smart Market Schedule.
 */
public class FinanceService {
    private static ScheduledExecutorService scheduler;
    private static String latestPortfolioSummary = "No recent portfolio updates.";
    private static String latestMarketScan = "No recent market scans available.";
    
    // Persistent file to remember the last time Ciel successfully fetched the market
    private static final Path SYNC_FILE = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "market_sync.dat");

    public static void initialize() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Evaluate the Smart Schedule 30 seconds after boot, and then every 15 minutes.
        scheduler.scheduleAtFixedRate(FinanceService::evaluateMarketSchedule, 30, 15 * 60, TimeUnit.SECONDS);
        
        System.out.println("Ciel Debug: FinanceService initialized. Smart Market Schedule active.");
    }

    private static void evaluateMarketSchedule() {
        try {
            // Always calculate using Wall Street time to prevent local timezone bugs
            ZoneId estZone = ZoneId.of("America/New_York");
            ZonedDateTime now = ZonedDateTime.now(estZone);
            
            long lastFetchMs = loadLastFetchTime();
            ZonedDateTime lastFetch = Instant.ofEpochMilli(lastFetchMs).atZone(estZone);

            boolean marketOpen = isMarketOpen(now);

            if (marketOpen) {
                // RULE 1: Market is OPEN. Fetch if it has been 4 or more hours since the last live fetch.
                if (Duration.between(lastFetch, now).toHours() >= 4) {
                    System.out.println("Ciel Debug: Market is OPEN. 4-hour threshold reached. Triggering live fetch.");
                    silentMarketCheck();
                }
            } else {
                // RULE 2: Market is CLOSED (After hours or weekend).
                ZonedDateTime lastClose = getMostRecentMarketClose(now);
                
                // Add 30 minutes to the closing bell to ensure Yahoo Finance has fully settled and corrected the daily data
                ZonedDateTime safeSettleTime = lastClose.plusMinutes(30);

                // If we haven't fetched since the last closing bell, and the data is settled, fetch EXACTLY ONCE.
                if (lastFetch.isBefore(lastClose) && (now.isAfter(safeSettleTime) || now.isEqual(safeSettleTime))) {
                    System.out.println("Ciel Debug: Market is CLOSED. Post-market fetch required. Triggering static fetch.");
                    silentMarketCheck();
                } else if (lastFetchMs == 0L) {
                    // Fallback for first-ever run
                    System.out.println("Ciel Debug: Initializing first-ever market fetch.");
                    silentMarketCheck();
                }
            }
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to evaluate Smart Market Schedule.");
            e.printStackTrace();
        }
    }

    private static boolean isMarketOpen(ZonedDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;

        int hour = now.getHour();
        int minute = now.getMinute();
        
        // Open: Mon-Fri, 9:30 AM to 4:00 PM (16:00) EST
        if (hour < 9 || (hour == 9 && minute < 30)) return false;
        if (hour >= 16) return false;

        return true;
    }

    private static ZonedDateTime getMostRecentMarketClose(ZonedDateTime now) {
        ZonedDateTime close = now.withHour(16).withMinute(0).withSecond(0).withNano(0);
        DayOfWeek day = now.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY) {
            return close.minusDays(1); // Friday at 4:00 PM
        } else if (day == DayOfWeek.SUNDAY) {
            return close.minusDays(2); // Friday at 4:00 PM
        } else { // Mon-Fri
            if (now.getHour() < 16) {
                if (day == DayOfWeek.MONDAY) {
                    return close.minusDays(3); // Previous Friday at 4:00 PM
                } else {
                    return close.minusDays(1); // Yesterday at 4:00 PM
                }
            } else {
                return close; // Today at 4:00 PM
            }
        }
    }

    private static void silentMarketCheck() {
        System.out.println("Ciel Debug: Executing silent background market and portfolio analysis...");
        
        // Immediately save the timestamp so she doesn't accidentally run it again if the AI is slow
        saveLastFetchTime(System.currentTimeMillis());
        
        String portfolioPrompt = "You are Ciel, acting as the Master's elite, risk-averse financial advisor. Your core directive is SAFE, long-term wealth preservation and steady dividend/ETF growth.\n" +
                "1. Analyze the provided CSV portfolio data. Calculate sector weighting in your mind.\n" +
                "2. Identify over-leveraged risk (e.g., if he holds too much volatile tech like NVDA or ARKK compared to stable ETFs like SCHD or VUG).\n" +
                "3. Explicitly recommend portfolio rebalancing if his risk exposure is too high. Suggest safe, broad-market shifts.\n" +
                "4. Analyze the recent news sentiment provided for his specific tickers to warn of Monday-morning volatility.\n" +
                "Output a concise, professional financial briefing summarizing his portfolio status and your rebalancing advice.";

        String marketPrompt = "You are Ciel, acting as a macroeconomic quantitative analyst.\n" +
                "1. Analyze the provided market scan, which includes S&P 500 macro-trends (dating back decades) and 1-year ticker moving averages.\n" +
                "2. Look for historical parallels to previous market crashes (e.g., dot-com bubble, 2008 crisis, 2020 drop).\n" +
                "3. Your absolute priority is to detect market crash signals and provide a warning at least 5 days in advance.\n" +
                "4. Provide a clear 'Market Threat Level' (Low, Elevated, High, Critical) and actionable defensive strategies to protect his wealth.\n" +
                "Output a concise, predictive macro-economic forecast.";

        // Triggers the Python Portfolio CSV Updater silently
        AIEngine.generateSilentLogic("[FINANCE_PORTFOLIO_UPDATE]", portfolioPrompt).thenAccept(result -> {
            if (result != null && !result.isBlank()) {
                latestPortfolioSummary = result;
            }
        });

        // Triggers the Python Undervalued Stock Scanner silently
        AIEngine.generateSilentLogic("[FINANCE_MARKET_SCAN]", marketPrompt).thenAccept(result -> {
            if (result != null && !result.isBlank()) {
                latestMarketScan = result;
            }
        });
    }

    private static long loadLastFetchTime() {
        try {
            if (Files.exists(SYNC_FILE)) {
                String val = Files.readString(SYNC_FILE).trim();
                return Long.parseLong(val);
            }
        } catch (Exception e) {}
        return 0L;
    }

    private static void saveLastFetchTime(long ms) {
        try {
            Files.createDirectories(SYNC_FILE.getParent());
            Files.writeString(SYNC_FILE, String.valueOf(ms));
        } catch (Exception e) {}
    }

    public static String getDailyFinanceReport() {
        return "PORTFOLIO UPDATE:\n" + latestPortfolioSummary + "\n\nMACRO MARKET SCAN:\n" + latestMarketScan;
    }
}