package com.cielcompanion.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans the local system for new, unknown application executables.
 */
public class AppScannerService {

    private final AppLauncherService appLauncherService;

    // Common installation directories to scan.
    private static final Set<Path> SEARCH_DIRECTORIES = Set.of(
        Paths.get(System.getenv("ProgramFiles")),
        Paths.get(System.getenv("ProgramFiles(x86)"))
        // We can add common Steam/Epic library paths here in the future
    );

    // Directories to exclude from the scan to avoid system instability.
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
        "Windows", "Common Files", "MSBuild", "Internet Explorer"
    );

    public AppScannerService(AppLauncherService appLauncherService) {
        this.appLauncherService = appLauncherService;
    }

    /**
     * Scans for new applications and adds them to the known paths.
     * @return The number of new applications that were found and saved.
     */
    public int scanAndLearnNewApps() {
        System.out.println("Ciel Debug: Starting application scan...");
        Set<String> knownAppNames = appLauncherService.getKnownAppNames();
        List<Path> newAppPaths = new ArrayList<>();

        for (Path rootDir : SEARCH_DIRECTORIES) {
            try (Stream<Path> paths = Files.walk(rootDir, 5)) { // Limit search depth for performance
                List<Path> executables = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase().endsWith(".exe"))
                    .filter(path -> !isExcluded(path))
                    .collect(Collectors.toList());

                for (Path exePath : executables) {
                    String friendlyName = exePath.getFileName().toString().replace(".exe", "").toLowerCase();
                    if (!knownAppNames.contains(friendlyName)) {
                        newAppPaths.add(exePath);
                        knownAppNames.add(friendlyName); // Prevent re-adding duplicates in the same scan
                    }
                }
            } catch (IOException e) {
                System.err.println("Ciel Error (AppScanner): Failed to scan directory: " + rootDir);
                e.printStackTrace();
            }
        }

        if (!newAppPaths.isEmpty()) {
            System.out.println("Ciel Debug: Found " + newAppPaths.size() + " new applications. Saving...");
            for (Path path : newAppPaths) {
                String friendlyName = path.getFileName().toString().replace(".exe", "").toLowerCase();
                appLauncherService.addAppPath(friendlyName, path.toString());
            }
        }
        
        System.out.println("Ciel Debug: Application scan complete.");
        return newAppPaths.size();
    }

    private boolean isExcluded(Path path) {
        return EXCLUDED_DIRECTORIES.stream().anyMatch(excluded -> path.toString().contains(excluded));
    }
}
