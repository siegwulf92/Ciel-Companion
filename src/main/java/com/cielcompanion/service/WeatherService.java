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
    
    // Background polling for proactive emergency weather alerts
    private static ScheduledExecutorService weatherScheduler;

    public static void initialize() {
        try (InputStream is = WeatherService.class.getResourceAsStream("/weather.properties")) {
            if (is == null) {
                System.err.println("Ciel Error: weather.properties not found. Weather features will be disabled.");
                return;
            }
            weatherProps.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            
            // Strictly poll every 15 minutes
            weatherScheduler = Executors.newSingleThreadScheduledExecutor();
            weatherScheduler.scheduleWithFixedDelay(WeatherService::proactiveWeatherCheck, 1, 15, TimeUnit.MINUTES);
            
            System.out.println("Ciel Debug: WeatherService initialized. Proactive emergency weather alerts active (15m interval).");
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to initialize WeatherService.");
            e.printStackTrace();
        }
    }

    private static void proactiveWeatherCheck() {
        try {
            // Silently fetch weather data. The internal logic handles caching automatically.
            getWeatherData(false);
        } catch (Exception e) {
            // Fail silently so background threads don't crash the app
        }
    }

    public static String getCurrentWeather() {
        return getWeatherData(false);
    }

    public static String getWeatherForecast() {
        return getWeatherData(true);
    }

    public static String getRawWeatherCondition() {
        if (cachedWeatherData == null) {
            getCurrentWeather(); 
        }
        
        if (cachedWeatherData != null && cachedWeatherData.current != null && cachedWeatherData.current.condition != null) {
            return cachedWeatherData.current.condition.text;
        }
        return "Unknown";
    }

    public static String getKatakanaWeatherCondition() {
        String raw = getRawWeatherCondition();
        if ("Unknown".equals(raw)) return "アンノウン";
        return katakanaConverter.toKatakana(raw);
    }

    private static String getWeatherData(boolean isForecast) {
        String apiKey = weatherProps.getProperty("weather.apiKey");
        if (apiKey == null || apiKey.isBlank() || apiKey.equalsIgnoreCase("YOUR_API_KEY_HERE")) {
            return "My weather functionality has not been configured with a valid API key.";
        }

        // Hardcoded to 15 minutes, ignoring the properties file to ensure fresh emergency alerts
        long cacheDurationMinutes = 15;

        if (cachedWeatherData != null && cachedWeatherData.fetchTimeEpochSeconds > 0) {
            Duration duration = Duration.between(Instant.ofEpochSecond(cachedWeatherData.fetchTimeEpochSeconds), Instant.now());
            if (duration.toMinutes() < cacheDurationMinutes) {
                return isForecast ? formatForecastReport(cachedWeatherData) : formatWeatherReport(cachedWeatherData);
            }
        }
        
        Optional<Fact> cachedFact = MemoryService.getFact(CACHED_WEATHER_KEY);
        if (cachedFact.isPresent()) {
            Gson gson = new Gson();
            WeatherData dbCachedData = gson.fromJson(cachedFact.get().value(), WeatherData.class);
            if (dbCachedData != null && dbCachedData.fetchTimeEpochSeconds > 0) {
                Duration duration = Duration.between(Instant.ofEpochSecond(dbCachedData.fetchTimeEpochSeconds), Instant.now());
                if (duration.toMinutes() < cacheDurationMinutes) {
                    cachedWeatherData = dbCachedData;
                    return isForecast ? formatForecastReport(dbCachedData) : formatWeatherReport(dbCachedData);
                }
            }
        }

        return fetchAndCacheNewData(apiKey, isForecast);
    }

    private static String fetchAndCacheNewData(String apiKey, boolean isForecast) {
        System.out.println("Ciel Debug (WeatherService): Fetching new data from WeatherAPI (alerts=yes).");
        String location = String.format("%f,%f", LocationService.getLatitude(), LocationService.getLongitude());
        String urlString = String.format("http://api.weatherapi.com/v1/forecast.json?key=%s&q=%s&days=1&aqi=no&alerts=yes", apiKey, location);

        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) {
                System.err.println("Ciel Error (WeatherService): API call failed with HTTP error code: " + conn.getResponseCode());
                return "I was unable to retrieve the weather information at this time.";
            }

            InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8);
            Gson gson = new Gson();
            WeatherData newWeatherData = gson.fromJson(reader, WeatherData.class);
            newWeatherData.fetchTimeEpochSeconds = Instant.now().getEpochSecond();
            
            cachedWeatherData = newWeatherData;

            Fact weatherFact = new Fact(CACHED_WEATHER_KEY, gson.toJson(newWeatherData), System.currentTimeMillis(), "system_cache", "system", 1);
            MemoryService.addFact(weatherFact);
            
            // PROACTIVE ALERT CHECK WITH AI EVALUATION
            if (newWeatherData.alerts != null && newWeatherData.alerts.alert != null && !newWeatherData.alerts.alert.isEmpty()) {
                List<Alert> newAlerts = new ArrayList<>();
                String today = LocalDate.now().toString();

                for (Alert alert : newWeatherData.alerts.alert) {
                    // Create a safe, unique key for this specific alert today
                    String alertKey = "weather_alert_" + today + "_" + alert.event.replaceAll("[^a-zA-Z0-9]", "");
                    
                    // Check if we've already announced this specific alert today
                    if (MemoryService.getFact(alertKey).isEmpty()) {
                        newAlerts.add(alert);
                        MemoryService.addFact(new Fact(alertKey, "Announced", System.currentTimeMillis(), "system_alert", "system", 1));
                    }
                }

                if (!newAlerts.isEmpty()) {
                    System.out.println("Ciel Debug (WeatherService): Detected " + newAlerts.size() + " new NWS alerts. Dispatching to AI Evaluator Core...");

                    // Build contextual prompt for the Swarm
                    StringBuilder alertContext = new StringBuilder();
                    for (Alert alert : newAlerts) {
                        alertContext.append("- Alert: ").append(alert.event).append("\n");
                        alertContext.append("  Severity: ").append(alert.severity).append("\n");
                        if (alert.effective != null) alertContext.append("  Effective: ").append(alert.effective).append("\n");
                        if (alert.expires != null) alertContext.append("  Expires: ").append(alert.expires).append("\n");
                        if (alert.desc != null) {
                            // Truncate massively long NWS paragraphs to save tokens
                            String safeDesc = alert.desc.length() > 500 ? alert.desc.substring(0, 500) + "..." : alert.desc;
                            alertContext.append("  Description: ").append(safeDesc).append("\n");
                        }
                    }

                    String currentCondition = newWeatherData.current.condition != null ? newWeatherData.current.condition.text : "Unknown";

                    String prompt = "You are Ciel, my protective AI companion. The National Weather Service just issued the following alerts for our area:\n\n" +
                            alertContext.toString() + "\n" +
                            "Current actual weather outside: " + currentCondition + "\n\n" +
                            "INSTRUCTION: Evaluate the true urgency of these alerts using the descriptions and current weather. " +
                            "If it is an immediate physical threat (like a Tornado or Flash Flood), give a short, urgent [Concerned] warning. " +
                            "If it is a lingering or non-immediate issue (like a river flood warning when it's sunny, or a generic watch), give a casual [Observing] advisory so I am aware but not alarmed. " +
                            "Keep your spoken response to 1 or 2 concise sentences, in character. Address me as Master. Do not use markdown, just the emotion tag followed by the text.";

                    // Ask the Swarm to intelligently judge the NWS alert
                    AIEngine.generateSilentLogic("[WEATHER_EVALUATION]", prompt).thenAccept(response -> {
                        if (response != null && !response.isBlank()) {
                            System.out.println("Ciel Debug (WeatherService): AI Evaluated Alert -> " + response.trim());
                            SpeechService.speakPreformatted(response.trim(), null, false, true); 
                        }
                    });
                }
            }

            conn.disconnect();
            return isForecast ? formatForecastReport(newWeatherData) : formatWeatherReport(newWeatherData);

        } catch (Exception e) {
            System.err.println("Ciel Error (WeatherService): Failed during API fetch.");
            e.printStackTrace();
            return "An error occurred while trying to contact the weather service.";
        }
    }

    private static String formatWeatherReport(WeatherData data) {
        if (data == null || data.location == null || data.current == null) {
            return "The weather data I received was incomplete.";
        }
        
        Optional<DialogueLine> lineOpt = LineManager.getWeatherReportLine();
        if (lineOpt.isEmpty()) {
            return String.format("In %s, it is currently %.0f degrees and %s.",
                data.location.name, data.current.tempF, data.current.condition.text.toLowerCase());
        }
        
        String location = LocationService.getLocationName();
        String temperature = EnglishNumber.convert(String.format("%.0f", data.current.tempF));
        String condition = katakanaConverter.toKatakana(data.current.condition.text);

        return lineOpt.get().text()
            .replace("{location}", location)
            .replace("{temperature}", temperature)
            .replace("{condition}", condition);
    }

    private static String formatForecastReport(WeatherData data) {
        if (data == null || data.forecast == null || data.forecast.forecastday.isEmpty()) {
            return "I received incomplete forecast data.";
        }
        
        ForecastDay forecast = data.forecast.forecastday.get(0);

        Optional<DialogueLine> lineOpt = LineManager.getWeatherForecastLine();
        if (lineOpt.isEmpty()) {
            return String.format("The forecast for tomorrow is %s, with a high of %.0f and a low of %.0f.",
                forecast.day.condition.text.toLowerCase(), forecast.day.maxtempF, forecast.day.mintempF);
        }

        String condition = katakanaConverter.toKatakana(forecast.day.condition.text);
        String highTemp = EnglishNumber.convert(String.format("%.0f", forecast.day.maxtempF));
        String lowTemp = EnglishNumber.convert(String.format("%.0f", forecast.day.mintempF));

        return lineOpt.get().text()
            .replace("{condition}", condition)
            .replace("{high_temp}", highTemp)
            .replace("{low_temp}", lowTemp);
    }

    // --- Data Classes for JSON Parsing ---
    private static class WeatherData {
        Location location;
        Current current;
        Forecast forecast;
        Alerts alerts; 
        long fetchTimeEpochSeconds;
    }

    private static class Location {
        String name;
    }

    private static class Current {
        @SerializedName("temp_f")
        double tempF;
        Condition condition;
    }

    private static class Condition {
        String text;
    }
    
    private static class Forecast {
        List<ForecastDay> forecastday;
    }

    private static class ForecastDay {
        Day day;
    }

    private static class Day {
        @SerializedName("maxtemp_f")
        double maxtempF;
        @SerializedName("mintemp_f")
        double mintempF;
        Condition condition;
    }
    
    private static class Alerts {
        List<Alert> alert;
    }
    
    // EXTENDED: Added deeper data capture for AI evaluation
    private static class Alert {
        String headline;
        String event;
        String severity;
        String desc;
        String effective;
        String expires;
    }
}