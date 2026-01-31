package com.cielcompanion.service;

import com.cielcompanion.CielState;
import com.cielcompanion.astronomy.*;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.util.AstroUtils;
import com.cielcompanion.util.EnglishNumber;
import com.cielcompanion.util.PhonoKana;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class AstronomyService {

    private static AstronomyConfig config = null;
    private static final String LAST_FETCH_DATE_KEY = "ciel.astronomy.last_fetch_date";
    private static final String CACHED_DATA_KEY = "ciel.astronomy.last_combined_api_data";
    
    private static CombinedAstronomyData todaysApiData = null;

    public record AstronomyReport(Map<String, String> sequentialEvents, List<String> reportAmbientLines, List<String> idleAmbientLines) {}

    private static boolean ensureConfigLoaded() {
        if (config == null) {
            try {
                config = AstronomyConfig.loadFromResource("/astronomy.properties");
            } catch (Exception e) {
                System.err.println("Ciel Error: FAILED to load astronomy configuration. Features disabled.");
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    public static void initializeApiState() {
        System.out.println("Ciel Debug (AstronomyService): Initializing API state...");
        if (!ensureConfigLoaded()) {
            CielState.setNeedsAstronomyApiFetch(false);
            return;
        }
        
        Optional<Fact> lastFetchDateFact = MemoryService.getFact(LAST_FETCH_DATE_KEY);
        if (lastFetchDateFact.isPresent()) {
            LocalDate lastFetchDate = LocalDate.parse(lastFetchDateFact.get().value());
            if (lastFetchDate.equals(LocalDate.now())) {
                CielState.setNeedsAstronomyApiFetch(false);
                Optional<Fact> cachedDataFact = MemoryService.getFact(CACHED_DATA_KEY);

                if (cachedDataFact.isPresent()) {
                     try {
                        byte[] data = Base64.getDecoder().decode(cachedDataFact.get().value());
                        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
                        todaysApiData = (CombinedAstronomyData) ois.readObject();
                        ois.close();
                        
                        // Check for incomplete data (Coordinates null means old cache format)
                        boolean isCacheIncomplete = todaysApiData.sunMoonTimes == null || 
                                                    (todaysApiData.planetCoordinates == null) ||
                                                    (todaysApiData.prominentConstellationLines == null);

                        if (isCacheIncomplete) {
                            System.out.println("Ciel Warning (AstronomyService): Cached data is incomplete/old format. Forcing refetch.");
                            CielState.setNeedsAstronomyApiFetch(true);
                            CielState.resetAstronomyFetchAttempts();
                        } else {
                            System.out.println("Ciel Debug (AstronomyService): Loaded cached combined astronomy data for today.");
                        }
                    } catch (Exception e) {
                        System.err.println("Ciel Error (AstronomyService): Failed to deserialize cached data. Forcing refetch.");
                        todaysApiData = null;
                        CielState.setNeedsAstronomyApiFetch(true);
                        CielState.resetAstronomyFetchAttempts();
                    }
                } else {
                     System.out.println("Ciel Warning (AstronomyService): Last fetch date is today, but no cached data was found. Forcing refetch.");
                     todaysApiData = null;
                     CielState.setNeedsAstronomyApiFetch(true);
                     CielState.resetAstronomyFetchAttempts();
                }
            } else {
                 System.out.println("Ciel Debug (AstronomyService): Last fetch was on a different date. A new fetch is required.");
                CielState.setNeedsAstronomyApiFetch(true);
                CielState.resetAstronomyFetchAttempts();
            }
        } else {
            System.out.println("Ciel Debug (AstronomyService): No last fetch date found. A new fetch is required.");
            CielState.setNeedsAstronomyApiFetch(true);
            CielState.resetAstronomyFetchAttempts();
        }
    }
    
    public static Optional<CombinedAstronomyData> getTodaysApiData() {
        return Optional.ofNullable(todaysApiData);
    }

    public static AstronomyReport getTodaysAstronomyReport() {
        Map<String, String> sequentialEvents = new LinkedHashMap<>();
        List<String> reportAmbientLines = new ArrayList<>();
        List<String> idleAmbientLines = new ArrayList<>();

        if (!ensureConfigLoaded()) {
            return new AstronomyReport(sequentialEvents, reportAmbientLines, idleAmbientLines);
        }
        
        if (todaysApiData == null) {
            System.out.println("Ciel Warning (AstronomyService): No astronomy data available. Using static fallback.");
            checkConstellations(sequentialEvents);
            checkVisiblePlanetsStatic(sequentialEvents); // Fallback
            checkMeteorShowers(reportAmbientLines);
            return new AstronomyReport(sequentialEvents, reportAmbientLines, idleAmbientLines);
        }
        
        checkSunriseSunset(sequentialEvents);
        checkEclipses(sequentialEvents);
        checkConstellations(sequentialEvents);
        checkVisiblePlanetsDynamic(sequentialEvents); // Live Math Check
        
        checkMoonPhase(reportAmbientLines);       
        checkSeasonal(idleAmbientLines);  
        checkMeteorShowers(reportAmbientLines); 
        
        System.out.printf("Ciel Debug (AstronomyService): Found %d sequential, %d report ambient, and %d idle ambient lines.%n", 
            sequentialEvents.size(), reportAmbientLines.size(), idleAmbientLines.size());

        return new AstronomyReport(sequentialEvents, reportAmbientLines, idleAmbientLines);
    }

    public static void performApiFetch() {
        if (!ensureConfigLoaded() || !CielState.needsAstronomyApiFetch()) return;

        CielState.incrementAstronomyFetchAttempts();
        System.out.printf("Ciel Debug (AstronomyService): Performing daily API fetch for combined astronomy data... (Attempt %d/3)%n", CielState.getAstronomyFetchAttempts());

        Map<String, String> sunMoonTimes = AstronomyApi.getSunMoonTimes(LocalDate.now());
        String moonPhase = AstronomyApi.getMoonPhase(LocalDate.now());
        // FETCH COORDINATES (Not lines)
        List<CombinedAstronomyData.PlanetCoordinate> planetCoords = AstronomyApi.fetchPlanetCoordinates(LocalDate.now());
        List<String> constellationLines = AstronomyApi.getProminentConstellationLines(LocalDate.now());
        List<String> meteorShowerLines = new ArrayList<>(); // Static fallback used later

        if (sunMoonTimes != null || moonPhase != null || !planetCoords.isEmpty() || !constellationLines.isEmpty()) {
            todaysApiData = new CombinedAstronomyData(sunMoonTimes, moonPhase, planetCoords, meteorShowerLines, constellationLines, System.currentTimeMillis() / 1000L);
            cacheApiData();
            System.out.println("Ciel Debug (AstronomyService): Primary API fetch successful and data cached.");
        } else {
            System.out.println("Ciel Warning (AstronomyService): Primary API fetch failed. Attempting fallback fetch...");
            performFallbackApiFetch();
        }
    }
    
    private static void performFallbackApiFetch() {
        IpGeoLocationApi.AstronomyData sunMoonData = null;
        String ipGeoApiKey = Settings.getIpGeolocationApiKey();
        if (ipGeoApiKey != null && !ipGeoApiKey.isBlank()) {
            try {
                sunMoonData = IpGeoLocationApi.fetchAstronomyData(LocationService.getLatitude(), LocationService.getLongitude(), ipGeoApiKey);
            } catch (Exception e) {
                System.err.println("Ciel Error (AstronomyService): Fallback IPGeolocation API call failed.");
            }
        }
        
        todaysApiData = new CombinedAstronomyData(
            sunMoonData != null && sunMoonData.sunrise() != null ? Map.of("sunrise", sunMoonData.sunrise().format(DateTimeFormatter.ofPattern("HH:mm:ss")), "sunset", sunMoonData.sunset().format(DateTimeFormatter.ofPattern("HH:mm:ss"))) : null,
            sunMoonData != null ? sunMoonData.moonPhase() : null,
            new ArrayList<>(), // No coords in fallback
            new ArrayList<>(),
            new ArrayList<>(),
            System.currentTimeMillis() / 1000L
        );
        cacheApiData();
        System.out.println("Ciel Debug (AstronomyService): Fallback API fetch complete and data cached.");
    }

    private static void cacheApiData() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(todaysApiData);
            oos.close();
            String serializedData = Base64.getEncoder().encodeToString(baos.toByteArray());
            MemoryService.addFact(new Fact(CACHED_DATA_KEY, serializedData, System.currentTimeMillis(), "system_cache", "system", 1));
            MemoryService.addFact(new Fact(LAST_FETCH_DATE_KEY, LocalDate.now().toString(), System.currentTimeMillis(), "system_cache", "system", 1));
            CielState.setNeedsAstronomyApiFetch(false);
        } catch (Exception e) {
             System.err.println("Ciel Error (AstronomyService): Failed to serialize combined astronomy data for caching.");
             e.printStackTrace();
        }
    }
    
    // --- Dynamic Calculation Logic ---
    
    private static void checkVisiblePlanetsDynamic(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.visiblePlanets", true)) return;
        
        if (todaysApiData.planetCoordinates != null && !todaysApiData.planetCoordinates.isEmpty()) {
            List<String> visibleLines = new ArrayList<>();
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(LocationService.getTimezone()));
            
            for (CombinedAstronomyData.PlanetCoordinate p : todaysApiData.planetCoordinates) {
                // MATH: Check visibility right NOW using cached RA/Dec
                double alt = AstroUtils.getAltitude(p.ra(), p.dec(), LocationService.getLatitude(), LocationService.getLongitude(), now);
                if (alt > 10.0) { // Visible if > 10 degrees above horizon
                     LineManager.getPlanetLine(p.id()).ifPresent(line -> visibleLines.add(line.text()));
                }
            }
            
            // Merge Jupiter/Saturn logic for cleaner speech
            boolean jupiter = visibleLines.stream().anyMatch(s -> s.contains("Jupiter") || s.contains("ジュピター"));
            boolean saturn = visibleLines.stream().anyMatch(s -> s.contains("Saturn") || s.contains("サターン"));
            if (jupiter && saturn) {
                visibleLines.removeIf(s -> s.contains("Jupiter") || s.contains("ジュピター") || s.contains("Saturn") || s.contains("サターン"));
                LineManager.getPlanetLine("jupitersaturn").ifPresent(line -> visibleLines.add(0, line.text()));
            }

            for (int i = 0; i < visibleLines.size(); i++) {
                sequentialEvents.put("planet_" + i, visibleLines.get(i));
            }
        } else {
             checkVisiblePlanetsStatic(sequentialEvents);
        }
    }

    // Static fallback if no coords are cached
    private static void checkVisiblePlanetsStatic(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.visiblePlanets", true)) return;
        System.out.println("Ciel Debug (AstronomyService): No planet data from API. Using static fallback.");
        List<String> planetLines = VisiblePlanets.getVisiblePlanetLines(LocalDate.now().getMonth());
        for (int i = 0; i < planetLines.size(); i++) {
            sequentialEvents.put("planet_" + i, planetLines.get(i));
        }
    }
    
    private static void checkEclipses(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.eclipses", true)) return;
        LocalDate today = LocalDate.now(ZoneId.of(LocationService.getTimezone()));
        Optional<EclipseData.Event> eclipseOpt = EclipseData.findNextEvent(today, 2, 2);

        eclipseOpt.ifPresent(event -> {
            boolean isVisible = isEclipseVisibleFromNA(event.visibility());
            String typeKey = event.type().toLowerCase().contains("lunar") ? "lunar" : "solar";
            
            long daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, event.date());
            String lineKey = (daysUntil == 0) ? "today" : (daysUntil > 0) ? "upcoming" : "aftermath";
            if (!isVisible) lineKey = "notvisible";
            
            String katakanaDesc = PhonoKana.getInstance().toKatakana(event.description());
            LineManager.getEclipseLine(typeKey + "_" + lineKey).ifPresent(line ->
                sequentialEvents.put("eclipse", line.text().replace("{event_desc}", katakanaDesc))
            );
        });
    }
    
    private static boolean isEclipseVisibleFromNA(String visibilityRegion) {
        String lowerRegion = visibilityRegion.toLowerCase();
        return lowerRegion.contains("america") || lowerRegion.contains("north america");
    }

    private static void checkSunriseSunset(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.sunriseSunset", true) || todaysApiData.sunMoonTimes == null) return;
        
        String sunriseStr = todaysApiData.sunMoonTimes.get("sunrise");
        String sunsetStr = todaysApiData.sunMoonTimes.get("sunset");
        
        if (sunriseStr == null || sunsetStr == null) return;

        try {
            String sunriseSpeakable = EnglishNumber.convertTimeToWords(LocalTime.parse(sunriseStr).format(DateTimeFormatter.ofPattern("h:mm a")));
            String sunsetSpeakable = EnglishNumber.convertTimeToWords(LocalTime.parse(sunsetStr).format(DateTimeFormatter.ofPattern("h:mm a")));
            sequentialEvents.put("sunriseSunset", String.format("サンライズ イズ アット %s、アンド サンセット イズ アット %s。", sunriseSpeakable, sunsetSpeakable));
        } catch (Exception e) {
            System.err.println("Ciel Error (AstronomyService): Could not parse sunrise/sunset times for speaking: " + sunriseStr + ", " + sunsetStr);
        }
    }
    
    private static void checkConstellations(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.constellations", true)) return;

        if (todaysApiData.prominentConstellationLines != null && !todaysApiData.prominentConstellationLines.isEmpty()) {
            sequentialEvents.put("constellations", todaysApiData.prominentConstellationLines.get(0));
        } else {
            String constellations = Constellations.forMonth(LocalDate.now().getMonth());
            sequentialEvents.put("constellations", "ヴィジブル コンステレーションズ インクルード " + constellations + "。");
        }
    }

    private static void checkMoonPhase(List<String> ambientLines) {
        if (!config.enabled("show.moonPhase", true) || todaysApiData.moonPhase == null) return;

        String simplifiedPhase = todaysApiData.moonPhase.toLowerCase()
            .replace(" ", "")
            .replace("_", "");

        LineManager.getMoonPhaseLine(simplifiedPhase).ifPresent(line -> ambientLines.add(line.text()));
    }

    private static void checkSeasonal(List<String> ambientLines) {
        DialogueLine seasonalLine = LineManager.getSeasonalLine(LocalDate.now().getMonth());
        if (seasonalLine != null) {
            ambientLines.add(seasonalLine.text());
        }
    }
    
    private static void checkMeteorShowers(List<String> ambientLines) {
        if (!config.enabled("show.meteorShowers", true)) return;
        
        System.out.println("Ciel Debug (AstronomyService): Using static calculation for meteor showers.");
        try {
            ZoneId zone = ZoneId.of(LocationService.getTimezone());
            List<MeteorShowers.Candidate> showers = MeteorShowers.visibleFrom(
                LocationService.getLatitude(), LocationService.getLongitude(), zone,
                config.getInt("meteors.lookaheadDays", 7),
                config.getDouble("meteors.minZHR", 20.0),
                config.getDouble("meteors.minAltitudeDeg", 20.0)
            );
            if (!showers.isEmpty()) {
                String showerName = PhonoKana.getInstance().toKatakana(showers.get(0).shower.name);
                ambientLines.add("ザ " + showerName + " ミーティア シャワー イズ アクティブ アラウンド ディス タイム。");
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (AstronomyService): Failed to check for meteor showers using fallback calculation. " + e.getMessage());
        }
    }
}