package com.cielcompanion.astronomy;
import java.time.LocalDate;
public final class AstronomyNarrativeFacts {
    private AstronomyNarrativeFacts() {}
    public static boolean clearViewing(boolean daytime, int clouds, double visibilityKm, int maxClouds) {
        return !daytime && clouds >= 0 && clouds <= Math.max(0, Math.min(30, maxClouds))
                && Double.isFinite(visibilityKm) && visibilityKm >= 3;
    }
    public static boolean isCurrentVisualKey(String key) {
        return key.startsWith("planet") || key.startsWith("constellation")
                || key.startsWith("deep_sky") || key.equals("special_alignment")
                || key.equals("uranus_note") || key.equals("eclipse");
    }
    public static String moonIllumination(double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return "";
        return "The Moon is approximately " + Math.round(fraction * 100) + " percent illuminated.";
    }
    /** Distant-Sun approximation from observed Sun-Moon elongation, not the
     * provider's inconsistent phase.fraction examples. Error is well below
     * a whole-percent narrative for ordinary observer coordinates. */
    public static double illuminationFromElongation(double elongationDeg) {
        if (!Double.isFinite(elongationDeg) || elongationDeg < 0 || elongationDeg > 180)
            return Double.NaN;
        return (1.0 - Math.cos(Math.toRadians(elongationDeg))) / 2.0;
    }
    public static String meteorWindow(String name, LocalDate date, LocalDate today) {
        if (date == null || !date.equals(today)) return "";
        return name + " is within its approximate annual peak window today. "
                + "The radiant's altitude and clear, dark skies determine whether viewing is worthwhile.";
    }
}
