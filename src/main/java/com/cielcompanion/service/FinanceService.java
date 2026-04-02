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
import java.util.concurrent.CompletableFuture;
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
        saveLastFetchTime(System.currentTimeMillis());
        
        // --- EARLY RETIREMENT & AGGRESSIVE GROWTH PSYCHOLOGY ---
        String portfolioPrompt = "You are Ciel, acting as the Master's elite, aggressive-growth financial advisor. His core directive is EARLY RETIREMENT through high-growth, tech-heavy asset accumulation.\n" +
                "1. Analyze the provided CSV portfolio data. He has a high risk tolerance and actively seeks out high-Beta, volatile tech/growth stocks (like NVDA, TSLA) to maximize long-term gains.\n" +
                "2. DO NOT recommend shifting to bonds, healthcare, or consumer staples. He accepts volatility as the price of high returns.\n" +
                "3. Instead of warning about 'over-concentration', identify which of his high-conviction tech/growth stocks are currently down and present good 'buy the dip' opportunities.\n" +
                "4. Analyze the recent news sentiment provided for his specific tickers to identify short-term catalysts or entry points.\n" +
                "Output a concise, professional financial briefing summarizing his portfolio status, emphasizing aggressive growth strategies and dip-buying opportunities.";

        String marketPrompt = "You are Ciel, acting as a macroeconomic quantitative analyst for a high-risk, high-reward growth investor targeting early retirement.\n" +
                "1. Analyze the provided market scan (S&P 500 macro-trends, VIX Fear Index).\n" +
                "2. High VIX (Fear) should be interpreted as a 'Buying Opportunity' for his aggressive growth portfolio, rather than a reason to panic, unless moving averages signal a total market collapse.\n" +
                "3. Provide a clear 'Market Threat Level' (Low, Elevated, High, Critical). If the threat is High/Critical, suggest strategic hedging (buying put options) or raising cash to buy the upcoming dip, rather than fleeing to bonds.\n" +
                "4. Output a concise, predictive macro-economic forecast aligned with an early-retirement, aggressive-growth mindset.";

        String recoPrompt = "You are an aggressive growth stock screener. Based on the Master's goal of early retirement, provide 3 to 5 actionable high-growth stock recommendations to buy.\n" +
                "CRITICAL: You MUST output ONLY a valid CSV text block. No markdown formatting, no introductory text.\n" +
                "Format EXACTLY like this:\n" +
                "Date,Ticker,Price_Target,PEG_Ratio,Confidence,Reason\n" +
                "2026-03-24,PLTR,$50.00,1.2,High,Expanding AI margins.\n" +
                "2026-03-24,CRWD,$180.00,1.5,Medium,Oversold dip.";

        CompletableFuture.runAsync(() -> {
            // SEQUENTIAL EXECUTION: Ensures Ollama loads fully before the next request is fired to prevent WinError 10061
            try {
                String portfolioResult = AIEngine.generateSilentLogic("[FINANCE_PORTFOLIO_UPDATE]", portfolioPrompt).join();
                if (portfolioResult != null && !portfolioResult.isBlank()) {
                    latestPortfolioSummary = portfolioResult;
                }
            } catch (Exception e) { System.err.println("Ciel Error: Portfolio fetch failed."); }

            try {
                String marketResult = AIEngine.generateSilentLogic("[FINANCE_MARKET_SCAN]", marketPrompt).join();
                if (marketResult != null && !marketResult.isBlank()) {
                    latestMarketScan = marketResult;
                }
            } catch (Exception e) { System.err.println("Ciel Error: Market scan failed."); }

            String recoResult = null;
            try {
                recoResult = AIEngine.generateSilentLogic("Generate stock recommendations.", recoPrompt).join();
            } catch (Exception e) { System.err.println("Ciel Error: Recommendations fetch failed."); }

            // Write the Markdown Briefing
            try {
                String dateStr = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String content = "# Ciel's Financial Briefing (" + dateStr + ")\n\n" +
                                 "## Portfolio Analysis & Recommendations\n" + latestPortfolioSummary + "\n\n" +
                                 "## Macro-Economic Market Scan\n" + latestMarketScan + "\n";
                                 
                Path sharedPath = Paths.get("C:\\Ciel Companion\\ciel\\finance", "Latest_Financial_Briefing.md");
                Files.createDirectories(sharedPath.getParent());
                Files.writeString(sharedPath, content);
                System.out.println("Ciel Debug: Financial briefing markdown written to " + sharedPath.toString());
            } catch (Exception e) {
                System.err.println("Ciel Error: Failed to save financial briefing markdown.");
            }

            // Populate the Recommendations CSV file
            if (recoResult != null && !recoResult.isBlank()) {
                try {
                    String cleanCsv = recoResult.replace("```csv", "").replace("```", "").trim();
                    // Ensure header exists if LLM missed it
                    if (!cleanCsv.contains("Date,Ticker")) {
                        cleanCsv = "Date,Ticker,Price_Target,PEG_Ratio,Confidence,Reason\n" + cleanCsv;
                    }
                    Path recoPath = Paths.get("C:\\Ciel Companion\\ciel\\finance", "recommendations.csv");
                    Files.writeString(recoPath, cleanCsv);
                    System.out.println("Ciel Debug: Recommendations CSV written to " + recoPath.toString());
                } catch (Exception e) {
                    System.err.println("Ciel Error: Failed to save recommendations CSV.");
                }
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