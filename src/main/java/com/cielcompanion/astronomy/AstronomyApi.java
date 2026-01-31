package com.cielcompanion.astronomy;

import com.cielcompanion.service.LocationService;
import com.cielcompanion.service.Settings;
import com.cielcompanion.util.PhonoKana;
import com.cielcompanion.service.LineManager;
import com.google.gson.Gson;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class AstronomyApi {

    private static final String BASE_URL = "https://api.astronomyapi.com/api/v2";
    private static final CloseableHttpClient client = HttpClients.createDefault();
    private static final Gson gson = new Gson();
    private static final Set<String> CIRCUMPOLAR_CONSTELLATIONS = Set.of("Ursa Minor", "Ursa Major", "Draco", "Cepheus", "Cassiopeia");

    // --- DTOs ---
    public static class CelestialBodyData {
        public Data data;
        public static class Data { public Table table; }
        public static class Table { public List<Row> rows; }
        public static class Row { public List<Cell> cells; }
        public static class Cell {
            public String id;
            public String name;
            public Position position;
        }
        public static class Position { public Equatorial equatorial; }
        public static class Equatorial {
            public RightAscension rightAscension;
            public Declination declination;
        }
        public static class RightAscension { public String string; }
        public static class Declination { public String string; }
        // Cell class for meteor showers (compilation fix)
        public static class MeteorCell { public String id; }
    }
    
    public static class ConstellationSearchResult {
        public Data data;
        public static class Data { public Table table; }
        public static class Table { public List<Row> rows; }
        public static class Row { public Entry entry; }
        public static class Entry { public String name; }
    }
    
    // Stub for Meteor DTO structure
    public static class MeteorShowerSearchResult {
         public Data data;
         public static class Data { public Table table; }
         public static class Table { public List<Row> rows; }
         public static class Row { public List<CelestialBodyData.MeteorCell> cells; }
    }

    private static String getEncodedAuth() {
        String appId = Settings.getAstronomyApiApplicationId();
        String appSecret = Settings.getAstronomyApiApplicationSecret();
        if (appId == null) appId = "";
        if (appSecret == null) appSecret = "";
        String credentials = appId + ":" + appSecret;
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    // --- METHODS ---

    /**
     * Delegates to IpGeoLocationApi for reliable Sunrise/Sunset times.
     */
    public static Map<String, String> getSunMoonTimes(LocalDate date) {
        String apiKey = Settings.getIpGeolocationApiKey();
        if (apiKey == null || apiKey.isBlank()) return null;

        try {
            IpGeoLocationApi.AstronomyData data = IpGeoLocationApi.fetchAstronomyData(
                LocationService.getLatitude(), LocationService.getLongitude(), apiKey
            );

            if (data != null && data.sunrise() != null && data.sunset() != null) {
                return Map.of(
                    "sunrise", data.sunrise().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    "sunset", data.sunset().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                );
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (AstronomyApi): Failed to fetch Sun/Moon times via IPGeo: " + e.getMessage());
        }
        return null;
    }

    /**
     * Delegates to IpGeoLocationApi for Moon Phase.
     */
    public static String getMoonPhase(LocalDate date) {
        String apiKey = Settings.getIpGeolocationApiKey();
        if (apiKey == null || apiKey.isBlank()) return null;

        try {
            IpGeoLocationApi.AstronomyData data = IpGeoLocationApi.fetchAstronomyData(
                LocationService.getLatitude(), LocationService.getLongitude(), apiKey
            );
            if (data != null) return data.moonPhase();
        } catch (Exception e) {
            System.err.println("Ciel Error (AstronomyApi): Failed to fetch Moon Phase via IPGeo.");
        }
        return null;
    }

    /**
     * Fetches raw coordinates (RA/Dec) for planets.
     * Does NOT calculate visibility here; that is done dynamically by the Service.
     */
    public static List<CombinedAstronomyData.PlanetCoordinate> fetchPlanetCoordinates(LocalDate date) {
        List<CombinedAstronomyData.PlanetCoordinate> coordinates = new ArrayList<>();
        String dateString = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        // Use Noon as the reference time for daily coordinates (positions shift very little)
        String timeString = "12:00:00"; 

        String url = String.format(Locale.US,
            "%s/bodies/positions?latitude=%.4f&longitude=%.4f&elevation=0&from_date=%s&to_date=%s&time=%s&bodies=venus,mars,jupiter,saturn,uranus,neptune",
            BASE_URL, LocationService.getLatitude(), LocationService.getLongitude(), dateString, dateString, timeString
        );

        HttpGet request = new HttpGet(url);
        configureRequest(request);

        try (CloseableHttpResponse response = client.execute(request)) {
            if (response.getStatusLine().getStatusCode() == 200) {
                String responseBody = EntityUtils.toString(response.getEntity());
                CelestialBodyData bodyData = gson.fromJson(responseBody, CelestialBodyData.class);
                
                if (bodyData != null && bodyData.data != null && bodyData.data.table != null && bodyData.data.table.rows != null) {
                    for (CelestialBodyData.Row row : bodyData.data.table.rows) {
                        if (row.cells == null || row.cells.isEmpty()) continue;
                        CelestialBodyData.Cell cell = row.cells.get(0);
                        try {
                            if (cell.position != null && cell.position.equatorial != null) {
                                double ra = parseRaToDegrees(cell.position.equatorial.rightAscension.string);
                                double dec = parseDecToDegrees(cell.position.equatorial.declination.string);
                                
                                // Store raw data for later calculation
                                coordinates.add(new CombinedAstronomyData.PlanetCoordinate(cell.id, cell.name, ra, dec));
                            }
                        } catch (Exception e) { /* Ignore parse errors */ }
                    }
                }
            } else {
                 System.err.println("Ciel Warning (AstronomyApi): Planet fetch failed (" + response.getStatusLine().getStatusCode() + ").");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return coordinates;
    }

    public static List<String> getProminentConstellationLines(LocalDate date) {
        String dateString = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        // We still fetch constellations based on visibility "now" because mapping 88 constellations
        // to RA/Dec manually for local calculation is too complex for this scope.
        // This remains a "best effort" fetch at the time of the API call.
        String jsonPayload = String.format(Locale.US,
            "{\"view\": {\"type\": \"constellation\"}, \"observer\": {\"date\": \"%s\", \"latitude\": %.4f, \"longitude\": %.4f}, \"position\": {\"altitude\": {\"gt\": 30}}}",
            dateString, LocationService.getLatitude(), LocationService.getLongitude()
        );

        HttpPost request = new HttpPost(BASE_URL + "/search");
        configureRequest(request);
        request.setEntity(new StringEntity(jsonPayload, StandardCharsets.UTF_8));

        try (CloseableHttpResponse response = client.execute(request)) {
            if (response.getStatusLine().getStatusCode() == 200) {
                String responseBody = EntityUtils.toString(response.getEntity());
                ConstellationSearchResult result = gson.fromJson(responseBody, ConstellationSearchResult.class);
                
                if (result != null && result.data != null && result.data.table != null && result.data.table.rows != null) {
                    List<String> names = result.data.table.rows.stream()
                        .map(row -> row.entry.name)
                        .filter(name -> !CIRCUMPOLAR_CONSTELLATIONS.contains(name)) 
                        .limit(3)
                        .map(name -> PhonoKana.getInstance().toKatakana(name))
                        .collect(Collectors.toList());
                    
                    if (!names.isEmpty()) {
                        return List.of("ヴィジブル コンステレーションズ インクルード " + String.join("、 ", names) + "。");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return List.of();
    }
    
    public static MeteorShowerSearchResult searchMeteorShowers(LocalDate date) {
        return null; // Triggers static fallback
    }

    private static void configureRequest(org.apache.http.client.methods.HttpRequestBase request) {
        request.addHeader("Authorization", "Basic " + getEncodedAuth());
        if (request instanceof HttpPost) {
            request.addHeader("Content-Type", "application/json");
        }
    }

    private static String formatTime(String isoTime) {
        try {
            return ZonedDateTime.parse(isoTime).withZoneSameInstant(ZoneId.of(LocationService.getTimezone()))
                    .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        } catch (Exception e) { return null; }
    }

    private static double parseRaToDegrees(String ra) {
        if (ra == null) return 0.0;
        try {
            String[] parts = ra.split("[hms]");
            double h = Double.parseDouble(parts[0].trim());
            double m = Double.parseDouble(parts[1].trim());
            double s = Double.parseDouble(parts[2].trim());
            return (h + m/60 + s/3600) * 15;
        } catch (Exception e) { return 0.0; }
    }

    private static double parseDecToDegrees(String dec) {
        if (dec == null) return 0.0;
        try {
            String[] parts = dec.split("[°'\"]");
            double d = Double.parseDouble(parts[0].trim());
            double m = Double.parseDouble(parts[1].trim());
            double s = Double.parseDouble(parts[2].trim());
            return d < 0 ? d - m/60 - s/3600 : d + m/60 + s/3600;
        } catch (Exception e) { return 0.0; }
    }
}