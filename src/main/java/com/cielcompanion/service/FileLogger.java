package com.cielcompanion.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public class FileLogger {

    private static final String LOG_DIRECTORY_PATH = "C:\\Ciel Companion\\logs";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendLiteral('.')
            .appendValue(ChronoField.MILLI_OF_SECOND, 3)
            .toFormatter();

    public static void initialize() {
        try {
            File logDir = new File(LOG_DIRECTORY_PATH);
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) {
                    System.err.println("Ciel Error: Failed to create log directory: " + logDir.getAbsolutePath());
                    return;
                }
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String logFileName = logDir.getAbsolutePath() + File.separator + "ciel_companion_" + timestamp + ".log";
            
            FileOutputStream fos = new FileOutputStream(logFileName, true);
            TimestampedPrintStream timestampedPrintStream = new TimestampedPrintStream(fos, true, StandardCharsets.UTF_8);

            System.setOut(timestampedPrintStream);
            System.setErr(timestampedPrintStream);
            System.out.println("Ciel Debug: File logger initialized. All output redirected to " + logFileName);
        } catch (IOException e) {
            System.err.println("Ciel Error: Failed to initialize file logger: " + e.getMessage());
        }
    }

    // Custom PrintStream to add timestamps to every line.
    private static class TimestampedPrintStream extends PrintStream {
        public TimestampedPrintStream(OutputStream out, boolean autoFlush, java.nio.charset.Charset charset) {
            super(out, autoFlush, charset);
        }

        private String getTimestamp() {
            return "[" + LocalDateTime.now().format(TIMESTAMP_FORMATTER) + "] ";
        }

        @Override
        public void println(String x) {
            super.println(getTimestamp() + x);
        }

        @Override
        public void println(Object x) {
            super.println(getTimestamp() + String.valueOf(x));
        }
        
        /**
         * CORRECTED: The return type is now PrintStream to match the overridden method,
         * and it returns `this` to allow for method chaining.
         */
        @Override
        public PrintStream printf(String format, Object... args) {
            super.print(getTimestamp() + String.format(format, args));
            return this;
        }
    }
}
