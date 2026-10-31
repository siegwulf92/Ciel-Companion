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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Advanced Astronomy Service for Ciel.
 * Manages daily API fetches, persistent SQLite caching, and real-time altitude calculations
 * for planets, stars, and deep-sky objects.
 */
public class AstronomyService {

    private static AstronomyConfig config = null;
    private static final String LAST_FETCH_DATE_KEY = "ciel.astronomy.last_fetch_date";
    private static final String CACHED_DATA_KEY = "ciel.astronomy.last_combined_api_data";
    private static String locationKey(String key) {
        String position = String.format(java.util.Locale.ROOT, "%.2f,%.2f", LocationService.getLatitude(), LocationService.getLongitude());
        return key + "." + Integer.toHexString(position.hashCode());
    }
    
    private static CombinedAstronomyData todaysApiData = null;
    private static final long LIVE_POSITION_REFRESH_SECONDS = 30 * 60;

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
        
        Optional<Fact> lastFetchDateFact = MemoryService.getFact(locationKey(LAST_FETCH_DATE_KEY));
        if (lastFetchDateFact.isPresent()) {
            LocalDate lastFetchDate = LocalDate.parse(lastFetchDateFact.get().value());
            if (lastFetchDate.equals(LocalDate.now())) {
                CielState.setNeedsAstronomyApiFetch(false);
                Optional<Fact> cachedDataFact = MemoryService.getFact(locationKey(CACHED_DATA_KEY));

                if (cachedDataFact.isPresent()) {
                     try {
                        byte[] data = Base64.getDecoder().decode(cachedDataFact.get().value());
                        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
                        todaysApiData = (CombinedAstronomyData) ois.readObject();
                        ois.close();
                        
                        boolean isCacheIncomplete = todaysApiData.sunMoonTimes == null || 
                                                    (todaysApiData.planetCoordinates == null) ||
                                                    (todaysApiData.prominentConstellationLines == null);

                        if (isCacheIncomplete) {
                            System.out.println("Ciel Warning: Cached data is incomplete/old format. Forcing refetch.");
                            CielState.setNeedsAstronomyApiFetch(true);
                            CielState.resetAstronomyFetchAttempts();
                        } else {
                            System.out.println("Ciel Debug: Loaded cached combined astronomy data for today.");
                        }
                    } catch (Exception e) {
                        System.err.println("Ciel Error: Failed to deserialize cached data. Forcing refetch.");
                        todaysApiData = null;
                        CielState.setNeedsAstronomyApiFetch(true);
                        CielState.resetAstronomyFetchAttempts();
                    }
                } else {
                     System.out.println("Ciel Warning: Last fetch date is today, but no cached data was found. Forcing refetch.");
                     todaysApiData = null;
                     CielState.setNeedsAstronomyApiFetch(true);
                     CielState.resetAstronomyFetchAttempts();
                }
            } else {
                 System.out.println("Ciel Debug: Last fetch was on a different date. A new fetch is required.");
                CielState.setNeedsAstronomyApiFetch(true);
                CielState.resetAstronomyFetchAttempts();
            }
        } else {
            System.out.println("Ciel Debug: No last fetch date found. A new fetch is required.");
            CielState.setNeedsAstronomyApiFetch(true);
            CielState.resetAstronomyFetchAttempts();
        }
    }

    public static Optional<CombinedAstronomyData> getTodaysApiData() {
        return Optional.ofNullable(todaysApiData);
    }

    /**
     * CRITICAL FIX: Calculates if the sun is physically below the horizon.
     * This absolutely prevents the AI from reporting visible constellations or planets
     * in the middle of the afternoon.
     */
    private static boolean isNightTime() {
        CombinedAstronomyData.PlanetCoordinate sun = body("sun");
        if (sun != null && sun.hasHorizontalPosition()) {
            long age = Math.max(0L, Instant.now().getEpochSecond() - sun.capturedAtEpochSeconds());
            if (age <= LIVE_POSITION_REFRESH_SECONDS * 2) {
                // Civil twilight is still too bright for dependable
                // constellation or meteor claims.
                return sun.altitudeDeg() < -6.0;
            }
        }
        LocalTime now = LocalTime.now(ZoneId.of(LocationService.getTimezone()));
        
        if (todaysApiData != null && todaysApiData.sunMoonTimes != null) {
            String sunriseStr = todaysApiData.sunMoonTimes.get("sunrise");
            String sunsetStr = todaysApiData.sunMoonTimes.get("sunset");
            
            if (sunriseStr != null && sunsetStr != null) {
                try {
                    DateTimeFormatter formatter = sunriseStr.contains("AM") || sunriseStr.contains("PM") ? 
                        DateTimeFormatter.ofPattern("h:mm a") : DateTimeFormatter.ofPattern("HH:mm:ss");
                        
                    LocalTime sunrise = LocalTime.parse(sunriseStr.toUpperCase(), formatter);
                    LocalTime sunset = LocalTime.parse(sunsetStr.toUpperCase(), formatter);
                    
                    if (sunrise.isBefore(sunset)) {
                        return now.isBefore(sunrise) || now.isAfter(sunset);
                    } else {
                        return now.isAfter(sunset) && now.isBefore(sunrise);
                    }
                } catch (Exception e) {
                    System.err.println("Ciel Debug: Error parsing sunset/sunrise for night calculation: " + e.getMessage());
                }
            }
        }
        
        // Solid fallback if API is broken: Assume night is between 8 PM and 6 AM
        return now.getHour() < 6 || now.getHour() >= 20;
    }

    public static AstronomyReport getTodaysAstronomyReport() {
        Map<String, String> sequentialEvents = new LinkedHashMap<>();
        List<String> reportAmbientLines = new ArrayList<>();
        List<String> idleAmbientLines = new ArrayList<>();

        if (!ensureConfigLoaded()) {
            return new AstronomyReport(sequentialEvents, reportAmbientLines, idleAmbientLines);
        }

        refreshLivePositionsIfStale();
        
        boolean isNight = isNightTime();
        
        if (todaysApiData == null) {
            System.out.println("Ciel Warning: No astronomy data available. Using static fallback.");
            if (isNight) {
                checkConstellations(sequentialEvents);
                checkVisiblePlanetsStatic(sequentialEvents); 
            }
            checkMeteorShowers(reportAmbientLines);
            return new AstronomyReport(sequentialEvents, reportAmbientLines, idleAmbientLines);
        }
        
        checkSunriseSunset(sequentialEvents);
        checkEclipses(sequentialEvents);
        
        // CRITICAL FIX: Only report on Deep Sky Objects if the sun is actually down
        if (isNight) {
            checkConstellations(sequentialEvents);
            checkVisiblePlanetsDynamic(sequentialEvents); 
            checkDeepSkyObjectsDynamic(sequentialEvents);
        }
        
        checkMoonPhase(reportAmbientLines);
        checkMoonVisibility(reportAmbientLines);
        checkSeasonal(idleAmbientLines);  
        checkMeteorShowers(reportAmbientLines); 
        
        System.out.printf("Ciel Debug: Found %d sequential, %d report ambient, and %d idle ambient lines.%n", 
            sequentialEvents.size(), reportAmbientLines.size(), idleAmbientLines.size());

        return new AstronomyReport(sequentialEvents, reportAmbientLines, idleAmbientLines);
    }

    public static void performApiFetch() {
        if (!ensureConfigLoaded() || !CielState.needsAstronomyApiFetch()) return;

        CielState.incrementAstronomyFetchAttempts();
        System.out.printf("Ciel Debug: Performing daily API fetch for combined astronomy data... (Attempt %d/3)%n", CielState.getAstronomyFetchAttempts());

        Map<String, String> sunMoonTimes = AstronomyApi.getSunMoonTimes(LocalDate.now());
        String moonPhase = AstronomyApi.getMoonPhase(LocalDate.now());
        ZoneId zone = ZoneId.of(LocationService.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<CombinedAstronomyData.PlanetCoordinate> planetCoords = AstronomyApi.fetchPlanetCoordinates(now.toLocalDate(), now.toLocalTime());
        List<String> constellationLines = AstronomyApi.getProminentConstellationLines(LocalDate.now());

        if (sunMoonTimes != null || moonPhase != null || !planetCoords.isEmpty() || !constellationLines.isEmpty()) {
            todaysApiData = new CombinedAstronomyData(sunMoonTimes, moonPhase, planetCoords, new ArrayList<>(), constellationLines, System.currentTimeMillis() / 1000L);
            cacheApiData();
            System.out.println("Ciel Debug: Primary API fetch successful and data cached.");
        } else {
            System.out.println("Ciel Warning: Primary API fetch failed. Attempting fallback fetch...");
            performFallbackApiFetch();
        }
    }

    private static void refreshLivePositionsIfStale() {
        if (todaysApiData == null) return;
        ZoneId zone = ZoneId.of(LocationService.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zone);
        long newest = todaysApiData.planetCoordinates == null ? 0L
                : todaysApiData.planetCoordinates.stream()
                    .mapToLong(CombinedAstronomyData.PlanetCoordinate::capturedAtEpochSeconds)
                    .max().orElse(0L);
        if (newest > 0 && now.toEpochSecond() - newest < LIVE_POSITION_REFRESH_SECONDS) return;

        List<CombinedAstronomyData.PlanetCoordinate> fresh = AstronomyApi.fetchPlanetCoordinates(
                now.toLocalDate(), now.toLocalTime());
        if (fresh.isEmpty()) {
            System.out.println("Ciel Debug (Astronomy): Live body refresh unavailable; retaining the last verified snapshot.");
            return;
        }
        Map<String, CombinedAstronomyData.PlanetCoordinate> merged = new LinkedHashMap<>();
        if (todaysApiData.planetCoordinates != null) {
            for (CombinedAstronomyData.PlanetCoordinate p : todaysApiData.planetCoordinates) {
                if (p != null && p.id() != null) merged.put(p.id().toLowerCase(Locale.ROOT), p);
            }
        }
        for (CombinedAstronomyData.PlanetCoordinate p : fresh) {
            if (p != null && p.id() != null) merged.put(p.id().toLowerCase(Locale.ROOT), p);
        }
        String moonPhase = todaysApiData.moonPhase;
        CombinedAstronomyData.PlanetCoordinate moon = merged.get("moon");
        if (moon != null && moon.hasMoonPhase()) moonPhase = moon.phaseName();
        todaysApiData = new CombinedAstronomyData(
                todaysApiData.sunMoonTimes, moonPhase,
                new ArrayList<>(merged.values()), todaysApiData.meteorShowerLines,
                todaysApiData.prominentConstellationLines, now.toEpochSecond());
        cacheApiData();
        System.out.println("Ciel Debug (Astronomy): Refreshed observer-time body positions at " + now);
    }
    
    private static void performFallbackApiFetch() {
        IpGeoLocationApi.AstronomyData sunMoonData = null;
        String ipGeoApiKey = Settings.getIpGeolocationApiKey();
        if (ipGeoApiKey != null && !ipGeoApiKey.isBlank()) {
            try {
                sunMoonData = IpGeoLocationApi.fetchAstronomyData(LocationService.getLatitude(), LocationService.getLongitude(), ipGeoApiKey);
            } catch (Exception e) {
                System.err.println("Ciel Error: Fallback IPGeolocation API call failed.");
            }
        }
        
        todaysApiData = new CombinedAstronomyData(
            sunMoonData != null && sunMoonData.sunrise() != null ? Map.of("sunrise", sunMoonData.sunrise().format(DateTimeFormatter.ofPattern("HH:mm:ss")), "sunset", sunMoonData.sunset().format(DateTimeFormatter.ofPattern("HH:mm:ss"))) : null,
            sunMoonData != null ? sunMoonData.moonPhase() : null,
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), System.currentTimeMillis() / 1000L
        );
        cacheApiData();
        System.out.println("Ciel Debug: Fallback API fetch complete and data cached.");
    }

    private static void cacheApiData() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(todaysApiData);
            oos.close();
            String serializedData = Base64.getEncoder().encodeToString(baos.toByteArray());
            MemoryService.addFact(new Fact(locationKey(CACHED_DATA_KEY), serializedData, System.currentTimeMillis(), "system_cache", "system", 1));
            MemoryService.addFact(new Fact(locationKey(LAST_FETCH_DATE_KEY), LocalDate.now().toString(), System.currentTimeMillis(), "system_cache", "system", 1));
            CielState.setNeedsAstronomyApiFetch(false);
        } catch (Exception e) {
             System.err.println("Ciel Error: Failed to serialize combined astronomy data for caching.");
             e.printStackTrace();
        }
    }
    
    private static void checkVisiblePlanetsDynamic(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.visiblePlanets", true)) return;
        
        if (todaysApiData.planetCoordinates != null && !todaysApiData.planetCoordinates.isEmpty()) {
            List<String> visibleLines = new ArrayList<>();
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(LocationService.getTimezone()));
            
            for (CombinedAstronomyData.PlanetCoordinate p : todaysApiData.planetCoordinates) {
                if (p == null || p.id() == null || p.id().equalsIgnoreCase("sun") || p.id().equalsIgnoreCase("moon")) continue;
                double alt = p.hasHorizontalPosition() ? p.altitudeDeg()
                        : AstroUtils.getAltitude(p.ra(), p.dec(), LocationService.getLatitude(), LocationService.getLongitude(), now);
                if (alt < config.getDouble("astronomy.minPlanetAltitudeDeg", 10.0) || !isPlanetObservable(p)) continue;
                LineManager.getPlanetLine(p.id().toLowerCase(Locale.ROOT)).ifPresent(line ->
                        visibleLines.add(line.text() + dynamicBodyDetail(p, alt)));
            }
            
            boolean jupiter = visibleLines.stream().anyMatch(s -> s.contains("Jupiter") || s.contains("ジュピター"));
            boolean saturn = visibleLines.stream().anyMatch(s -> s.contains("Saturn") || s.contains("サターン"));
            if (jupiter && saturn) {
                visibleLines.removeIf(s -> s.contains("Jupiter") || s.contains("Saturn"));
                LineManager.getPlanetLine("jupitersaturn").ifPresent(line -> visibleLines.add(0, line.text()));
            }

            for (int i = 0; i < visibleLines.size(); i++) {
                sequentialEvents.put("planet_" + i, visibleLines.get(i));
            }
        } else {
             checkVisiblePlanetsStatic(sequentialEvents);
        }
    }

    private static void checkDeepSkyObjectsDynamic(Map<String, String> sequentialEvents) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(LocationService.getTimezone()));
    }

    private static CombinedAstronomyData.PlanetCoordinate body(String id) {
        if (todaysApiData == null || todaysApiData.planetCoordinates == null || id == null) return null;
        return todaysApiData.planetCoordinates.stream()
                .filter(p -> p != null && id.equalsIgnoreCase(p.id()))
                .findFirst().orElse(null);
    }

    private static boolean isPlanetObservable(CombinedAstronomyData.PlanetCoordinate p) {
        WeatherService.SkyConditions sky = WeatherService.getCurrentSkyConditions();
        if (sky == null) return true;
        double magnitude = p.hasPhotometry() ? p.magnitude() : 2.0;
        if (sky.cloudCoverPercent() >= config.getInt("astronomy.noObjectCloudCoverPercent", 90)
                || (Double.isFinite(sky.visibilityKm()) && sky.visibilityKm() < config.getDouble("astronomy.noObjectVisibilityKm", 1.5))) return false;
        if (sky.cloudCoverPercent() >= config.getInt("astronomy.brightOnlyCloudCoverPercent", 75) && magnitude > -1.0) return false;
        return !(sky.cloudCoverPercent() >= config.getInt("astronomy.nakedEyeCloudCoverPercent", 60) && magnitude > 2.0);
    }

    private static String dynamicBodyDetail(CombinedAstronomyData.PlanetCoordinate p, double altitude) {
        StringBuilder english = new StringBuilder(" It is about ").append(Math.round(altitude)).append(" degrees above the horizon");
        if (p.hasPhotometry()) english.append(" and has an apparent magnitude near ")
                .append(String.format(Locale.ROOT, "%.1f", p.magnitude()));
        english.append(".");
        return PhonoKana.getInstance().toKatakana(english.toString());
    }

    private static void checkVisiblePlanetsStatic(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.visiblePlanets", true)) return;
        List<String> planetLines = VisiblePlanets.getVisiblePlanetLines(LocalDate.now().getMonth());
        for (int i = 0; i < planetLines.size(); i++) {
            sequentialEvents.put("planet_" + i, planetLines.get(i));
        }
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
            System.err.println("Ciel Error: Could not parse sunrise/sunset times.");
        }
    }
    
    private static void checkConstellations(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.constellations", true)) return;
        if (todaysApiData.prominentConstellationLines != null && !todaysApiData.prominentConstellationLines.isEmpty()) {
            List<String> candidates = todaysApiData.prominentConstellationLines.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .distinct()
                .toList();
            if (candidates.isEmpty()) return;
            // Keep the report concise but use more than the first API row. A
            // date-based rotation prevents the same ordering from dominating
            // every report while remaining deterministic for troubleshooting.
            int count = Math.min(3, candidates.size());
            int start = Math.floorMod(LocalDate.now(ZoneId.of(LocationService.getTimezone())).getDayOfYear(), candidates.size());
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String line = candidates.get((start + i) % candidates.size());
                sequentialEvents.put("constellation_" + i, line);
                selected.add(line);
            }
            System.out.println("Ciel Debug (Astronomy): Selected " + selected.size()
                + " time/location-aware constellation entries from API: " + String.join(" | ", selected));
        } else {
            String constellations = Constellations.forMonth(LocalDate.now().getMonth());
            sequentialEvents.put("constellations", "ヴィジブル コンステレーションズ インクルード " + constellations + "。");
            System.out.println("Ciel Debug (Astronomy): Using monthly constellation fallback: " + constellations);
        }
    }

    private static void checkMoonPhase(List<String> ambientLines) {
        if (!config.enabled("show.moonPhase", true) || todaysApiData.moonPhase == null) return;
        String simplifiedPhase = todaysApiData.moonPhase.toLowerCase().replace(" ", "").replace("_", "");
        LineManager.getMoonPhaseLine(simplifiedPhase).ifPresent(line -> ambientLines.add(line.text()));
    }

    private static void checkMoonVisibility(List<String> ambientLines) {
        if (!config.enabled("show.moonVisibility", true)) return;
        CombinedAstronomyData.PlanetCoordinate moon = body("moon");
        if (moon == null || !moon.hasHorizontalPosition() || moon.altitudeDeg() < 5.0 || skyTooObscured()) return;
        StringBuilder english = new StringBuilder("The Moon is currently ")
                .append(Math.round(moon.altitudeDeg())).append(" degrees above the horizon");
        if (Double.isFinite(moon.phaseFraction())) {
            english.append(" with about ").append(Math.round(moon.phaseFraction() * 100.0)).append(" percent illumination");
        }
        english.append(".");
        ambientLines.add(PhonoKana.getInstance().toKatakana(english.toString()));
    }

    private static boolean skyTooObscured() {
        WeatherService.SkyConditions sky = WeatherService.getCurrentSkyConditions();
        return sky != null && (sky.cloudCoverPercent() >= config.getInt("astronomy.noObjectCloudCoverPercent", 90)
                || (Double.isFinite(sky.visibilityKm()) && sky.visibilityKm() < config.getDouble("astronomy.noObjectVisibilityKm", 1.5)));
    }

    private static void checkSeasonal(List<String> ambientLines) {
        DialogueLine seasonalLine = LineManager.getSeasonalLine(LocalDate.now().getMonth());
        if (seasonalLine != null) {
            ambientLines.add(seasonalLine.text());
        }
    }
    
    private static void checkMeteorShowers(List<String> ambientLines) {
        if (!config.enabled("show.meteorShowers", true)) return;
        // Meteor showers are night-sky events. This ambient path used to bypass
        // the report's daylight gate and could announce a shower while the sun
        // was up.
        if (!isNightTime()) return;
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
            System.err.println("Ciel Error: Failed to check for meteor showers fallback. " + e.getMessage());
        }
    }

    private static void checkEclipses(Map<String, String> sequentialEvents) {
        if (!config.enabled("show.eclipses", true)) return;
        LocalDate today = LocalDate.now(ZoneId.of(LocationService.getTimezone()));
        Optional<EclipseData.Event> eclipseOpt = EclipseData.findNextEvent(today, 2, 2);
        eclipseOpt.ifPresent(event -> {
            boolean isVisible = event.visibility().toLowerCase().contains("america");
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
}


