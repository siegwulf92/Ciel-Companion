package com.cielcompanion.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

public class HolidayService {

    /**
     * Checks for holidays today or tomorrow and returns a conversational warning for Ciel to speak.
     */
    public static Optional<String> getHolidayGreeting() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        
        String todayHoliday = checkDate(today);
        if (todayHoliday != null) {
            if (todayHoliday.equals("Master Taylor's Birthday")) {
                return Optional.of("[Happy] マスター、 ハッピー バースデー。 アイ ウィッシュ ユー ナッシング バット サクセス イン ザ カミング イヤー。");
            }
            return Optional.of("[Observing] I should note that today is " + todayHoliday + ".");
        }
        
        String tomorrowHoliday = checkDate(tomorrow);
        if (tomorrowHoliday != null) {
            return Optional.of("[Observing] I should note that tomorrow is " + tomorrowHoliday + ". Shall we prepare accordingly?");
        }
        
        return Optional.empty();
    }
    
    /**
     * Appends to the daily idle report or explicit command report.
     */
    public static Optional<String> getDailyReportAddition() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        String todayH = checkDate(today);
        String tomH = checkDate(tomorrow);
        
        if (todayH != null) return Optional.of("Today is " + todayH + ".");
        if (tomH != null) return Optional.of("Tomorrow will be " + tomH + ".");
        return Optional.empty();
    }
    
    private static String checkDate(LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        
        // Personal & Fixed Dates
        if (month == 12 && day == 30) return "Master Taylor's Birthday"; // ~7:40 PM EST, 1992
        if (month == 1 && day == 1) return "New Year's Day";
        if (month == 2 && day == 14) return "Valentine's Day";
        if (month == 3 && day == 10) return "Mario Day";
        if (month == 3 && day == 14) return "Pi Day";
        if (month == 3 && day == 17) return "St. Patrick's Day";
        if (month == 5 && day == 4) return "Star Wars Day";
        if (month == 7 && day == 4) return "Independence Day";
        if (month == 10 && day == 31) return "Halloween";
        if (month == 11 && day == 7) return "N7 Day";
        if (month == 12 && day == 24) return "Christmas Eve";
        if (month == 12 && day == 25) return "Christmas";
        if (month == 12 && day == 31) return "New Year's Eve";
        
        // Dynamic US Holidays
        if (date.equals(LocalDate.of(date.getYear(), Month.MAY, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY)))) return "Mother's Day";
        if (date.equals(LocalDate.of(date.getYear(), Month.JUNE, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.SUNDAY)))) return "Father's Day";
        if (date.equals(LocalDate.of(date.getYear(), Month.NOVEMBER, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)))) return "Thanksgiving";
        
        return null;
    }
}