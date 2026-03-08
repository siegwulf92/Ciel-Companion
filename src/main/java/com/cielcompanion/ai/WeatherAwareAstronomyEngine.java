package com.cielcompanion.ai;

import com.cielcompanion.service.AstronomyService.AstronomyReport;
import com.cielcompanion.service.WeatherService;
import com.google.gson.JsonObject;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Cross-references API data using the AI Evaluator Core to ensure 
 * Ciel only announces celestial events that are physically visible through the local weather and time of day.
 */
public class WeatherAwareAstronomyEngine {

    public static void processReport(AstronomyReport rawReport, Consumer<AstronomyReport> onComplete) {
        String weatherCondition = WeatherService.getRawWeatherCondition();
        
        if (weatherCondition == null || weatherCondition.equalsIgnoreCase("Unknown")) {
            onComplete.accept(rawReport);
            return;
        }

        // Check if there are actually any visual events in the queue today
        List<String> visibleItems = new ArrayList<>();
        for (String key : rawReport.sequentialEvents().keySet()) {
            if (key.startsWith("planet") || key.equals("constellations") || key.equals("eclipse")) {
                visibleItems.add(key);
            }
        }

        if (visibleItems.isEmpty()) {
            onComplete.accept(rawReport);
            return;
        }

        // Get the current local time to pass to the AI
        String currentTimeEn = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"));

        // Instruct the AI to factor in BOTH time of day and weather conditions
        String prompt = "You are Ciel, an advanced Manas. " +
            "The current local time is " + currentTimeEn + ". " +
            "The current local weather condition is: '" + weatherCondition + "'. " +
            "You are evaluating whether to read an astronomy report about visible planets and constellations to your master. " +
            "Rule 1: If it is currently daytime (the sun is up), stars and planets are invisible due to daylight. " +
            "Rule 2: If it is nighttime, bad weather (Cloudy, Overcast, Rain, Snow, Storm) obscures the night sky. " +
            "If the sky is obscured by EITHER daylight OR bad weather, you must cancel the visual report. " +
            "If you cancel it, write a short, wry, or disappointed English sentence explaining exactly why you can't show him the stars right now. (e.g. referencing the sun blocking the view, or the specific weather). " +
            "Output strictly valid JSON: { \"cancel_visuals\": true/false, \"reason\": \"brief internal logic\", \"complaint\": \"Your dynamic English response here (or empty string if false)\" }.";

        AIEngine.evaluateBackground("Check if sky is visible.", prompt).thenAccept(jsonResponse -> {
            if (jsonResponse == null) {
                onComplete.accept(rawReport);
                return;
            }

            try {
                boolean cancelVisuals = jsonResponse.has("cancel_visuals") && jsonResponse.get("cancel_visuals").getAsBoolean();

                if (cancelVisuals) {
                    System.out.println("Ciel Debug: AI determined visuals are obscured (Time: " + currentTimeEn + ", Weather: " + weatherCondition + "). Filtering report.");
                    
                    Map<String, String> filteredEvents = new LinkedHashMap<>(rawReport.sequentialEvents());
                    
                    // Remove visual-dependent events (keeps sunrise/sunset)
                    filteredEvents.keySet().removeIf(k -> k.startsWith("planet") || k.equals("constellations") || k.equals("eclipse"));
                    
                    List<String> filteredReportAmbient = new ArrayList<>(rawReport.reportAmbientLines());
                    filteredReportAmbient.clear(); // Remove meteor showers if sky is obscured
                    
                    // Extract the dynamic English complaint and translate it to Katakana instantly
                    String rawComplaint = jsonResponse.has("complaint") && !jsonResponse.get("complaint").isJsonNull() 
                        ? jsonResponse.get("complaint").getAsString() 
                        : "I cannot show you the stars right now due to current conditions.";
                    
                    if (rawComplaint.isBlank()) {
                        rawComplaint = "I cannot show you the stars right now due to current conditions.";
                    }

                    String weatherComplaintKata = com.cielcompanion.util.PhonoKana.getInstance().toKatakana(rawComplaint);
                    filteredEvents.put("visual_cancellation", weatherComplaintKata);
                    
                    AstronomyReport filteredReport = new AstronomyReport(filteredEvents, filteredReportAmbient, rawReport.idleAmbientLines());
                    onComplete.accept(filteredReport);
                } else {
                    System.out.println("Ciel Debug: AI determined sky is visible. Proceeding normally.");
                    onComplete.accept(rawReport);
                }
            } catch (Exception e) {
                System.err.println("Ciel Warning: Failed to parse weather-astronomy logic.");
                onComplete.accept(rawReport);
            }
        }).exceptionally(e -> {
            onComplete.accept(rawReport);
            return null;
        });
    }
}