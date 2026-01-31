package com.cielcompanion.service;

import com.cielcompanion.astronomy.AstronomyConfig;
import com.cielcompanion.astronomy.IpGeoLocationApi;
import com.cielcompanion.astronomy.IpGeoLocationApi.GeoLocationData;

public class LocationService {

    private static GeoLocationData locationData = null;
    private static double fallbackLatitude;
    private static double fallbackLongitude;
    private static String fallbackTimezone;
    private static String locationName;

    /**
     * CORRECTED: Initialization is now synchronous to prevent race conditions.
     * It loads a reliable fallback first, then attempts to overwrite it with live API data.
     */
    public static void initialize() {
        // Step 1: Load fallback data from properties to ensure there's always a valid location.
        try {
            AstronomyConfig config = AstronomyConfig.loadFromResource("/astronomy.properties");
            fallbackLatitude = config.getLocationLat();
            fallbackLongitude = config.getLocationLon();
            fallbackTimezone = config.getLocationTimezone();
            locationName = config.getString("location.us.zip", "your location");
        } catch (Exception e) {
            System.err.println("Ciel FATAL Error: Could not load fallback location from astronomy.properties. Using hardcoded defaults.");
            fallbackLatitude = 41.3897; // Parma, OH
            fallbackLongitude = -81.7351; // Parma, OH
            fallbackTimezone = "America/New_York";
            locationName = "Parma";
        }

        // Step 2: Attempt to fetch live location data from the API to get precise, current info.
        try {
            String apiKey = Settings.getIpGeolocationApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                System.out.println("Ciel Debug (LocationService): No IPGeolocation API key found. Using fallback location data.");
                return; // Exit, leaving the reliable fallback data in place.
            }
            GeoLocationData data = IpGeoLocationApi.fetchGeoLocationData(apiKey);
            if (data != null) {
                locationData = data; // Overwrite fallback data with more accurate live data.
                locationName = data.city() + ", " + data.state_prov();
                System.out.println("Ciel Debug (LocationService): Successfully fetched and updated location data from API.");
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (LocationService): Failed to fetch geo-location data. Using fallback. Error: " + e.getMessage());
        }
    }

    public static double getLatitude() {
        return (locationData != null) ? locationData.latitude() : fallbackLatitude;
    }

    public static double getLongitude() {
        return (locationData != null) ? locationData.longitude() : fallbackLongitude;
    }

    public static String getTimezone() {
        // This will now always return a valid timezone, preventing the NullPointerException.
        return (locationData != null && locationData.time_zone() != null && locationData.time_zone().name() != null)
                ? locationData.time_zone().name()
                : fallbackTimezone;
    }

    public static String getLocationName() {
        return (locationData != null && locationData.city() != null) ? (locationData.city() + ", " + locationData.state_prov()) : locationName;
    }
}

