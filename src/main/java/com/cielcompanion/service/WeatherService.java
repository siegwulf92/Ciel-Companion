package com.cielcompanion.service;

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
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public class WeatherService {

    private static final String CACHED_WEATHER_KEY = "ciel.weather.last_api_data";
    private static Properties weatherProps = new Properties();
    private static WeatherData cachedWeatherData = null;
    private static final PhonoKana katakanaConverter = PhonoKana.getInstance();

    public static void initialize() {
        try (InputStream is = WeatherService.class.getResourceAsStream("/weather.properties")) {
            if (is == null) {
                System.err.println("Ciel Error: weather.properties not found. Weather features will be disabled.");
                return;
            }
            weatherProps.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            System.out.println("Ciel Debug: WeatherService initialized and configuration loaded.");
        } catch (Exception e) {
            System.err.println("Ciel Error: Failed to initialize WeatherService.");
            e.printStackTrace();
        }
    }

    public static String getCurrentWeather() {
        return getWeatherData(false);
    }

    public static String getWeatherForecast() {
        return getWeatherData(true);
    }

    private static String getWeatherData(boolean isForecast) {
        String apiKey = weatherProps.getProperty("weather.apiKey");
        if (apiKey == null || apiKey.isBlank() || apiKey.equalsIgnoreCase("YOUR_API_KEY_HERE")) {
            return "My weather functionality has not been configured with a valid API key.";
        }

        long cacheDurationMinutes = Long.parseLong(weatherProps.getProperty("weather.cacheDurationMinutes", "60"));

        if (cachedWeatherData != null && cachedWeatherData.fetchTimeEpochSeconds > 0) {
            Duration duration = Duration.between(Instant.ofEpochSecond(cachedWeatherData.fetchTimeEpochSeconds), Instant.now());
            if (duration.toMinutes() < cacheDurationMinutes) {
                System.out.println("Ciel Debug (WeatherService): Using in-memory cached weather data.");
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
                    System.out.println("Ciel Debug (WeatherService): Using database cached weather data.");
                    cachedWeatherData = dbCachedData;
                    return isForecast ? formatForecastReport(dbCachedData) : formatWeatherReport(dbCachedData);
                }
            }
        }

        return fetchAndCacheNewData(apiKey, isForecast);
    }

    private static String fetchAndCacheNewData(String apiKey, boolean isForecast) {
        System.out.println("Ciel Debug (WeatherService): No valid cache. Fetching new data (forecast=" + isForecast + ").");
        String location = String.format("%f,%f", LocationService.getLatitude(), LocationService.getLongitude());
        String urlString = String.format("http://api.weatherapi.com/v1/forecast.json?key=%s&q=%s&days=1&aqi=no&alerts=no", apiKey, location);

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
}

