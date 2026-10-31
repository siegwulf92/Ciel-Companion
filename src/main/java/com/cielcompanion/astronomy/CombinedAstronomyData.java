package com.cielcompanion.astronomy;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * REWORKED: Now holds raw Planet Coordinates (RA/Dec) instead of static strings.
 * This allows Ciel to calculate real-time visibility mathematically without new API calls.
 */
public class CombinedAstronomyData implements Serializable {
    private static final long serialVersionUID = 5L; // Adds cached date-range sky-event candidates

    public final Map<String, String> sunMoonTimes;
    public final String moonPhase;
    // Replaces static 'visiblePlanetLines' with raw data
    public final List<PlanetCoordinate> planetCoordinates; 
    public final List<String> meteorShowerLines;
    public final List<String> prominentConstellationLines; 
    public final List<SkyEvent> upcomingSkyEvents;
    public final long fetchTimeEpochSeconds;

    public CombinedAstronomyData(Map<String, String> sunMoonTimes, String moonPhase, List<PlanetCoordinate> planetCoordinates, List<String> meteorShowerLines, List<String> prominentConstellationLines, long fetchTime) {
        this(sunMoonTimes, moonPhase, planetCoordinates, meteorShowerLines, prominentConstellationLines, List.of(), fetchTime);
    }

    public CombinedAstronomyData(Map<String, String> sunMoonTimes, String moonPhase, List<PlanetCoordinate> planetCoordinates, List<String> meteorShowerLines, List<String> prominentConstellationLines, List<SkyEvent> upcomingSkyEvents, long fetchTime) {
        this.sunMoonTimes = sunMoonTimes;
        this.moonPhase = moonPhase;
        this.planetCoordinates = planetCoordinates;
        this.meteorShowerLines = meteorShowerLines;
        this.prominentConstellationLines = prominentConstellationLines;
        this.upcomingSkyEvents = upcomingSkyEvents == null ? List.of() : upcomingSkyEvents;
        this.fetchTimeEpochSeconds = fetchTime;
    }

    public record SkyEvent(String date, String time, String primary, String secondary,
                           double separationDeg, double primaryAltitudeDeg, double primaryAzimuthDeg,
                           double secondaryAltitudeDeg, double secondaryAzimuthDeg) implements Serializable {
        public SkyEvent(String date, String time, String primary, String secondary, double separationDeg) {
            this(date, time, primary, secondary, separationDeg, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    /**
     * Inner record to store celestial coordinates.
     * RA (Right Ascension) and Dec (Declination) are essentially "Latitude/Longitude" for the sky.
     */
    /**
     * A body position captured for a specific observer time.  The original
     * implementation stored only RA/Dec sampled at noon, then extrapolated
     * altitude later.  That made a report stale by several hours and could
     * describe an object as visible after it had set.  The API also gives us
     * horizontal coordinates, apparent magnitude, elongation and (for the
     * Moon) illumination; retain all of those values when available.
     */
    public record PlanetCoordinate(
            String id,
            String name,
            double ra,
            double dec,
            double altitudeDeg,
            double azimuthDeg,
            double magnitude,
            double elongation,
            String phaseName,
            double phaseFraction,
            long capturedAtEpochSeconds) implements Serializable {

        /** Backward-compatible constructor for older callers and fixtures. */
        public PlanetCoordinate(String id, String name, double ra, double dec) {
            this(id, name, ra, dec, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, null, Double.NaN, 0L);
        }

        public boolean hasHorizontalPosition() {
            return Double.isFinite(altitudeDeg) && Double.isFinite(azimuthDeg);
        }

        public boolean hasPhotometry() {
            return Double.isFinite(magnitude);
        }

        public boolean hasMoonPhase() {
            return phaseName != null && !phaseName.isBlank();
        }
    }
}


