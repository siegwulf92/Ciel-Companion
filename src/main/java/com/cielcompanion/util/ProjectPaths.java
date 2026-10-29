package com.cielcompanion.util;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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

    /** Use the windowless interpreter for background helpers launched by the GUI. */
    public static String pythonBackgroundExecutable() {
        Path configured = Paths.get(pythonExecutable());
        if (configured.getFileName() != null &&
                configured.getFileName().toString().equalsIgnoreCase("python.exe")) {
            Path windowless = configured.resolveSibling("pythonw.exe");
            if (java.nio.file.Files.isRegularFile(windowless)) return windowless.toString();
        }
        return pythonExecutable();
    }

    /**
     * Run a console-oriented child without ever allocating a visible Windows
     * console. This lightweight wrapper is for commands whose output is not
     * consumed. Use {@link WindowlessCommand} when stdin or captured stdout is
     * part of the command contract.
     */
    public static List<String> windowlessCommand(List<String> child) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return new ArrayList<>(child);
        List<String> wrapped = new ArrayList<>();
        wrapped.add(pythonBackgroundExecutable());
        wrapped.add("-c");
        wrapped.add("import subprocess,sys; p=subprocess.run(sys.argv[1:], creationflags=0x08000000); raise SystemExit(p.returncode)");
        wrapped.addAll(child);
        return wrapped;
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
