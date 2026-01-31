package com.cielcompanion.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

/**
 * Manages the loading and execution of multi-step routines.
 */
public class RoutineService {

    private final Properties routines = new Properties();
    private final AppLauncherService appLauncherService;

    public RoutineService(AppLauncherService appLauncherService) {
        this.appLauncherService = appLauncherService;
    }

    public void initialize() {
        try (InputStream is = RoutineService.class.getResourceAsStream("/routines.properties")) {
            if (is == null) {
                System.err.println("Ciel Warning: routines.properties not found. Routines will be disabled.");
                return;
            }
            routines.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            System.out.println("Ciel Debug: Loaded " + routines.size() + " routines.");
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to load routines.properties.");
            e.printStackTrace();
        }
    }

    /**
     * Executes a named routine.
     * @param routineName The name of the routine to execute (e.g., "gaming").
     */
    public void executeRoutine(String routineName) {
        String actions = routines.getProperty(routineName.toLowerCase() + ".actions");
        if (actions == null || actions.isBlank()) {
            System.err.println("Ciel Error: No actions found for routine: " + routineName);
            // Optionally, have Ciel speak that she doesn't know the routine.
            return;
        }

        // Announce the routine is starting.
        LineManager.getRoutineStartLine().ifPresent(line ->
            SpeechService.speak(line.text().replace("{routine_name}", routineName))
        );

        String[] actionSteps = actions.split(",");
        new Thread(() -> {
            for (String step : actionSteps) {
                handleStep(step.trim());
            }
        }).start();
    }

    private void handleStep(String step) {
        String[] parts = step.split(":", 2);
        String action = parts[0];
        String value = (parts.length > 1) ? parts[1] : "";

        switch (action) {
            case "launch":
                handleLaunchAction(value);
                break;
            case "wait":
                handleWaitAction(value);
                break;
            default:
                System.err.println("Ciel Warning: Unknown routine action: " + action);
                break;
        }
    }

    private void handleLaunchAction(String appName) {
        String appPath = appLauncherService.getAppPath(appName);
        if (appPath == null) {
            System.err.println("Ciel Error: Cannot check status of '" + appName + "' because its path is unknown.");
            return;
        }

        // Extract the executable name from the full path
        String executableName = new File(appPath).getName();

        // Check if the application is already running
        Set<String> runningProcesses = SystemMonitor.getSystemMetrics().runningProcesses();
        if (runningProcesses.contains(executableName.toLowerCase())) {
            System.out.println("Ciel Debug: Application '" + executableName + "' is already running. Skipping launch.");
        } else {
            appLauncherService.launchApplication(appName);
        }
    }

    private void handleWaitAction(String secondsStr) {
        try {
            int seconds = Integer.parseInt(secondsStr);
            System.out.println("Ciel Debug: Waiting for " + seconds + " seconds as part of a routine.");
            Thread.sleep(seconds * 1000L);
        } catch (NumberFormatException | InterruptedException e) {
            System.err.println("Ciel Error: Invalid wait time in routine or wait was interrupted.");
            Thread.currentThread().interrupt();
        }
    }
}
