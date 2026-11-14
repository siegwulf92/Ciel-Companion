package com.cielcompanion.astronomy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Locale;

/** Deterministic solar and weather gates used before any narrative filtering. */
public final class AstronomyVisibility {
    public static final int TWILIGHT_WINDOW_MINUTES = 45;

    private AstronomyVisibility() {}

    public record SolarWindow(ZonedDateTime now, ZonedDateTime sunrise, ZonedDateTime sunset,
                              boolean daylight, boolean twilight) {
        public boolean nightSky() { return !daylight; }
        public String phase() {
            if (twilight) return "twilight";
            return daylight ? "daylight" : "night";
        }
    }

    public static SolarWindow evaluate(ZonedDateTime now, LocalTime sunriseTime, LocalTime sunsetTime) {
        if (now == null || sunriseTime == null || sunsetTime == null) return null;
        ZoneId zone = now.getZone();
        ZonedDateTime sunrise = sunriseTime.atDate(now.toLocalDate()).atZone(zone);
        ZonedDateTime sunset = sunsetTime.atDate(now.toLocalDate()).atZone(zone);
        if (sunset.isBefore(sunrise)) sunset = sunset.plusDays(1);
        boolean daylight = !now.isBefore(sunrise) && now.isBefore(sunset);
        long sunriseDistance = Math.abs(Duration.between(now, sunrise).toMinutes());
        long sunsetDistance = Math.abs(Duration.between(now, sunset).toMinutes());
        boolean twilight = Math.min(sunriseDistance, sunsetDistance) <= TWILIGHT_WINDOW_MINUTES;
        return new SolarWindow(now, sunrise, sunset, daylight, twilight);
    }

    public static String describe(SolarWindow window) {
        if (window == null) return "Solar timing is unavailable, so visibility is unconfirmed.";
        String sunrise = window.sunrise().toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"));
        String sunset = window.sunset().toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"));
        if (window.twilight()) {
            long sunriseDistance = Math.abs(Duration.between(window.now(), window.sunrise()).toMinutes());
            String edge = sunriseDistance <= TWILIGHT_WINDOW_MINUTES
                    ? "daylight is beginning near sunrise"
                    : "daylight is fading near sunset";
            return String.format(Locale.ROOT,
                    "It is %s at sunrise %s and sunset %s; bright objects may be visible, but the darker sky is not fully established.",
                    edge, sunrise, sunset);
        }
        if (window.daylight()) {
            return String.format(Locale.ROOT,
                    "The sun is above the horizon until sunset at %s. Naked-eye stars and most planets are not currently visible in daylight.", sunset);
        }
        return String.format(Locale.ROOT,
                "The sun is below the horizon after sunset at %s and before sunrise at %s; night-sky objects can be evaluated now.", sunset, sunrise);
    }

    public static boolean skyObscuredByWeather(String condition) {
        return skyObscuredByWeather(condition, -1, -1.0);
    }

    public static boolean skyObscuredByWeather(String condition, int cloudCoverPercent, double visibilityMiles) {
        if (cloudCoverPercent >= 90 || (visibilityMiles >= 0 && visibilityMiles < 1.5)) return true;
        if (condition == null) return false;
        String lower = condition.toLowerCase(Locale.ROOT);
        if (lower.contains("partly") || lower.contains("mostly clear")) {
            return lower.contains("rain") || lower.contains("storm") || lower.contains("snow") || lower.contains("fog");
        }
        return lower.contains("rain") || lower.contains("storm") || lower.contains("snow")
                || lower.contains("overcast") || lower.contains("cloud") || lower.contains("fog")
                || lower.contains("mist") || lower.contains("drizzle");
    }

    public static String describeSkyConditions(String condition, int cloudCoverPercent, double visibilityMiles) {
        if ((condition == null || condition.isBlank() || condition.equalsIgnoreCase("Unknown"))
                && cloudCoverPercent < 0 && visibilityMiles < 0) return null;
        StringBuilder result = new StringBuilder("Sky conditions: ");
        if (condition != null && !condition.isBlank() && !condition.equalsIgnoreCase("Unknown")) {
            result.append(condition);
        } else {
            result.append("weather data available");
        }
        if (cloudCoverPercent >= 0) result.append(String.format(Locale.ROOT, "; cloud cover is %d percent", cloudCoverPercent));
        if (visibilityMiles >= 0) result.append(String.format(Locale.ROOT, "; reported visibility is %.1f miles", visibilityMiles));
        if (cloudCoverPercent >= 80 || (visibilityMiles >= 0 && visibilityMiles < 3.0)) {
            result.append(". Bright objects may show, but faint stars and deep-sky objects are unreliable.");
        } else if (cloudCoverPercent >= 40) {
            result.append(". Bright planets may show through gaps, while faint objects remain uncertain.");
        } else {
            result.append(". Conditions are comparatively favorable for the objects that are above the horizon.");
        }
        return result.toString();
    }
}
