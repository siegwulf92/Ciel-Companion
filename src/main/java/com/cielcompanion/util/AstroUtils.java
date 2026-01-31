package com.cielcompanion.util;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import static java.lang.Math.*;

/**
 * A utility class containing shared astronomical calculation methods.
 */
public class AstroUtils {

    /**
     * Calculates the altitude of a celestial object above the horizon.
     * @param raDeg Right Ascension in degrees.
     * @param decDeg Declination in degrees.
     * @param latDeg Observer's latitude in degrees.
     * @param lonDeg Observer's longitude in degrees.
     * @param t The time of observation.
     * @return The altitude in degrees.
     */
    public static double getAltitude(double raDeg, double decDeg, double latDeg, double lonDeg, ZonedDateTime t) {
        double lstDeg = localSiderealTimeDeg(t, lonDeg);
        double haDeg  = normalizeDegrees(lstDeg - raDeg);
        double latR = toRadians(latDeg), decR = toRadians(decDeg), haR = toRadians(haDeg);
        double sinAlt = sin(decR) * sin(latR) + cos(decR) * cos(latR) * cos(haR);
        return toDegrees(asin(sinAlt));
    }

    /**
     * Calculates the Local Sidereal Time (LST).
     * @param t The time of observation.
     * @param lonDeg Observer's longitude in degrees.
     * @return The LST in degrees.
     */
    private static double localSiderealTimeDeg(ZonedDateTime t, double lonDeg) {
        ZonedDateTime utc = t.withZoneSameInstant(ZoneId.of("UTC"));
        int Y = utc.getYear(); int M = utc.getMonthValue(); int D = utc.getDayOfMonth();
        double h = utc.getHour() + utc.getMinute()/60.0 + utc.getSecond()/3600.0;
        if (M <= 2) { Y -= 1; M += 12; }
        int A = Y/100; int B = 2 - A + A/4;
        double JD = floor(365.25*(Y+4716)) + floor(30.6001*(M+1)) + D + h/24.0 + B - 1524.5;
        double D0 = JD - 2451545.0;
        double GMST = 280.46061837 + 360.98564736629 * D0;
        double LST = GMST + lonDeg;
        return normalizeDegrees(LST);
    }

    /**
     * Normalizes an angle to be within the range [0, 360).
     */
    private static double normalizeDegrees(double x) {
        x %= 360;
        if (x < 0) x += 360;
        return x;
    }
}
