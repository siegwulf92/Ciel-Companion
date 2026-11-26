package com.cielcompanion.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;

/** Pure calendar/backoff rules, independent of running services. */
public final class FinanceSchedulePolicy {
    public enum Job { DAILY_BRIEFING, CLOSED_MARKET_PLAN, NEWS_WATCH }
    /** Exchange-local schedule boundaries used by the finance scheduler. */
    public static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    /**
     * The owner's configured end-of-day boundary.  The regular NYSE session
     * closes at 16:00 ET; the extra half-hour keeps the final briefing from
     * racing the closing quote/dividend refresh and matches the user's 16:30
     * reporting expectation.
     */
    public static final LocalTime FINAL_REPORT_TIME = LocalTime.of(16, 30);
    // NYSE published full-day closures. Refresh this table before 2029.
    private static final Map<Integer, Set<LocalDate>> NYSE_CLOSURES = Map.of(
            2026, dates(2026, "01-01", "01-19", "02-16", "04-03", "05-25", "06-19", "07-03", "09-07", "11-11", "11-26", "12-25"),
            2027, dates(2027, "01-01", "01-18", "02-15", "03-26", "05-31", "06-18", "07-05", "09-06", "11-25", "12-24"),
            2028, dates(2028, "01-17", "02-21", "04-14", "05-29", "06-19", "07-04", "09-04", "11-23", "12-25"));
    private static Set<LocalDate> dates(int year, String... monthDays) {
        var values = new java.util.HashSet<LocalDate>();
        for (String monthDay : monthDays) values.add(LocalDate.parse(year + "-" + monthDay));
        return Set.copyOf(values);
    }
    public static boolean marketClosed(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY || isFullDayHoliday(date);
    }
    public static boolean isFullDayHoliday(LocalDate date) {
        return NYSE_CLOSURES.getOrDefault(date.getYear(), Set.of()).contains(date);
    }
    /**
     * On a full-day exchange holiday, permit one after-hours catch-up for the
     * most recent market day that was missed. This never creates a briefing
     * for the holiday itself; it only clears an older missed obligation.
     */
    public static boolean holidayCatchupDue(ZonedDateTime now, long success, long attempt, int failures) {
        if (!isFullDayHoliday(now.toLocalDate()) || now.toLocalTime().isBefore(FINAL_REPORT_TIME)) return false;
        if (attempt > success && now.toInstant().toEpochMilli() - attempt
                < retryMinutes(failures) * 60_000) return false;
        LocalDate previousMarketDay = now.toLocalDate().minusDays(1);
        while (marketClosed(previousMarketDay)) previousMarketDay = previousMarketDay.minusDays(1);
        if (success == 0) return true;
        LocalDate lastSuccess = Instant.ofEpochMilli(success).atZone(now.getZone()).toLocalDate();
        return lastSuccess.isBefore(previousMarketDay);
    }
    public static Job briefingAt(ZonedDateTime now) {
        return marketClosed(now.toLocalDate()) ? Job.CLOSED_MARKET_PLAN : Job.DAILY_BRIEFING;
    }
    public static long retryMinutes(int failures) {
        return Math.min(240, 15L << Math.min(4, Math.max(0, failures - 1)));
    }

    /** Return the most recent exchange day before {@code date}. */
    public static LocalDate previousMarketDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        while (marketClosed(previous)) previous = previous.minusDays(1);
        return previous;
    }

    /**
     * The report's accounting date is the previous market day when Ciel comes
     * back online after midnight but before the next market opens and the
     * prior day's final pass was not recorded.  This prevents a 2 AM catch-up
     * from incorrectly marking the new market day as complete.
     */
    public static LocalDate reportDate(ZonedDateTime now, Job job, long success) {
        if (job != Job.DAILY_BRIEFING || marketClosed(now.toLocalDate())
                || !now.toLocalTime().isBefore(MARKET_OPEN)) return now.toLocalDate();
        LocalDate previous = previousMarketDay(now.toLocalDate());
        long previousClose = previous.atTime(FINAL_REPORT_TIME)
                .atZone(now.getZone()).toInstant().toEpochMilli();
        return success == 0 || success < previousClose ? previous : now.toLocalDate();
    }

    public static boolean due(ZonedDateTime now, Job job, long success, long attempt, int failures) {
        if (job == null || (job != Job.NEWS_WATCH && briefingAt(now) != job)) return false;
        long millis = now.toInstant().toEpochMilli();
        boolean dailyRecoveryWindow = false;
        boolean recoveryAttemptAlreadyMade = false;
        if (job == Job.DAILY_BRIEFING && !marketClosed(now.toLocalDate())) {
            ZonedDateTime marketOpen = now.with(MARKET_OPEN).withSecond(0).withNano(0);
            ZonedDateTime finalTime = now.with(FINAL_REPORT_TIME).withSecond(0).withNano(0);
            boolean preOpenCatchup = now.isBefore(marketOpen);
            boolean finalCatchup = !now.isBefore(finalTime);
            dailyRecoveryWindow = preOpenCatchup || finalCatchup;
            if (attempt > 0) {
                LocalDate attemptDate = Instant.ofEpochMilli(attempt).atZone(now.getZone()).toLocalDate();
                if (preOpenCatchup) {
                    // A failed attempt from a prior date must not block the
                    // first post-login catch-up before today's open.
                    recoveryAttemptAlreadyMade = attemptDate.equals(now.toLocalDate());
                } else if (finalCatchup) {
                    // A pre-close failure must not block the first final pass;
                    // once a final-window attempt exists, honor its backoff.
                    recoveryAttemptAlreadyMade = attempt >= finalTime.toInstant().toEpochMilli()
                            && attemptDate.equals(now.toLocalDate());
                }
            }
        }
        if (attempt > success && millis - attempt < retryMinutes(failures) * 60_000
                && (!dailyRecoveryWindow || recoveryAttemptAlreadyMade)) return false;
        if (job == Job.DAILY_BRIEFING) {
            ZonedDateTime marketOpen = now.with(MARKET_OPEN).withSecond(0).withNano(0);
            LocalDate previous = previousMarketDay(now.toLocalDate());
            long previousClose = previous.atTime(FINAL_REPORT_TIME)
                    .atZone(now.getZone()).toInstant().toEpochMilli();
            // Before today's open, recover a missed final pass for the prior
            // market day.  A success after that prior close satisfies the
            // catch-up and leaves today's obligation untouched.
            if (now.isBefore(marketOpen)) return success == 0 || success < previousClose;
            if (success == 0) return true;
            long todayOpen = marketOpen.toInstant().toEpochMilli();
            if (success < todayOpen) return true;
            // A pre-close/incomplete run is not the final daily briefing.
            // Require a second pass once the configured 16:30 boundary has
            // been reached, even if the earlier pass succeeded.
            ZonedDateTime finalTime = now.with(FINAL_REPORT_TIME).withSecond(0).withNano(0);
            if (now.isBefore(finalTime)) return false;
            return success < finalTime.toInstant().toEpochMilli();
        }
        if (success == 0) return true;
        if (job == Job.NEWS_WATCH) return millis - success >= 60 * 60_000;
        // The market-day briefing is a calendar-day obligation, not a rolling
        // 20-hour timer. If yesterday's run finished late, the next market day
        // must still receive its own report at the next schedule tick.
        LocalDate lastSuccess = Instant.ofEpochMilli(success).atZone(now.getZone()).toLocalDate();
        return lastSuccess.isBefore(now.toLocalDate()) && millis - success >= 20 * 60 * 60_000;
    }
}
