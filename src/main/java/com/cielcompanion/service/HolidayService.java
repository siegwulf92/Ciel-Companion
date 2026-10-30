package com.cielcompanion.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

public class HolidayService {

    /**
     * Checks for holidays today or tomorrow and returns a conversational warning for Ciel to speak.
     */
    public static Optional<String> getHolidayGreeting() {
        // Holiday wording must follow the user's local calendar rather than the
        // JVM/default timezone (which can still be UTC on a newly provisioned PC).
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        LocalDate tomorrow = today.plusDays(1);
        
        String todayHoliday = checkDate(today);
        if (todayHoliday != null) {
            if (todayHoliday.equals("Master Taylor's Birthday")) {
                return Optional.of("[Happy] マスター、 ハッピー バースデー。 アイ ウィッシュ ユー ナッシング バット サクセス イン ザ カミング イヤー。");
            }
            return Optional.of("[Observing] " + holidayMessage(todayHoliday, true));
        }
        
        String tomorrowHoliday = checkDate(tomorrow);
        if (tomorrowHoliday != null) {
            return Optional.of("[Observing] " + holidayMessage(tomorrowHoliday, false));
        }
        
        return Optional.empty();
    }
    
    /**
     * Appends to the daily idle report or explicit command report.
     */
    public static Optional<String> getDailyReportAddition() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        LocalDate tomorrow = today.plusDays(1);
        String todayH = checkDate(today);
        String tomH = checkDate(tomorrow);
        
        if (todayH != null) return Optional.of("Today is " + todayH + " (" + formatDate(today) + ").");
        if (tomH != null) return Optional.of("Tomorrow will be " + tomH + " (" + formatDate(tomorrow) + ").");
        return Optional.empty();
    }

    private static String formatDate(LocalDate date) {
        return date.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
                + " " + date.getDayOfMonth();
    }

    /**
     * Calendar-aware conversational copy.  The date relationship is supplied
     * by the caller, while the holiday itself selects a fitting theme; this
     * avoids a stale generic "prepare accordingly" line for every occasion.
     */
    private static String holidayMessage(String holiday, boolean today) {
        String relation = today ? "today" : "tomorrow";
        return switch (holiday) {
            case "Halloween" -> today
                    ? "It is Halloween today, Master. The atmosphere is perfect for a little mischief, a spooky film, or a suitably dramatic night."
                    : "Halloween is tomorrow, Master. Shall we plan something suitably spooky—a horror film, a costume, or a little mischief?";
            case "Christmas Eve" -> today
                    ? "It is Christmas Eve today, Master. Shall we make the evening cozy and prepare for tomorrow's celebration?"
                    : "Christmas Eve is tomorrow, Master. Shall we plan a cozy evening and get the final preparations in order?";
            case "Christmas" -> today
                    ? "It is Christmas today, Master. I hope we can make the day warm, peaceful, and full of good company."
                    : "Christmas is tomorrow, Master. Shall we set aside time for something warm, peaceful, and festive?";
            case "Valentine's Day" -> today
                    ? "It is Valentine's Day today, Master. A good excuse for a favorite meal, a heartfelt message, or a little indulgence."
                    : "Valentine's Day is tomorrow, Master. Shall we plan a favorite meal or a thoughtful gesture?";
            case "New Year's Eve" -> today
                    ? "It is New Year's Eve today, Master. Shall we reflect on the year and choose something meaningful to carry forward?"
                    : "New Year's Eve is tomorrow, Master. Shall we make a small plan for the celebration and the year ahead?";
            case "New Year's Day" -> today
                    ? "It is New Year's Day today, Master. A fresh beginning deserves a calm start and one promising intention."
                    : "New Year's Day is tomorrow, Master. Shall we choose one promising intention for the year ahead?";
            case "Star Wars Day" -> today
                    ? "It is Star Wars Day today, Master. This seems like an excellent excuse for a favorite space adventure."
                    : "Star Wars Day is tomorrow, Master. Shall we queue up a favorite space adventure?";
            case "Mario Day" -> today
                    ? "It is Mario Day today, Master. A cheerful little gaming detour may be in order."
                    : "Mario Day is tomorrow, Master. Shall we leave room for a cheerful gaming detour?";
            case "Pi Day" -> today
                    ? "It is Pi Day today, Master. I approve of celebrating it with something circular and preferably edible."
                    : "Pi Day is tomorrow, Master. Shall we secure a suitably circular treat?";
            case "Independence Day" -> today
                    ? "It is Independence Day today, Master. Please keep the celebration enjoyable and remember the noise and heat outside."
                    : "Independence Day is tomorrow, Master. Shall we plan around the heat, noise, and any local celebrations?";
            case "Thanksgiving" -> today
                    ? "It is Thanksgiving today, Master. I hope the day leaves room for good food, gratitude, and a little rest."
                    : "Thanksgiving is tomorrow, Master. Shall we leave room for good food, gratitude, and a little rest?";
            case "Mother's Day", "Father's Day" -> today
                    ? "It is " + holiday + " today, Master. A thoughtful message or small kindness would be a fitting way to mark it."
                    : holiday + " is tomorrow, Master. Shall we plan a thoughtful message or small kindness?";
            case "N7 Day" -> today
                    ? "It is N7 Day today, Master. A little spacefaring nostalgia seems appropriate."
                    : "N7 Day is tomorrow, Master. Shall we make room for a little spacefaring nostalgia?";
            default -> "It is " + holiday + " " + relation + ", Master. Shall we mark the occasion in a way that suits us?";
        };
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
