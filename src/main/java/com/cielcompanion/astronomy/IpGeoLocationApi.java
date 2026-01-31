package com.cielcompanion.astronomy;

import com.cielcompanion.util.HttpUtil;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class IpGeoLocationApi {
    
    // Data Transfer Objects (DTOs) to match the JSON response structure
    public record AstronomyData(
        String date,
        @SerializedName("sunrise") String sunriseStr,
        @SerializedName("sunset") String sunsetStr,
        @SerializedName("moon_phase") String moonPhase
    ) {
        // Helper methods to parse time strings (API returns 24h format HH:mm)
        public LocalTime sunrise() {
            try {
                return LocalTime.parse(sunriseStr, DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception e) { return null; }
        }
        public LocalTime sunset() {
            try {
                return LocalTime.parse(sunsetStr, DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception e) { return null; }
        }
    }

    public record GeoLocationData(
        double latitude,
        double longitude,
        String city,
        String state_prov,
        TimeZone time_zone
    ) {
        public record TimeZone(String name) {}
    }

    public static GeoLocationData fetchGeoLocationData(String apiKey) throws Exception {
        String url = "https://api.ipgeolocation.io/ipgeo?apiKey=" + apiKey;
        System.out.println("Ciel Debug (IpGeoLocation): Fetching Geo URL: " + url);
        String json = HttpUtil.get(url, "CielCompanion/1.0");
        Gson gson = new Gson();
        return gson.fromJson(json, GeoLocationData.class);
    }

    public static AstronomyData fetchAstronomyData(double lat, double lon, String apiKey) throws Exception {
        // Using Locale.US to ensure dots for decimals in lat/long
        String url = String.format(Locale.US,
            "https://api.ipgeolocation.io/astronomy?apiKey=%s&lat=%f&long=%f",
            apiKey, lat, lon);
        
        System.out.println("Ciel Debug (IpGeoLocation): Fetching Astronomy URL: " + url);
        String json = HttpUtil.get(url, "CielCompanion/1.0");
        
        Gson gson = new Gson();
        return gson.fromJson(json, AstronomyData.class);
    }
}