package com.cielcompanion.service;

import javax.swing.filechooser.FileSystemView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AppLauncherService {

    private final Properties appCache = new Properties();
    private final Properties appAliases = new Properties();
    private static final String APP_DATA_DIRECTORY = System.getenv("LOCALAPPDATA") + File.separator + "CielCompanion";
    private static final Path CACHE_FILE = Paths.get(APP_DATA_DIRECTORY, "app_paths.properties");
    private static final Path ALIASES_FILE = Paths.get(APP_DATA_DIRECTORY, "app_aliases.properties");

    public void initialize() {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
        } catch (IOException e) {
            System.err.println("Ciel Error: Could not create app data directory. Paths may not save.");
            e.printStackTrace();
        }

        loadPropertiesFromFile(appCache, CACHE_FILE, "/app_paths.properties");
        loadPropertiesFromFile(appAliases, ALIASES_FILE, "/app_aliases.properties");
    }
    
    private void loadPropertiesFromFile(Properties properties, Path externalPath, String internalResourcePath) {
        if (Files.exists(externalPath)) {
            try (InputStream is = Files.newInputStream(externalPath)) {
                properties.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                System.out.println("Ciel Debug: Loaded " + properties.size() + " entries from " + externalPath.getFileName());
            } catch (IOException e) {
                System.err.println("Ciel Error: Failed to load properties from " + externalPath);
                e.printStackTrace();
            }
        } else {
            System.out.println("Ciel Debug: No external " + externalPath.getFileName() + " found. Loading from internal resources.");
            try (InputStream is = AppLauncherService.class.getResourceAsStream(internalResourcePath)) {
                if (is != null) {
                    properties.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                    try (OutputStream os = Files.newOutputStream(externalPath)) {
                        properties.store(new OutputStreamWriter(os, StandardCharsets.UTF_8), "Ciel Companion - Auto-generated file");
                    } catch (IOException saveEx) {
                        System.err.println("Ciel Error: Failed to save default properties to " + externalPath);
                    }
                }
            } catch (IOException e) {
                System.err.println("Ciel Error: Failed to load internal resource " + internalResourcePath);
                e.printStackTrace();
            }
        }
    }

    private void saveCacheToDisk() {
        try (OutputStream os = Files.newOutputStream(CACHE_FILE)) {
            appCache.store(new OutputStreamWriter(os, StandardCharsets.UTF_8), "Ciel Companion - Application Paths");
            System.out.println("Ciel Debug: Saved application cache to " + CACHE_FILE);
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to save application cache.");
            e.printStackTrace();
        }
    }

    public boolean launchApplication(String friendlyName) {
        String key = friendlyName.toLowerCase().trim();

        if (appCache.containsKey(key)) {
            String cachedPath = appCache.getProperty(key);
            System.out.println("Ciel Debug: Found '" + key + "' in cache. Path: " + cachedPath);
            String executablePath = cachedPath.split(" ")[0];
            if (Files.exists(Paths.get(executablePath))) {
                runExecutable(cachedPath);
                return true;
            } else {
                System.out.println("Ciel Warning: Cached path for '" + key + "' is invalid. Removing from cache.");
                appCache.remove(key);
                saveCacheToDisk();
            }
        }

        String exeName = appAliases.getProperty(key, key.replace(" ", "") + ".exe");
        System.out.println("Ciel Debug: '" + key + "' not in cache. Searching for executable: " + exeName);

        Optional<String> foundPathOpt = searchForExe(exeName);

        if (foundPathOpt.isPresent()) {
            String foundPath = foundPathOpt.get();
            System.out.println("Ciel Debug: Found executable for '" + key + "' at: " + foundPath);
            appCache.put(key, foundPath);
            saveCacheToDisk();
            runExecutable(foundPath);
            return true;
        } else {
            System.err.println("Ciel Error: Could not find '" + exeName + "' on any fixed drive.");
            return false;
        }
    }

    private Optional<String> searchForExe(String exeName) {
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            if (!isFixedDrive(root)) continue;
            System.out.println("Ciel Debug: Scanning drive " + root + "...");
            try (Stream<Path> files = Files.walk(root)) {
                Optional<Path> match = files.parallel()
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(exeName))
                    .findFirst();

                if (match.isPresent()) return Optional.of(match.get().toString());
            } catch (IOException | UncheckedIOException e) {
                System.err.println("Ciel Warning: Could not access parts of drive " + root + ". " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    private boolean isFixedDrive(Path root) {
        FileSystemView fsv = FileSystemView.getFileSystemView();
        File rootFile = root.toFile();
        String typeDescription = fsv.getSystemTypeDescription(rootFile);
        return typeDescription != null && typeDescription.equals("Local Disk") && rootFile.getTotalSpace() > 0;
    }

    private void runExecutable(String path) {
        try {
            System.out.println("Ciel Command: Executing path: " + path);
            new ProcessBuilder(path.split(" ")).start();
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to execute launch command for path: " + path);
            e.printStackTrace();
        }
    }

    public void addAppPath(String friendlyName, String path) {
        appCache.setProperty(friendlyName.toLowerCase(), path);
        saveCacheToDisk();
    }

    public String getAppPath(String appName) {
        return appCache.getProperty(appName.toLowerCase());
    }

    public Set<String> getKnownAppNames() {
        return appCache.stringPropertyNames().stream().collect(Collectors.toSet());
    }
    
    public boolean terminateProcess(String processName, boolean force) {
        Optional<AppProfilerService.AppProfile> profileOpt = AppProfilerService.findProfileByFuzzyName(processName);
        String executableName;
        if (profileOpt.isPresent()) {
            executableName = profileOpt.get().processName();
        } else {
            executableName = processName.toLowerCase().endsWith(".exe") ? processName : processName + ".exe";
        }

        System.out.println("Ciel Command: Attempting to terminate '" + executableName + "' (Force: " + force + ")");
        String command = "taskkill /IM " + executableName + " /T" + (force ? " /F" : "");
        
        try {
            Process process = Runtime.getRuntime().exec(command);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean success = false;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains("success")) success = true;
                }
                return success;
            }
        } catch (IOException e) {
            return false;
        }
    }
    
    public void closeBrowsers() {
        System.out.println("Ciel Debug: Closing browsers for clean shutdown...");
        String[] browsers = {"chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe"};
        for (String browser : browsers) {
            terminateProcess(browser, false);
        }
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
    }
}