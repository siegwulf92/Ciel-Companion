package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs silently in the background, commanding the Swarm to update 
 * CSV spreadsheets and tracking market metrics without interrupting the Master.
 */
public class FinanceService {
    private static ScheduledExecutorService scheduler;
    private static String latestPortfolioSummary = "No recent portfolio updates.";
    private static String latestMarketScan = "No recent market scans available.";

    public static void initialize() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Trigger a silent market scan 30 seconds after Ciel boots up
        scheduler.schedule(FinanceService::silentMarketCheck, 30, TimeUnit.SECONDS);
        
        // Run a continuous silent check every 4 hours while she is running
        scheduler.scheduleWithFixedDelay(FinanceService::silentMarketCheck, 4, 4, TimeUnit.HOURS);
        
        System.out.println("Ciel Debug: FinanceService initialized. Silent market tracking active.");
    }

    private static void silentMarketCheck() {
        System.out.println("Ciel Debug: Executing silent background market and portfolio analysis...");
        
        // Triggers the Python Portfolio CSV Updater silently
        AIEngine.generateSilentLogic("[FINANCE_PORTFOLIO_UPDATE]", "You are Ciel's financial sub-process. Summarize the portfolio data.").thenAccept(result -> {
            if (result != null && !result.isBlank()) {
                latestPortfolioSummary = result;
            }
        });

        // Triggers the Python Undervalued Stock Scanner silently
        AIEngine.generateSilentLogic("[FINANCE_MARKET_SCAN]", "You are Ciel's financial sub-process. Summarize the market scan.").thenAccept(result -> {
            if (result != null && !result.isBlank()) {
                latestMarketScan = result;
            }
        });
    }

    public static String getDailyFinanceReport() {
        return "PORTFOLIO UPDATE: " + latestPortfolioSummary + "\n\nMARKET SCAN: " + latestMarketScan;
    }
}