package com.cielcompanion.service;

import com.cielcompanion.ai.AIEngine;
import com.cielcompanion.memory.Fact;
import com.cielcompanion.memory.MemoryService;
import com.cielcompanion.service.LineManager.DialogueLine;
import com.cielcompanion.util.EnglishNumber;
import com.cielcompanion.util.PhonoKana;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WeatherService {

    private static final String CACHED_WEATHER_KEY = "ciel.weather.last_api_data";
    private static Properties weatherProps = new Properties();
    private static WeatherData cachedWeatherData = null;
    private static final PhonoKana katakanaConverter = PhonoKana.getInstance();
    private static ScheduledExecutorService weatherScheduler;

    public static void initialize() {
        try (InputStream is = WeatherService.class.getResourceAsStream("/weather.properties")) {
            if (is == null) return;
            weatherProps.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            weatherScheduler = Executors.newSingleThreadScheduledExecutor();
            weatherScheduler.scheduleWithFixedDelay(WeatherService::proactiveWeatherCheck, 1, 15, TimeUnit.MINUTES);
            System.out.println("Ciel Debug: WeatherService initialized. Proactive alerts active.");
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void proactiveWeatherCheck() {
        try { getWeatherData(false); } catch (Exception ignored) {}
    }

    public static String getCurrentWeather() { return getWeatherData(false); }
    public static String getWeatherForecast() { return getWeatherData(true); }

    public static String getRawWeatherCondition() {
        if (cachedWeatherData == null) getCurrentWeather(); 
        return (cachedWeatherData != null && cachedWeatherData.current != null) ? cachedWeatherData.current.condition.text : "Unknown";
    }

    private static String getWeatherData(boolean isForecast) {
        String apiKey = weatherProps.getProperty("weather.apiKey");
        if (apiKey == null || apiKey.isBlank() || apiKey.equalsIgnoreCase("YOUR_API_KEY_HERE")) return "Weather API key not configured.";

        if (cachedWeatherData != null && Duration.between(Instant.ofEpochSecond(cachedWeatherData.fetchTimeEpochSeconds), Instant.now()).toMinutes() < 15) {
            return isForecast ? formatForecastReport(cachedWeatherData) : formatWeatherReport(cachedWeatherData);
        }
        
        Optional<Fact> cachedFact = MemoryService.getFact(CACHED_WEATHER_KEY);
        if (cachedFact.isPresent()) {
            WeatherData dbCachedData = new Gson().fromJson(cachedFact.get().value(), WeatherData.class);
            if (dbCachedData != null && Duration.between(Instant.ofEpochSecond(dbCachedData.fetchTimeEpochSeconds), Instant.now()).toMinutes() < 15) {
                cachedWeatherData = dbCachedData;
                return isForecast ? formatForecastReport(dbCachedData) : formatWeatherReport(dbCachedData);
            }
        }

        return fetchAndCacheNewData(apiKey, isForecast);
    }

    private static String fetchAndCacheNewData(String apiKey, boolean isForecast) {
        String location = String.format("%f,%f", LocationService.getLatitude(), LocationService.getLongitude());
        String urlString = String.format("http://api.weatherapi.com/v1/forecast.json?key=%s&q=%s&days=1&aqi=no&alerts=yes", apiKey, location);

        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return "Unable to retrieve weather data.";

            WeatherData newWeatherData = new Gson().fromJson(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8), WeatherData.class);
            newWeatherData.fetchTimeEpochSeconds = Instant.now().getEpochSecond();
            cachedWeatherData = newWeatherData;
            MemoryService.addFact(new Fact(CACHED_WEATHER_KEY, new Gson().toJson(newWeatherData), System.currentTimeMillis(), "system_cache", "system", 1));
            
            evaluateAlerts(newWeatherData);

            conn.disconnect();
            return isForecast ? formatForecastReport(newWeatherData) : formatWeatherReport(newWeatherData);
        } catch (Exception e) { return "Weather service error."; }
    }

    private static void evaluateAlerts(WeatherData data) {
        if (data.alerts == null || data.alerts.alert == null || data.alerts.alert.isEmpty()) return;

        List<Alert> newAlerts = new ArrayList<>();
        String today = LocalDate.now().toString();
        for (Alert alert : data.alerts.alert) {
            String alertKey = "weather_alert_" + today + "_" + alert.event.replaceAll("[^a-zA-Z0-9]", "");
            if (MemoryService.getFact(alertKey).isEmpty()) {
                newAlerts.add(alert);
                MemoryService.addFact(new Fact(alertKey, "Announced", System.currentTimeMillis(), "system_alert", "system", 1));
            }
        }

        if (!newAlerts.isEmpty()) {
            StringBuilder alertContext = new StringBuilder();
            for (Alert alert : newAlerts) alertContext.append("- ").append(alert.event).append(": ").append(alert.desc).append("\n");

            String myCity = LocationService.getLocationName();
            
            // CRITICAL FIX: Stricter prompt to absolutely prevent unprompted weather reports.
            String prompt = "You are Ciel, my protective AI companion. Regional NWS alerts:\n" + alertContext.toString() +
                            "\nMaster's EXACT City: " + myCity + "\nCurrent Weather: " + data.current.condition.text +
                            "\nINSTRUCTION: Ignore minor/regional alerts. If an alert specifically threatens " + myCity + " (e.g., Tornado, Flash Flood, Severe Thunderstorm), give a short, urgent [Concerned] warning. If there is no immediate physical threat to his city, output EXACTLY the word: ABORT. Do NOT give a general weather update under any circumstances. ONLY output the warning or ABORT.";

            AIEngine.generateSilentLogic("[WEATHER_EVALUATION]", prompt).thenAccept(response -> {
                if (response != null && !response.isBlank()) {
                    String cleanResponse = response.trim();
                    // Hard Failsafe: Ensures she stays completely silent if the AI hallucinates a polite weather update anyway
                    if (!cleanResponse.equals("ABORT") && !cleanResponse.contains("ABORT") && 
                        !cleanResponse.toLowerCase().matches(".*(overcast|light rain|sunny|cloudy|clear|current weather).*")) {
                        HabitTrackerService.interruptWithCriticalAnnouncement(cleanResponse);
                    } else {
                        System.out.println("Ciel Debug: Weather alert deemed non-critical or AI hallucinated. Logged silently.");
                    }
                }
            });
        }
    }

    private static String formatWeatherReport(WeatherData data) {
        Optional<DialogueLine> lineOpt = LineManager.getWeatherReportLine();
        if (lineOpt.isEmpty()) return String.format("In %s, it is %.0f degrees and %s.", data.location.name, data.current.tempF, data.current.condition.text);
        
        return lineOpt.get().text().replace("{location}", LocationService.getLocationName())
            .replace("{temperature}", EnglishNumber.convert(String.format("%.0f", data.current.tempF)))
            .replace("{condition}", katakanaConverter.toKatakana(data.current.condition.text));
    }

    private static String formatForecastReport(WeatherData data) {
        ForecastDay forecast = data.forecast.forecastday.get(0);
        Optional<DialogueLine> lineOpt = LineManager.getWeatherForecastLine();
        if (lineOpt.isEmpty()) return String.format("Forecast: %s, High %.0f, Low %.0f.", forecast.day.condition.text, forecast.day.maxtempF, forecast.day.mintempF);

        return lineOpt.get().text().replace("{condition}", katakanaConverter.toKatakana(forecast.day.condition.text))
            .replace("{high_temp}", EnglishNumber.convert(String.format("%.0f", forecast.day.maxtempF)))
            .replace("{low_temp}", EnglishNumber.convert(String.format("%.0f", forecast.day.mintempF)));
    }

    private static class WeatherData {
        Location location; Current current; Forecast forecast; Alerts alerts; long fetchTimeEpochSeconds;
    }
    private static class Location { String name; }
    private static class Current { @SerializedName("temp_f") double tempF; Condition condition; }
    private static class Condition { String text; }
    private static class Forecast { List<ForecastDay> forecastday; }
    private static class ForecastDay { Day day; }
    private static class Day { @SerializedName("maxtemp_f") double maxtempF; @SerializedName("mintemp_f") double mintempF; Condition condition; }
    private static class Alerts { List<Alert> alert; }
    private static class Alert { String headline; String event; String severity; String desc; String effective; String expires; }
}