package com.cielcompanion.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Properties;

public class AzureUsageTracker {

    private static final String USAGE_FILE = "azure_usage.properties";
    private static final String KEY_MONTH = "current_month";
    private static final String KEY_USAGE_SECONDS = "usage_seconds";
    
    // Default limit: 5 hours * 3600 seconds = 18000 seconds
    private static long monthlyLimitSeconds = 18000;

    public static void setLimit(long limitSeconds) {
        monthlyLimitSeconds = limitSeconds;
    }

    /**
     * Checks if adding the estimated duration would exceed the monthly limit.
     */
    public static synchronized boolean canSpeak(long estimatedDurationSeconds) {
        ensureMonthConsistency();
        long currentUsage = getCurrentUsage();
        if (currentUsage + estimatedDurationSeconds > monthlyLimitSeconds) {
            System.out.println("[Azure Tracker] Quota Limit Reached! Used: " + currentUsage + "s, Attempting: " + estimatedDurationSeconds + "s, Limit: " + monthlyLimitSeconds + "s");
            return false;
        }
        return true;
    }

    /**
     * Adds actual spoken duration to the tracker.
     */
    public static synchronized void addUsage(long durationSeconds) {
        ensureMonthConsistency();
        long current = getCurrentUsage();
        saveUsage(current + durationSeconds);
        System.out.println("[Azure Tracker] Usage updated: " + (current + durationSeconds) + "/" + monthlyLimitSeconds + " seconds.");
    }

    private static void ensureMonthConsistency() {
        Properties props = loadProperties();
        String savedMonth = props.getProperty(KEY_MONTH, "");
        String currentMonth = YearMonth.now().toString(); // Format: YYYY-MM

        if (!savedMonth.equals(currentMonth)) {
            System.out.println("[Azure Tracker] New month detected (" + currentMonth + "). Resetting quota.");
            props.setProperty(KEY_MONTH, currentMonth);
            props.setProperty(KEY_USAGE_SECONDS, "0");
            saveProperties(props);
        }
    }

    private static long getCurrentUsage() {
        Properties props = loadProperties();
        String val = props.getProperty(KEY_USAGE_SECONDS, "0");
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void saveUsage(long totalSeconds) {
        Properties props = loadProperties();
        // Ensure month is set correctly before saving
        props.setProperty(KEY_MONTH, YearMonth.now().toString());
        props.setProperty(KEY_USAGE_SECONDS, String.valueOf(totalSeconds));
        saveProperties(props);
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        File file = new File(USAGE_FILE);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return props;
    }

    private static void saveProperties(Properties props) {
        try (FileOutputStream fos = new FileOutputStream(USAGE_FILE)) {
            props.store(fos, "Ciel Companion - Azure Usage Tracking");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}