package com.cielcompanion.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Properties;

/**
 * Conservative local reservation ledger for Breeze's 400-credit daily grant.
 * Breeze charges one credit per ten characters, with CJK characters weighted
 * as two.  A reservation is kept when a request times out because the server
 * may have accepted it; this avoids accidentally doubling usage on retries.
 * The file is intentionally replaceable by the paired memory-sync service so
 * the two devices can merge reservations later without putting the API key in
 * shared memory.
 */
public final class BreezeUsageTracker {
    private static final Object LOCK = new Object();
    private static final Path FILE = Path.of(".local", "breeze_usage.properties");

    private BreezeUsageTracker() {}

    public static boolean reserve(String text) {
        long credits = creditsFor(text);
        synchronized (LOCK) {
            Properties p = read();
            String today = LocalDate.now().toString();
            if (!today.equals(p.getProperty("day"))) {
                p.clear();
                p.setProperty("day", today);
                p.setProperty("credits", "0");
            }
            long used = parseLong(p.getProperty("credits"), 0L);
            long limit = Math.max(1L, Settings.getBreezeDailyCreditLimit());
            if (credits > limit || used > limit - credits) {
                System.err.println("[Breeze TTS] Daily credit guard declined request (used="
                        + used + ", requested=" + credits + ", limit=" + limit + ").");
                return false;
            }
            p.setProperty("credits", Long.toString(used + credits));
            return write(p);
        }
    }

    private static long creditsFor(String text) {
        if (text == null || text.isEmpty()) return 1L;
        long weightedCharacters = text.codePoints().mapToLong(cp -> isCjk(cp) ? 2L : 1L).sum();
        return Math.max(1L, (weightedCharacters + 9L) / 10L);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3000 && cp <= 0x30ff) || (cp >= 0x3400 && cp <= 0x9fff)
                || (cp >= 0xf900 && cp <= 0xfaff);
    }

    private static Properties read() {
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(FILE)) {
                try (InputStream in = Files.newInputStream(FILE)) { p.load(in); }
            }
        } catch (IOException e) {
            System.err.println("[Breeze TTS] Usage ledger read failed; refusing unsafe retry: " + e.getMessage());
        }
        return p;
    }

    private static boolean write(Properties p) {
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "Breeze daily credit reservations"); }
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException atomicFailure) {
            try {
                Files.createDirectories(FILE.getParent());
                try (OutputStream out = Files.newOutputStream(FILE)) { p.store(out, "Breeze daily credit reservations"); }
                return true;
            } catch (IOException e) {
                System.err.println("[Breeze TTS] Usage ledger write failed; request declined: " + e.getMessage());
                return false;
            }
        }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }
}
