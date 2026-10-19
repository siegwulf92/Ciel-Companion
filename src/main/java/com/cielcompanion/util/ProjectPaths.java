package com.cielcompanion.util;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves files relative to the Ciel installation instead of one machine's path. */
public final class ProjectPaths {
    private static final Path ROOT = resolveRoot();

    private ProjectPaths() {}

    public static Path root() { return ROOT; }

    public static Path ciel() { return ROOT.resolve("ciel"); }

    /** Use the same device runtime for the router and offline pronunciation. */
    public static String pythonExecutable() {
        String configured = System.getProperty("ciel.python");
        if (configured != null && !configured.isBlank()) return configured;
        for (String relative : new String[]{".venv/Scripts/python.exe", "OpenJarvis-main/.venv/Scripts/python.exe"}) {
            Path candidate = ROOT.resolve(relative);
            if (java.nio.file.Files.isRegularFile(candidate)) return candidate.toString();
        }
        return "python";
    }

    private static Path resolveRoot() {
        String configured = System.getProperty("ciel.root");
        if (configured != null && !configured.isBlank()) return Paths.get(configured).toAbsolutePath().normalize();
        try {
            Path location = Paths.get(ProjectPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path dir = location;
            if (!java.nio.file.Files.isDirectory(dir)) dir = dir.getParent();
            if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("classes")) dir = dir.getParent();
            if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("target")) dir = dir.getParent();
            if (dir != null) return dir;
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fall back to the launch directory for unusual class loaders.
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }
}
