package com.cielcompanion.astronomy;

import com.cielcompanion.util.AstroUtils;
import java.time.*;
import java.util.*;
import static java.lang.Math.*;

public class MeteorShowers {
    public static class Shower {
        public final String name;
        public final MonthDay peak;
        public final int lookWindow;
        public final double zhr;
        public final double raDeg, decDeg;
        public final String hemisphere;
        public Shower(String name, MonthDay peak, int window, double zhr, double raDeg, double decDeg, String hemi) {
            this.name = name; this.peak = peak; this.lookWindow = window; this.zhr = zhr;
            this.raDeg = raDeg; this.decDeg = decDeg; this.hemisphere = hemi;
        }
    }

    private static final List<Shower> CORE = List.of(
        new Shower("Quadrantids", MonthDay.of(1,4), 2, 120, 230, +49, "N"),
        new Shower("Lyrids",      MonthDay.of(4,22),2, 18,  271, +34, "Both"),
        new Shower("Eta Aquariids",MonthDay.of(5,6),2, 50,  338, -1,  "S"),
        new Shower("Perseids",    MonthDay.of(8,12),3, 100, 48,  +58, "N"),
        new Shower("Orionids",    MonthDay.of(10,21),3, 20,  95,  +16, "Both"),
        new Shower("Leonids",     MonthDay.of(11,17),3, 15, 153, +22, "Both"),
        new Shower("Geminids",    MonthDay.of(12,14),3, 150, 112, +32, "Both")
    );

    public static class Candidate {
        public final Shower shower;
        public final LocalDate date;
        public final double radiantAltDeg;
        public Candidate(Shower s, LocalDate date, double alt) { this.shower = s; this.date = date; this.radiantAltDeg = alt; }
    }

    public static List<Candidate> visibleFrom(double lat, double lon, ZoneId zone,
                                              int lookaheadDays, double minZHR, double minAltDeg) {
        LocalDate today = LocalDate.now(zone);
        List<Candidate> out = new ArrayList<>();
        for (int d = 0; d <= lookaheadDays; d++) {
            LocalDate date = today.plusDays(d);
            for (Shower s : CORE) {
                if (!hemisphereOk(lat, s.hemisphere)) continue;
                int delta = dayDelta(date, s.peak, date.getYear());
                if (abs(delta) <= s.lookWindow) {
                    ZonedDateTime midnight = date.atTime(0, 0).atZone(zone);
                    double alt = AstroUtils.getAltitude(s.raDeg, s.decDeg, lat, lon, midnight);
                    if (alt >= minAltDeg && s.zhr >= minZHR) {
                        out.add(new Candidate(s, date, alt));
                    }
                }
            }
        }
        out.sort(Comparator.comparing((Candidate c) -> c.date).thenComparing(c -> -c.shower.zhr));
        return out;
    }

    private static boolean hemisphereOk(double lat, String hemi) {
        return switch (hemi) {
            case "N" -> lat >= -10;
            case "S" -> lat <= +10;
            default  -> true;
        };
    }

    private static int dayDelta(LocalDate date, MonthDay md, int year) {
        LocalDate peakThisYear = md.atYear(year);
        LocalDate altYear = md.atYear(year + (date.isAfter(peakThisYear) ? 1 : 0));
        LocalDate base = date.isAfter(peakThisYear.plusDays(200)) ? altYear : peakThisYear;
        return (int) (date.toEpochDay() - base.toEpochDay());
    }
}

