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
        if (configured.toString().equalsIgnoreCase("python") && System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win")) {
            for (String directory : System.getenv().getOrDefault("PATH", "")
                    .split(java.io.File.pathSeparator)) {
                if (directory.isBlank()) continue;
                Path windowless = Paths.get(directory.replace("\"", "")).resolve("pythonw.exe");
                if (java.nio.file.Files.isRegularFile(windowless)) return windowless.toString();
            }
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
        wrapped.add("import subprocess,sys; sys.exit(subprocess.call(sys.argv[1:], creationflags=subprocess.CREATE_NO_WINDOW))");
        wrapped.addAll(child);
        return wrapped;
    }

    /** An opt-in visible retry is limited to a launch denied before the child ran. */
    public static List<String> windowlessCommand(List<String> child, Path input, Path diagnostic) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return new ArrayList<>(child);
        List<String> wrapped = new ArrayList<>();
        wrapped.add(pythonBackgroundExecutable());
        wrapped.add("-c");
        wrapped.add("""
                import subprocess, sys
                with open(sys.argv[1], 'rb') as prompt, open(sys.argv[2], 'ab') as diagnostic:
                    try:
                        process = subprocess.Popen(sys.argv[3:], stdin=prompt, stdout=subprocess.DEVNULL,
                                                 stderr=diagnostic, creationflags=subprocess.CREATE_NO_WINDOW)
                    except OSError as error:
                        if getattr(error, 'winerror', None) != 5:
                            raise
                        diagnostic.write(b'[Console Launch] Hidden launch denied; attempting one visible launch.\\n')
                        diagnostic.flush()
                        process = subprocess.Popen(sys.argv[3:], stdin=prompt, stderr=diagnostic,
                                                 creationflags=subprocess.CREATE_NEW_CONSOLE)
                raise SystemExit(process.wait())
                """);
        wrapped.add(input.toString());
        wrapped.add(diagnostic.toString());
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
