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
    private static boolean refreshScheduled;
    private static String locationSource = "configured_fallback";
    private static String exitNodeState = "unknown";
    private static Double accuracyMeters;

    private static com.google.gson.JsonObject readCommandJson(String... command) throws Exception {
        var result = com.cielcompanion.util.WindowlessCommand.run(java.util.Arrays.asList(command), 12);
        if (result.exitCode() != 0) throw new java.io.IOException("Location helper failed");
        return com.google.gson.JsonParser.parseString(result.output()).getAsJsonObject();
    }

    private static String tailscaleExitNodeState() {
        try {
            var file = java.nio.file.Paths.get(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Tailscale", "tailscale.exe");
            if (!java.nio.file.Files.exists(file)) return "unknown";
            var status = readCommandJson(file.toString(), "status", "--json");
            if (status.has("ExitNodeStatus") && !status.get("ExitNodeStatus").isJsonNull()) return "active";
            if (status.has("BackendState")) return "inactive";
        } catch (Exception ignored) { }
        return "unknown";
    }

    private static boolean tryWindowsLocation() {
        try {
            var script = java.nio.file.Paths.get(System.getProperty("user.dir"), "tools", "location-update", "Get-DeviceLocation.ps1");
            var result = readCommandJson("powershell.exe", "-WindowStyle", "Hidden", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString());
            if (!result.has("available") || !result.get("available").getAsBoolean()) return false;
            double lat = result.get("latitude").getAsDouble(), lon = result.get("longitude").getAsDouble();
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180) return false;
            accuracyMeters = result.get("accuracy_m").getAsDouble();
            locationData = new GeoLocationData(lat, lon, "Windows location estimate", "", new GeoLocationData.TimeZone(java.time.ZoneId.systemDefault().getId()));
            locationSource = "windows_location_estimate";
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static void publishLocation() {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(System.getProperty("user.dir"), ".local", "current-location.json");
            java.nio.file.Files.createDirectories(path.getParent());
            var values = new java.util.LinkedHashMap<String, Object>();
            values.put("observed_at", java.time.Instant.now().toString());
            values.put("source", locationSource);
            values.put("tailscale_exit_node", exitNodeState);
            values.put("accuracy_m", accuracyMeters);
            values.put("name", getLocationName());
            values.put("latitude", getLatitude());
            values.put("longitude", getLongitude());
            values.put("timezone", getTimezone());
            values.put("physical_location_verified", false);
            java.nio.file.Files.writeString(path, new com.google.gson.Gson().toJson(values));
            System.out.println("Ciel Debug (LocationService): Shared weather/astronomy location snapshot published (approximate or configured fallback).");
        } catch (Exception e) {
            System.err.println("Ciel Error (LocationService): Shared snapshot unavailable: " + e.getClass().getSimpleName());
        }
    }

    /**
     * CORRECTED: Initialization is now synchronous to prevent race conditions.
     * It loads a reliable fallback first, then attempts to overwrite it with live API data.
     */
    public static synchronized void initialize() {
        double previousLatitude = getLatitude();
        double previousLongitude = getLongitude();
        locationData = null;
        locationSource = "configured_fallback";
        accuracyMeters = null;
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
            exitNodeState = tailscaleExitNodeState();
            if (!exitNodeState.equals("inactive")) {
                if (!tryWindowsLocation()) {
                    System.out.println("Ciel Debug (LocationService): Exit-node state active/unknown; Windows location unavailable. Using labeled configured fallback, not VPN egress location.");
                }
                return;
            }
            String apiKey = Settings.getIpGeolocationApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                System.out.println("Ciel Debug (LocationService): No IPGeolocation API key found. Using fallback location data.");
                return; // Finally publishes the explicitly labeled fallback.
            }
            GeoLocationData data = IpGeoLocationApi.fetchGeoLocationData(apiKey);
            if (data != null) {
                locationData = data; // Overwrite fallback data with more accurate live data.
                locationSource = "ip_geolocation_approximate";
                locationName = data.city() + ", " + data.state_prov();
                System.out.println("Ciel Debug (LocationService): Successfully fetched and updated location data from API.");
            }
        } catch (Exception e) {
            System.err.println("Ciel Error (LocationService): Failed to fetch geo-location data. Using fallback. Error: " + e.getMessage());
        } finally {
            publishLocation();
            if (refreshScheduled && (Math.abs(previousLatitude - getLatitude()) > .02 || Math.abs(previousLongitude - getLongitude()) > .02)) {
                com.cielcompanion.CielState.setNeedsAstronomyApiFetch(true);
                com.cielcompanion.CielState.resetAstronomyFetchAttempts();
            }
            if (!refreshScheduled) {
                refreshScheduled = true;
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "CielLocationRefresh");
                    thread.setDaemon(true);
                    return thread;
                }).scheduleWithFixedDelay(LocationService::initialize, 30, 30, java.util.concurrent.TimeUnit.MINUTES);
            }
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

