package com.cielcompanion.astronomy;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class AstronomyConfig {
    private final Properties props;
    private double locationLat;
    private double locationLon;
    private String locationTimezone;

    public AstronomyConfig(Properties props) {
        this.props = props;
        resolveLocation();
    }

    public static AstronomyConfig loadFromResource(String resourcePath) throws Exception {
        System.out.println("Ciel Debug (AstronomyConfig): Attempting to load resource from classpath: " + resourcePath);
        InputStream is = AstronomyConfig.class.getResourceAsStream(resourcePath);

        if (is == null) {
            System.err.println("Ciel FATAL ERROR (AstronomyConfig): FAILED to find resource '" + resourcePath + "' on the classpath.");
            throw new Exception("Resource not found: " + resourcePath);
        }

        System.out.println("Ciel Debug (AstronomyConfig): Resource '" + resourcePath + "' found successfully. Loading properties...");
        Properties props = new Properties();
        try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            props.load(isr);
            System.out.println("Ciel Debug (AstronomyConfig): Properties loaded successfully.");
            return new AstronomyConfig(props);
        }
    }

    private void resolveLocation() {
        String zip = props.getProperty("location.us.zip");
        String latStr = props.getProperty("location.lat");
        String lonStr = props.getProperty("location.lon");
        String timezone = props.getProperty("location.timezone", "auto");

        if (zip != null && !zip.trim().isEmpty()) {
            this.locationLat = 41.3897; // Parma, OH
            this.locationLon = -81.7351; // Parma, OH
            this.locationTimezone = "America/New_York";
            System.out.println("Ciel Debug (LocationResolver): Using hardcoded location for ZIP " + zip + ": Lat=" + locationLat + ", Lon=" + locationLon);
        } else if (latStr != null && !latStr.trim().isEmpty() && lonStr != null && !lonStr.trim().isEmpty()) {
            this.locationLat = Double.parseDouble(latStr.trim());
            this.locationLon = Double.parseDouble(lonStr.trim());
            this.locationTimezone = timezone;
        } else {
            this.locationLat = 0.0;
            this.locationLon = 0.0;
            this.locationTimezone = "UTC";
        }
    }
    
    public String getString(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public boolean enabled(String key, boolean defaultValue) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(defaultValue)));
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public double getLocationLat() { return locationLat; }
    public double getLocationLon() { return locationLon; }
    public String getLocationTimezone() { return locationTimezone; }
}

