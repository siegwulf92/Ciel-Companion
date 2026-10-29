package com.cielcompanion.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes a console child without creating a console window and captures bounded stdout. */
public final class WindowlessCommand {
    public record Result(int exitCode, String output) {}

    private WindowlessCommand() {}

    public static Result run(List<String> child, int timeoutSeconds) throws Exception {
        return run(child, timeoutSeconds, null);
    }

    public static Result run(List<String> child, int timeoutSeconds, byte[] standardInput) throws Exception {
        int boundedTimeout = Math.max(1, Math.min(timeoutSeconds, 300));
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Process process = new ProcessBuilder(child).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (standardInput != null) {
                process.getOutputStream().write(standardInput);
                process.getOutputStream().close();
            }
            if (!process.waitFor(boundedTimeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new java.io.IOException("Background command timed out");
            }
            return new Result(process.exitValue(), new String(process.getInputStream().readNBytes(1_000_000), StandardCharsets.UTF_8));
        }
        Path output = Files.createTempFile("ciel-hidden-command-", ".out");
        Path input = standardInput == null ? null : Files.createTempFile("ciel-hidden-command-", ".in");
        try {
            if (input != null) Files.write(input, standardInput);
            String runner = "import pathlib,subprocess,sys; "
                    + "data=None if sys.argv[3]=='-' else pathlib.Path(sys.argv[3]).read_bytes(); "
                    + "p=subprocess.run(sys.argv[4:],input=data,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,"
                    + "timeout=float(sys.argv[2]),creationflags=0x08000000); "
                    + "pathlib.Path(sys.argv[1]).write_bytes(p.stdout[:1000000]); raise SystemExit(p.returncode)";
            List<String> wrapper = new ArrayList<>();
            wrapper.add(ProjectPaths.pythonBackgroundExecutable());
            wrapper.add("-c");
            wrapper.add(runner);
            wrapper.add(output.toString());
            wrapper.add(Integer.toString(boundedTimeout));
            wrapper.add(input == null ? "-" : input.toString());
            wrapper.addAll(child);
            Process process = new ProcessBuilder(wrapper).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(boundedTimeout + 3L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new java.io.IOException("Windowless background command timed out");
            }
            String text = Files.isRegularFile(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
            return new Result(process.exitValue(), text);
        } finally {
            Files.deleteIfExists(output);
            if (input != null) Files.deleteIfExists(input);
        }
    }
}
