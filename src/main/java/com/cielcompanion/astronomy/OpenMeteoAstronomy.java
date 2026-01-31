package com.cielcompanion.astronomy;

import com.google.gson.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Locale;

public class OpenMeteoAstronomy {

    // Using ZonedDateTime to be timezone-aware
    public static class SunMoon {
        private final ZonedDateTime sunrise;
        private final ZonedDateTime sunset;

        public SunMoon(ZonedDateTime sunrise, ZonedDateTime sunset) {
            this.sunrise = sunrise;
            this.sunset = sunset;
        }

        public ZonedDateTime getSunrise() { return sunrise; }
        public ZonedDateTime getSunset() { return sunset; }
    }

    public static SunMoon fetchSunMoon(double lat, double lon, String tz) throws Exception {
        String tzParam = tz == null || tz.equalsIgnoreCase("auto") ? "auto" : URLEncoder.encode(tz, StandardCharsets.UTF_8);
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&daily=sunrise,sunset&timezone=%s",
                lat, lon, tzParam);
        
        System.out.println("Ciel Debug (OpenMeteo): Fetching URL: " + url);
        String json = HttpUtil.get(url, "CielCompanion/1.0");

        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        JsonObject daily = o.getAsJsonObject("daily");
        String sunriseStr = daily.getAsJsonArray("sunrise").get(0).getAsString();
        String sunsetStr  = daily.getAsJsonArray("sunset").get(0).getAsString();

        // CORRECTED: The API returns a local date-time string (e.g., "2025-08-30T06:51").
        // We parse it as a LocalDateTime first, then apply the timezone from the API response.
        String responseTimezone = o.get("timezone").getAsString();
        ZonedDateTime sunrise = LocalDateTime.parse(sunriseStr).atZone(java.time.ZoneId.of(responseTimezone));
        ZonedDateTime sunset = LocalDateTime.parse(sunsetStr).atZone(java.time.ZoneId.of(responseTimezone));
        
        return new SunMoon(sunrise, sunset);
    }
}

