package com.cielcompanion.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;

/** Pure calendar/backoff rules, independent of running services. */
public final class FinanceSchedulePolicy {
    public enum Job { DAILY_BRIEFING, CLOSED_MARKET_PLAN, NEWS_WATCH }
    // NYSE published full-day closures. Refresh this table before 2029.
    private static final Map<Integer, Set<LocalDate>> NYSE_CLOSURES = Map.of(
            2026, dates(2026, "01-01", "01-19", "02-16", "04-03", "05-25", "06-19", "07-03", "09-07", "11-26", "12-25"),
            2027, dates(2027, "01-01", "01-18", "02-15", "03-26", "05-31", "06-18", "07-05", "09-06", "11-25", "12-24"),
            2028, dates(2028, "01-17", "02-21", "04-14", "05-29", "06-19", "07-04", "09-04", "11-23", "12-25"));
    private static Set<LocalDate> dates(int year, String... monthDays) {
        var values = new java.util.HashSet<LocalDate>();
        for (String monthDay : monthDays) values.add(LocalDate.parse(year + "-" + monthDay));
        return Set.copyOf(values);
    }
    public static boolean marketClosed(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                || NYSE_CLOSURES.getOrDefault(date.getYear(), Set.of()).contains(date);
    }
    public static Job briefingAt(ZonedDateTime now) {
        return marketClosed(now.toLocalDate()) ? Job.CLOSED_MARKET_PLAN : Job.DAILY_BRIEFING;
    }
    public static long retryMinutes(int failures) {
        return Math.min(240, 15L << Math.min(4, Math.max(0, failures - 1)));
    }
    public static boolean due(ZonedDateTime now, Job job, long success, long attempt, int failures) {
        if (job == null || (job != Job.NEWS_WATCH && briefingAt(now) != job)) return false;
        long millis = now.toInstant().toEpochMilli();
        if (attempt > success && millis - attempt < retryMinutes(failures) * 60_000) return false;
        if (success == 0) return true;
        if (job == Job.NEWS_WATCH) return millis - success >= 60 * 60_000;
        return !Instant.ofEpochMilli(success).atZone(now.getZone()).toLocalDate().equals(now.toLocalDate())
                && millis - success >= 20 * 60 * 60_000;
    }
}
