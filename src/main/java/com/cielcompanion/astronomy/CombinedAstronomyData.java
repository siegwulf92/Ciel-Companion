package com.cielcompanion.astronomy;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * REWORKED: Now holds raw Planet Coordinates (RA/Dec) instead of static strings.
 * This allows Ciel to calculate real-time visibility mathematically without new API calls.
 */
public class CombinedAstronomyData implements Serializable {
    private static final long serialVersionUID = 3L; // Version bumped for new structure

    public final Map<String, String> sunMoonTimes;
    public final String moonPhase;
    // Replaces static 'visiblePlanetLines' with raw data
    public final List<PlanetCoordinate> planetCoordinates; 
    public final List<String> meteorShowerLines;
    public final List<String> prominentConstellationLines; 
    public final long fetchTimeEpochSeconds;

    public CombinedAstronomyData(Map<String, String> sunMoonTimes, String moonPhase, List<PlanetCoordinate> planetCoordinates, List<String> meteorShowerLines, List<String> prominentConstellationLines, long fetchTime) {
        this.sunMoonTimes = sunMoonTimes;
        this.moonPhase = moonPhase;
        this.planetCoordinates = planetCoordinates;
        this.meteorShowerLines = meteorShowerLines;
        this.prominentConstellationLines = prominentConstellationLines;
        this.fetchTimeEpochSeconds = fetchTime;
    }

    /**
     * Inner record to store celestial coordinates.
     * RA (Right Ascension) and Dec (Declination) are essentially "Latitude/Longitude" for the sky.
     */
    public record PlanetCoordinate(String id, String name, double ra, double dec) implements Serializable {}
}