package com.cielcompanion.astronomy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

public class AstronomyService {

    private final AstronomyConfig config;
    private final Gson gson = new Gson();
    private final Random random = new Random();

    // Cache for Open-Meteo API responses to avoid hitting API too frequently
    private JsonObject cachedOpenMeteoResponse = null;
    private LocalDate lastOpenMeteoFetchDate = null;

    public AstronomyService(AstronomyConfig config) {
        this.config = config;
    }

    /**
     * Builds a categorized report of astronomy events for Ciel to announce.
     * This method is the primary interface for CielCompanion to get astronomy data.
     * The returned Map contains categories (e.g., "moonPhase", "meteorShowers") as keys
     * and their corresponding English text descriptions as values.
     * The CielCompanion will then convert these English texts to Katakana-English.
     *
     * @return A map where keys are event categories and values are English descriptions.
     * @throws Exception if data fetching fails.
     */
    public Map<String, String> buildCategorizedReport() throws Exception {
        Map<String, String> report = new HashMap<>();

        // Fetch Open-Meteo data once per day
        fetchOpenMeteoDataForToday();

        // 1. Moon Phase (will now return null from getMoonPhase as Open-Meteo doesn't support it)
        if (config.enabled("show.moonPhase", true)) {
            String moonPhase = getMoonPhase(); // This method will now return null
            if (moonPhase != null && !moonPhase.isEmpty()) {
                report.put("moonPhase", moonPhase); // Store pure English
            }
        }

        // 2. Meteor Showers (simplified placeholder for now)
        if (config.enabled("show.meteorShowers", true)) {
            String meteorShower = getMeteorShowerInfo();
            if (meteorShower != null && !meteorShower.isEmpty()) {
                report.put("meteorShowers", meteorShower); // Store pure English
            }
        }

        // 3. Visible Constellations
        if (config.enabled("show.constellations", true)) {
            String constellations = getConstellationsVisibleTonight();
            if (constellations != null && !constellations.isEmpty()) {
                report.put("constellations", constellations); // Store pure English
            }
        }

        // 4. Sunrise/Sunset
        if (config.enabled("show.sunriseSunset", true)) {
             String sunriseSunset = getSunriseSunsetInfo();
             if (sunriseSunset != null && !sunriseSunset.isEmpty()) {
                 report.put("sunriseSunset", sunriseSunset); // Store pure English
             }
        }

        // Other categories (Planets, Eclipses, Comets) currently rely on external feeds,
        // which are not yet fully implemented or may require specific provider data.
        // For now, they will not be added to the report unless an external feed is provided
        // and processed here.

        return report;
    }


    /**
     * Fetches astronomy data from Open-Meteo API for the current day.
     * Caches the response to avoid repeated API calls within the same day.
     *
     * @throws Exception if the API call fails or returns an error.
     */
    private void fetchOpenMeteoDataForToday() throws Exception {
        LocalDate today = LocalDate.now();
        if (cachedOpenMeteoResponse != null && today.equals(lastOpenMeteoFetchDate)) {
            // Use cached data if already fetched for today
            System.out.println("Ciel Debug (AstronomyService): Using cached Open-Meteo data for " + today);
            return;
        }

        System.out.println("Ciel Debug (AstronomyService): Fetching new Open-Meteo data for " + today);

        double latitude = config.getLocationLat();
        double longitude = config.getLocationLon();
        String timezone = config.getLocationTimezone();

        if (latitude == 0.0 || longitude == 0.0) {
            throw new IllegalStateException("Ciel Error (AstronomyService): Latitude and Longitude not set. Cannot fetch Open-Meteo data.");
        }

        String apiUrl = String.format("https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&daily=sunrise,sunset&timezone=%s",
                latitude, longitude, timezone);

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorMsg = readStream(conn.getErrorStream());
            throw new Exception("Ciel Error (AstronomyService): HTTP " + responseCode + " for " + apiUrl + ": " + errorMsg);
        }

        String jsonResponse = readStream(conn.getInputStream());
        cachedOpenMeteoResponse = gson.fromJson(jsonResponse, JsonObject.class);
        lastOpenMeteoFetchDate = today;

        System.out.println("Ciel Debug (AstronomyService): Successfully fetched Open-Meteo data.");
    }


    /**
     * Retrieves the moon phase for today using Open-Meteo data.
     *
     * @return A string describing the moon phase (e.g., "Waxing Crescent"), or null if not available.
     */
    private String getMoonPhase() {
        System.out.println("Ciel Debug (AstronomyService): Moon phase data is not available from the current Open-Meteo API endpoint.");
        return null;
    }

    /**
     * Retrieves sunrise and sunset times for today using Open-Meteo data.
     *
     * @return A string describing sunrise and sunset times, or null if not available.
     * @throws Exception if data fetching or parsing fails.
     */
    private String getSunriseSunsetInfo() throws Exception {
        if (cachedOpenMeteoResponse == null || !cachedOpenMeteoResponse.has("daily")) {
            System.err.println("Ciel Error (AstronomyService): No daily data available from Open-Meteo for sunrise/sunset.");
            return null;
        }

        JsonObject daily = cachedOpenMeteoResponse.getAsJsonObject("daily");
        JsonArray sunriseTimes = daily.getAsJsonArray("sunrise");
        JsonArray sunsetTimes = daily.getAsJsonArray("sunset");

        if (sunriseTimes == null || sunriseTimes.size() == 0 || sunsetTimes == null || sunsetTimes.size() == 0) {
            System.err.println("Ciel Error (AstronomyService): No sunrise/sunset data found in Open-Meteo response.");
            return null;
        }

        // Assuming the first entry is for today
        String sunriseStr = sunriseTimes.get(0).getAsString();
        String sunsetStr = sunsetTimes.get(0).getAsString();

        ZoneId zoneId = ZoneId.of(config.getLocationTimezone());

        LocalDateTime sunriseLocal = LocalDateTime.parse(sunriseStr);
        LocalDateTime sunsetLocal = LocalDateTime.parse(sunsetStr);

        ZonedDateTime sunriseZoned = sunriseLocal.atZone(zoneId);
        ZonedDateTime sunsetZoned = sunsetLocal.atZone(zoneId);

        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("h:mm a"); // e.g., 6:30 AM
        String formattedSunrise = sunriseZoned.format(timeFormatter);
        String formattedSunset = sunsetZoned.format(timeFormatter);

        // Return in English for Katakana conversion
        return String.format("Sunrise at %s, Sunset at %s.", formattedSunrise, formattedSunset);
    }

    /**
     * Provides information about meteor showers.
     * Currently returns placeholder English meteor shower names.
     *
     * @return A string with meteor shower information.
     */
    private String getMeteorShowerInfo() {
        // Return pure English for Katakana conversion in CielCompanion.
        List<String> activeShowers = new ArrayList<>();
        activeShowers.add("Perseids Peak Tonight");
        activeShowers.add("Geminids Peak Hour");

        if (activeShowers.isEmpty()) {
            return "No active meteor showers detected.";
        }
        return activeShowers.get(random.nextInt(activeShowers.size()));
    }


    /**
     * Provides a list of visible constellations.
     * Currently returns placeholder English constellation names.
     *
     * @return A comma-separated string of constellation names.
     */
    private String getConstellationsVisibleTonight() {
        List<String> constellations = List.of(
            "Orion", "Ursa Major", "Cassiopeia", "Cygnus", "Leo",
            "Scorpius", "Sagittarius", "Lyra", "Aquarius", "Pisces"
        );

        // Select a few random constellations
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < 3; i++) { // Select 3 random constellations
            selected.add(constellations.get(random.nextInt(constellations.size())));
        }
        // Ensure uniqueness for the selected constellations
        selected = selected.stream().distinct().collect(Collectors.toList());

        return String.join(", ", selected);
    }


    /**
     * Helper method to read the content of an InputStream into a String.
     * @param is The InputStream to read.
     * @return The content as a String.
     * @throws Exception if an I/O error occurs.
     */
    private String readStream(java.io.InputStream is) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
