package com.cielcompanion.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;

/** Pure calendar/backoff rules, independent of running services. */
public final class FinanceSchedulePolicy {
    public enum Job { WEEKEND_PLAN, WEEKDAY_NEWS }
    public static Job jobAt(ZonedDateTime now) {
        boolean weekend = now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY;
        int hour = now.getHour();
        if (weekend) return hour >= 9 && hour < 22 ? Job.WEEKEND_PLAN : null;
        return hour >= 8 && hour < 20 ? Job.WEEKDAY_NEWS : null;
    }
    public static long retryMinutes(int failures) {
        return Math.min(240, 15L << Math.min(4, Math.max(0, failures - 1)));
    }
    public static boolean due(ZonedDateTime now, Job job, long success, long attempt, int failures) {
        if (job == null || jobAt(now) != job) return false;
        long millis = now.toInstant().toEpochMilli();
        if (attempt > success && millis - attempt < retryMinutes(failures) * 60_000) return false;
        if (success == 0) return true;
        if (job == Job.WEEKEND_PLAN)
            return !Instant.ofEpochMilli(success).atZone(now.getZone()).toLocalDate().equals(now.toLocalDate());
        return millis - success >= 60 * 60_000;
    }
}
